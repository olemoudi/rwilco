@file:UseSerializers(
    LocalDateSerializer::class,
    LocalTimeSerializer::class,
    LocalDateTimeSerializer::class,
    DayOfWeekSerializer::class,
    InstantSerializer::class,
)

package dev.rwilco.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * What makes a reminder fire. The `@SerialName`s are the on-disk discriminators and are frozen:
 * renaming one silently drops every reminder of that kind on the next app start (the codec skips
 * triggers it does not recognise rather than losing the whole reminder).
 */
@Serializable
sealed interface Trigger {

    /** Once, at a wall-clock moment. A countdown is its own kind, [Countdown]: a length that resolves to an instant when it is saved. */
    @Serializable
    @SerialName("at_date_time")
    data class AtDateTime(val at: LocalDateTime) : Trigger

    /**
     * Once, on a day; rings at the user's default time (a setting, not stored here).
     *
     * Nothing writes one of these any more — the date tile hands back an [AtDateTime] with the
     * default time already in it, or a [DayRandom]. It stays because reminders written before
     * that are still on people's phones, and it still means exactly what it meant.
     */
    @Serializable
    @SerialName("on_date")
    data class OnDate(val date: LocalDate) : Trigger

    /**
     * Once, on a day, at a moment nobody chose: drawn from the hours this person is awake, or
     * from [window] when there is one — "a la hora de comer" rather than "some time today".
     *
     * The other half of the date tile. "Some time on Thursday" is a real thing to want — take
     * the bins out, ring your mother — and pinning it to 09:00 makes it an appointment, which
     * is the thing it is not. The draw is deterministic (see [RandomDraw]) so the app and the
     * scheduler agree on it without storing it, and it moves with the day: a Saturday is drawn
     * from the weekend's longer hours, a Sunday from a night that ends earlier.
     */
    @Serializable
    @SerialName("day_random")
    data class DayRandom(val date: LocalDate, val window: DayWindow? = null) : Trigger

    /**
     * Every week on [days], at [time]. Superseded by [Repeat], and kept for the reminders that
     * were written with it: "todos los martes a las nueve" is a weekly [Repeat] now.
     */
    @Serializable
    @SerialName("at_time")
    data class AtTime(val time: LocalTime, val days: Set<DayOfWeek>) : Trigger

    /**
     * A recurrence with a shape: every N days, weeks, months or years, from a day, until it
     * stops.
     *
     * **No longer a way of starting.** This is the calendar behind [Recurrence.Calendar] and it
     * is reached from "Vuelve", never from the "cuándo" card: a repeat is the answer to "¿y
     * vuelve?", and having it in both places meant nothing on either screen said which of the
     * two a reminder had. It stays a [Trigger] because that is the shape every phone already has
     * on disk and the discriminators are frozen — the ones stored as rules are folded into the
     * recurrence on the way in (`foldRepeats`).
     *
     * The pieces are the ones an RRULE has, minus the ones nobody sets by hand:
     *
     * - [every] and [unit]: how far apart. Every counts blocks of the unit, not occurrences —
     *   "every 2 weeks on Monday and Thursday" is two rings a fortnight, not one a fortnight.
     * - [days]: which days of the week, for [RepeatUnit.WEEK] only. Empty means the weekday
     *   [startsOn] falls on, so a week with nothing ticked is still a sensible weekly.
     * - [monthly]: for [RepeatUnit.MONTH] only, "day 26" or "the fourth Wednesday". Null means
     *   the day of the month [startsOn] falls on.
     * - [time]: the hour. Null is a moment nobody chose, drawn from [window] when there is one
     *   and from that day's waking hours when there is not — the same three answers, and the
     *   same words, as the date tile's.
     * - [startsOn]: the first day it can ring, and the anchor every block is counted from.
     *   Moving it moves the whole series, which is why it is asked for rather than assumed.
     * - [ends]: never, on a date, or after so many times.
     *
     * Nothing here ever skips a block: a "day 31" in February rings on the 28th rather than not
     * at all, and the ordinals stop at "fourth" and "last", which every month has. A reminder
     * that silently misses a month is a worse failure than one that rings a day early, and the
     * count behind [RepeatEnd.After] can only be exact if every block produces its dates.
     */
    @Serializable
    @SerialName("repeat")
    data class Repeat(
        val startsOn: LocalDate,
        val every: Int = 1,
        val unit: RepeatUnit = RepeatUnit.WEEK,
        val time: LocalTime? = null,
        val days: Set<DayOfWeek> = emptySet(),
        val monthly: MonthlyOn? = null,
        val ends: RepeatEnd = RepeatEnd.Never,
        /**
         * Only read when [time] is null: the stretch of the day the moment is drawn from.
         *
         * Last in the list rather than beside [time] where it belongs, because the order of a
         * data class's parameters is its `copy` and its positional calls, and everything that
         * already builds one of these predates it.
         */
        val window: DayWindow? = null,
    ) : Trigger

    /**
     * A stretch of the day rather than a point in it: "de 17 a 19".
     *
     * The only trigger that is a *state* — it is true for two hours, not at one instant — and
     * that is the whole reason it exists. Under [RuleMatch.TOGETHER] it is what makes "en la
     * oficina, entre las cinco y las siete" a thing somebody can write, and as a rule's own
     * condition it is the same window a [Condition.TimeWindow] is.
     *
     * On its own it rings at [from], because a trigger that never rings is not a trigger and
     * the start is the moment the window becomes true. A window that ends before it starts
     * crosses midnight, exactly as the condition's does.
     */
    @Serializable
    @SerialName("interval")
    data class Interval(
        val from: LocalTime,
        val to: LocalTime,
        val days: Set<DayOfWeek> = emptySet(),
    ) : Trigger

    /**
     * A stretch of time from the moment it starts, not a moment on the calendar.
     *
     * This is what "dentro de media hora" is, and storing it as the date-time it worked out to
     * was wrong in two ways: a preset could only ever hold the half hour after the day it was
     * written, and re-setting one on an old reminder counted from the wrong place. [startedAt]
     * is stamped when the reminder is saved (`startCountdowns`); null means it has not begun —
     * a preset's copy, or a draft on its way to being saved — and reads as "from now".
     */
    @Serializable
    @SerialName("countdown")
    data class Countdown(val minutes: Int, val startedAt: Instant? = null) : Trigger

    /**
     * Being inside a circle around a place, or being outside it.
     *
     * **A place is a state, and [onCrossing] is the exception.** It used to be an event and
     * only an event — "al llegar" meant a line the phone had to be *seen* going through — and
     * that one decision spread: a reminder written at home would not ring until you had left
     * and come back, a set under "todos" could not tick off a place you were already standing
     * in, and the first fix of a session had to be biased towards silence so it did not invent
     * an arrival. Most of the time nobody means the doorway; they mean "cuando esté en casa".
     *
     * So [presence] says which side of the line the rule is about and nothing more: it holds
     * whenever the phone is on that side, whether or not anybody watched it get there. A
     * reminder that is only "mientras esté en casa", written at home, rings at once.
     *
     * [onCrossing] asks for the doorway back — "al llegar", "al salir" — and means exactly one
     * thing: a side nobody has seen yet does not count as the other side. The phone has to be
     * seen on the far side first, and only then does arriving ring.
     */
    @Serializable
    @SerialName("location")
    data class Location(
        val lat: Double,
        val lng: Double,
        val radiusM: Int,
        // The on-disk key is "transition", from when this was a crossing and nothing else.
        @SerialName("transition") val presence: Presence,
        val label: String,
        val onCrossing: Boolean = false,
    ) : Trigger

    /**
     * [timesPer] random moments per [period], each inside the [from]..[to] window, on [days]
     * (empty = every day). The moments are drawn deterministically (see RandomDraw) so the app
     * and the phase-2 scheduler agree without storing them.
     */
    @Serializable
    @SerialName("random")
    data class Random(
        val timesPer: Int,
        val period: Period = Period.DAY,
        val from: LocalTime,
        val to: LocalTime,
        val days: Set<DayOfWeek> = emptySet(),
    ) : Trigger
}

/**
 * Which way a phone went through a line. The watch's word, not a rule's: a crossing is a thing
 * that happens, and only the geofences and the step between two fixes ever see one.
 */
enum class Transition { ENTER, EXIT }

/**
 * Which side of a line a rule is about. A rule's word, and a *state*: true for as long as the
 * phone is on that side.
 *
 * The names on disk are the crossings this used to be, and they are frozen: every phone holds
 * `"transition":"ENTER"` for what is now [INSIDE]. Reading them as sides rather than doorways is
 * the whole of the change — see [Trigger.Location].
 */
@Serializable
enum class Presence {
    @SerialName("ENTER")
    INSIDE,

    @SerialName("EXIT")
    OUTSIDE,
}

/** The side as the watch counts it, for a circle it is keeping an eye on. */
val Presence.asTransition: Transition
    get() = if (this == Presence.INSIDE) Transition.ENTER else Transition.EXIT

/** The other side: what "mientras no estoy" is to "mientras estoy". */
val Presence.opposite: Presence
    get() = if (this == Presence.INSIDE) Presence.OUTSIDE else Presence.INSIDE

enum class Period { DAY, WEEK }

/** How far apart a [Trigger.Repeat] repeats. */
@Serializable
enum class RepeatUnit { DAY, WEEK, MONTH, YEAR }

/** Which day of the month a monthly [Trigger.Repeat] lands on. */
@Serializable
sealed interface MonthlyOn {
    /** The [day]th, or the last day of a month too short to have one. */
    @Serializable
    @SerialName("day_of_month")
    data class Day(val day: Int) : MonthlyOn

    /**
     * The [ordinal]th [day] of the month: 1..4, or -1 for the last one. There is deliberately
     * no fifth — four months in five do not have one, and "the fifth Tuesday" is a rule that
     * mostly does not ring.
     */
    @Serializable
    @SerialName("nth_weekday")
    data class Nth(val ordinal: Int, val day: DayOfWeek) : MonthlyOn
}

/** When a [Trigger.Repeat] stops. */
@Serializable
sealed interface RepeatEnd {
    @Serializable
    @SerialName("never")
    data object Never : RepeatEnd

    /** The last day it can ring on; a moment later that day still counts. */
    @Serializable
    @SerialName("on")
    data class On(val date: LocalDate) : RepeatEnd

    /** After this many rings, counted from the first one on or after `startsOn`. */
    @Serializable
    @SerialName("after")
    data class After(val times: Int) : RepeatEnd
}

/**
 * The colour a trigger is recognised by, everywhere it appears. Three, not six: past three a
 * palette stops helping recognition. The amber of the theme is deliberately not among them — it
 * means "this is what fires next", never "this kind of trigger".
 */
enum class TriggerFamily { TIME, PLACE, CHANCE }

/**
 * The tiles of the "add trigger" sheet; how a person picks, not how it is stored.
 *
 * [DATE_TIME] and [REPEAT_TIME] are no longer among them — see [OFFERED_KINDS]. A day and a
 * day-with-an-hour were two tiles asking the same question, and the answer to "which one do I
 * want" was always "the one that lets me change my mind", so they are one tile with an hour in
 * it. A repeating time was a whole second way of saying what "Vuelve" says, on a different card,
 * with nothing on either screen to tell you which one a reminder had; it is a calendar in
 * "Vuelve" now ([Recurrence.Calendar]). Both entries stay because they are stored values:
 * somebody's favourite kind is written down by name, and an enum that loses a name loses the
 * whole settings file with it.
 */
enum class TriggerKind(val family: TriggerFamily) {
    DATE_TIME(TriggerFamily.TIME),
    DATE(TriggerFamily.TIME),
    REPEAT_TIME(TriggerFamily.TIME),
    INTERVAL(TriggerFamily.TIME),
    COUNTDOWN(TriggerFamily.TIME),
    PLACE(TriggerFamily.PLACE),
    RANDOM(TriggerFamily.CHANCE),
}

/** The tiles actually offered, in order. See [TriggerKind]. */
val OFFERED_KINDS: List<TriggerKind> = TriggerKind.entries - TriggerKind.DATE_TIME - TriggerKind.REPEAT_TIME

/**
 * What a stored favourite means now that the two date tiles are one and the repeating time has
 * moved to "Vuelve". A favourite that is no longer a tile falls back to the date, which is the
 * nearest thing still on the sheet — and the sheet must never open with nothing marked.
 */
fun TriggerKind.offered(): TriggerKind = if (this in OFFERED_KINDS) this else TriggerKind.DATE

/**
 * Whether this trigger works out its own next date, over and over, without being asked again.
 *
 * What makes "por calendario" an answer somebody can give: these three name dates rather than
 * one moment, so a reminder carrying one has a calendar to come back on. Everything else fires
 * once and has nothing more to say.
 */
val Trigger.decidesItsOwnDates: Boolean
    get() = this is Trigger.Repeat || this is Trigger.Random || this is Trigger.AtTime

val Trigger.family: TriggerFamily
    get() = when (this) {
        is Trigger.AtDateTime, is Trigger.OnDate, is Trigger.AtTime, is Trigger.Interval -> TriggerFamily.TIME
        is Trigger.DayRandom, is Trigger.Repeat -> TriggerFamily.TIME
        is Trigger.Location -> TriggerFamily.PLACE
        is Trigger.Countdown -> TriggerFamily.TIME
        is Trigger.Random -> TriggerFamily.CHANCE
    }

/**
 * The same trigger read as a *state* — "is this true right now?" — or null when it has none.
 *
 * This is what [RuleMatch.TOGETHER] is built on. A place is a state as much as an event: the
 * crossing is what wakes the app, but being inside the circle is true for as long as you are
 * there, and which of the two a rule means is decided by what it is asked. An interval is a
 * state and nothing else. Everything else is a *moment*: true at one instant and false either
 * side of it, which is exactly why two of them together can never both be true, and why a set
 * with none of them has nothing to start it.
 *
 * A day with no hour is a state too — for the hours this person is up on it, which is what
 * [shape] is for — and not a moment: "el jueves a cualquier hora, y a la vez en la oficina" is
 * a whole day at the office, not one minute of it. See [whenCombined].
 */
fun Trigger.asState(shape: DayShape = DayShape.DEFAULT): Condition? = when (this) {
    is Trigger.Location -> Condition.AtPlace(lat, lng, radiusM, label, inside = presence == Presence.INSIDE)
    is Trigger.Interval -> Condition.TimeWindow(from, to, days)
    // A day with a window on it is a state for as long as the window lasts, exactly as an
    // interval is — and a day with none is a state for as long as its waking hours last. See
    // [whenCombined] for the other half of what that means.
    is Trigger.DayRandom -> stretchOf(shape).let { Condition.TimeWindow(it.from.toLocalTime(), it.to.toLocalTime()) }
    is Trigger.AtDateTime, is Trigger.OnDate, is Trigger.AtTime, is Trigger.Countdown, is Trigger.Random -> null
    is Trigger.Repeat -> null
}

/** The stretch of the day a date with no hour is drawn from: the one it was given, or the day this person is up for. */
fun Trigger.DayRandom.stretchOf(shape: DayShape): AwakeWindow = window?.on(date) ?: shape.awakeOn(date)

/**
 * The same trigger as its own set makes it, for a set that combines ([RuleMatch.ALL] and
 * [RuleMatch.TOGETHER]).
 *
 * **A window is only a draw while it depends on nothing else.** "El viernes a la hora de comer"
 * on its own means a minute nobody chose somewhere between two and four, which is the whole
 * point of naming a stretch instead of an hour. Put it in a set and that reading falls apart:
 * "a la hora de comer, y a la vez en la oficina" cannot mean "at 15:37 if you happen to be at
 * the office" — a draw that lands while the other half is false is a reminder that silently
 * does not ring. In a set the window is a *gate*: it is met as soon as it is open, so the ring
 * lands the moment everything else is true and we are inside it, which can be its first second.
 *
 * So the moment becomes the opening, and the window becomes a condition on every sibling
 * ([asState]) — which is exactly what an [Trigger.Interval] has always been, reached from the
 * other side. A day with no window is the same gate over the hours this person is up ([shape]):
 * "el jueves, y a la vez en la oficina" used to be one minute of Thursday, drawn from the whole
 * day and rung only if the phone happened to be inside the circle at it. And the gate opens at
 * the first minute the rule's own hour [fences] allow, not at the window's start: a door that
 * opened at eight for a rule that says "sólo de 16 a 17" was a moment the fence rejected, and a
 * set that never completed. Everything else comes back untouched.
 */
fun Trigger.whenCombined(shape: DayShape = DayShape.DEFAULT, fences: List<Condition.TimeWindow> = emptyList()): Trigger = when (this) {
    is Trigger.DayRandom -> Trigger.AtDateTime(gateOpening(stretchOf(shape), fences))
    else -> this
}

/**
 * The first minute of [window] that every one of [fences] allows, or its start when none does —
 * the walk that asks the fences then answers *never*, and says so, instead of a set that waits
 * for a moment it has already ruled out.
 */
private fun gateOpening(window: AwakeWindow, fences: List<Condition.TimeWindow>): LocalDateTime {
    if (fences.isEmpty()) return window.from
    var at = window.from
    while (at < window.to) {
        if (fences.all { it.holdsAt(at) }) return at
        at = at.plusMinutes(1)
    }
    return window.from
}

/**
 * Whether this trigger names an hour of the day it is due on.
 *
 * What a rest defers to (see `restUntil`). A place names no hour — it rings whenever somebody
 * arrives, at any hour it is being watched — and neither does a countdown, which names a moment
 * rather than a time of day. Everything else does, including the two that leave the choosing to
 * the day: an hour drawn from somebody's waking hours is still an hour, and still one the rest
 * must not be standing in front of.
 */
val Trigger.namesAnHour: Boolean
    get() = when (this) {
        is Trigger.AtDateTime, is Trigger.OnDate, is Trigger.DayRandom -> true
        is Trigger.AtTime, is Trigger.Repeat, is Trigger.Interval, is Trigger.Random -> true
        is Trigger.Countdown, is Trigger.Location -> false
    }

/** Whether this trigger is true only at an instant. See [asState]. */
val Trigger.isMoment: Boolean get() = asState() == null

/** The tile that edits an existing trigger (a countdown re-opens as a countdown). */
val Trigger.kind: TriggerKind
    get() = when (this) {
        // One tile edits all three: a date, with an hour or without one.
        is Trigger.AtDateTime, is Trigger.OnDate, is Trigger.DayRandom -> TriggerKind.DATE
        is Trigger.AtTime, is Trigger.Repeat -> TriggerKind.REPEAT_TIME
        is Trigger.Interval -> TriggerKind.INTERVAL
        is Trigger.Location -> TriggerKind.PLACE
        is Trigger.Countdown -> TriggerKind.COUNTDOWN
        is Trigger.Random -> TriggerKind.RANDOM
    }

/**
 * Start the clock on any countdown that has not begun. Called where a reminder is written —
 * from the editor or straight from a preset — so "dentro de media hora" counts from the moment
 * it was asked for, not from whenever the shape was invented.
 */
fun startCountdowns(rules: List<TriggerRule>, now: Instant): List<TriggerRule> = rules.map { rule ->
    val trigger = rule.trigger
    if (trigger is Trigger.Countdown && trigger.startedAt == null) rule.copy(trigger = trigger.copy(startedAt = now)) else rule
}

/**
 * A day whose hour was left to the day, written while that day is already under way, is drawn
 * from what is left of it.
 *
 * "Hoy, a cualquier hora" saved at five in the afternoon drew its minute from the whole day —
 * by (reminder, day), the same on every screen — and one time in two that minute had already
 * gone by: the reminder was born overdue and never rang, and the editor could not say so,
 * because the id the draw is seeded by is minted at the save. So the window is narrowed to
 * what is left, here, where a countdown is stamped ([startCountdowns]) and for the same reason:
 * the moment it is written is the moment it starts. The card then says exactly what will
 * happen — "hoy entre las 17:04 y las 23:30" — and [warnings] runs the same narrowing, so what
 * it says and what is saved agree without either knowing the id.
 *
 * Left alone when the window has not opened yet (nothing to narrow), when it has closed (the
 * "ya ha pasado" word is then the right one), and when the next minute is not on the day at
 * all — the small hours of a Saturday belong to a Friday's waking window, and a window laid on
 * the Friday cannot start on the Saturday.
 */
fun settleDays(rules: List<TriggerRule>, now: Instant, zone: ZoneId, shape: DayShape): List<TriggerRule> = rules.map { rule ->
    val day = rule.trigger as? Trigger.DayRandom ?: return@map rule
    val window = day.stretchOf(shape)
    val nextMinute = now.atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
    if (nextMinute.toLocalDate() != day.date) return@map rule
    if (nextMinute <= window.from || nextMinute >= window.to) return@map rule
    rule.copy(trigger = day.copy(window = DayWindow(nextMinute.toLocalTime(), window.to.toLocalTime())))
}

/**
 * The countdown a configurator hands back, given the one it was opened on.
 *
 * A length that has not changed is **the same timer, still running**: opening the sheet to look
 * at it, or to change something else about the reminder, must not put it back to the beginning.
 * That is what it did — the sheet always built a fresh countdown, [startCountdowns] stamped it
 * at the save, and "in ten minutes" quietly became ten minutes from whenever you pressed save.
 * A length somebody actually changed is a new timer and starts when the reminder is written.
 */
fun countdownOf(minutes: Int, previous: Trigger.Countdown?): Trigger.Countdown =
    if (previous != null && previous.minutes == minutes) previous else Trigger.Countdown(minutes)

/** The other way: a preset keeps the length and never the moment, or it could only be used once. */
fun clearCountdowns(rules: List<TriggerRule>): List<TriggerRule> = rules.map { rule ->
    val trigger = rule.trigger
    if (trigger is Trigger.Countdown && trigger.startedAt != null) rule.copy(trigger = trigger.copy(startedAt = null)) else rule
}
