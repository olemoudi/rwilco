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
     * Once, on a day, at no hour anybody chose: true for the whole of [window] when there is one
     * — "a la hora de comer" — and for the whole of that day's waking hours when there is not.
     *
     * The other half of the date tile. "Some time on Thursday" is a real thing to want — take
     * the bins out, ring your mother — and pinning it to 09:00 makes it an appointment, which is
     * the thing it is not. **It is a stretch, not a lottery** ([openingOf]): it rings when the
     * stretch opens and goes on being true until it closes, so it can be ANDed with a place or
     * fenced by an hour without the ring landing somewhere nobody can see it. The stretch moves
     * with the day — a Saturday gets the weekend's longer hours, a Sunday a night that ends
     * earlier — and chance, where somebody actually wants it, is [Random].
     *
     * The name on disk is `day_random`, from when the moment inside it was drawn. It is frozen,
     * like every other discriminator here.
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
     * - [time]: the hour. Null is no hour anybody chose: it opens with [window] when there is one
     *   and with that day's waking hours when there is not — the same three answers, and the same
     *   words, as the date tile's.
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
         * Only read when [time] is null: the stretch of the day the moment opens with.
         *
         * Last in the list rather than beside [time] where it belongs, because the order of a
         * data class's parameters is its `copy` and its positional calls, and everything that
         * already builds one of these predates it.
         */
        val window: DayWindow? = null,
    ) : Trigger

    /**
     * A time of day and nothing else: "a las 09:00", on [days] (empty means every day).
     *
     * The point in the day that [Interval] is a stretch of, and it exists for the same reason:
     * so it can be combined. "A las 09:00, y a la vez entre el 1 y el 15" and "a las 09:00, y a
     * la vez en casa" are sentences nothing else could write — a date names one day, a window
     * has to be given a width it does not have, and a calendar in "Vuelve" cannot sit in a set
     * at all. Here it is the *moment* of the set and everything else is the state it has to land
     * inside, which is the shape "a la vez" was built around.
     *
     * On its own it is the next such time on an allowed day, and again on the next one if
     * nobody deals with it — exactly what [Interval] does, and bounded the same way: by the
     * fences on its rule, and spent on the first "hecho". An unbounded "todos los días a las
     * nueve" is still "Vuelve"'s to say ([Recurrence.Calendar]), and this is not a second way of
     * saying it: a calendar names *dates*, carries a start and an end, and answers "¿y vuelve?".
     *
     * Not [AtTime], which is the same two fields and cannot be reused: that one is the old
     * "una hora que se repite" tile, and a rule holding one is folded into the calendar it
     * always was on the way in (`foldRepeats`). Reviving it would resurrect every one of those.
     */
    @Serializable
    @SerialName("time_of_day")
    data class TimeOfDay(val time: LocalTime, val days: Set<DayOfWeek> = emptySet()) : Trigger

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
     * A stretch of the calendar rather than a point on it: "entre el 1 y el 15", both days
     * included.
     *
     * What [Interval] is to a day, this is to a year, and it exists for the same reason: some
     * things are true for a while. "Renovar el abono" is not a Tuesday, it is the fortnight the
     * window is open, and writing it as a date meant picking a day out of that fortnight and
     * hoping it was the right one.
     *
     * It names no hour on purpose — the sheet does not offer one — so it rings **at the default
     * time** (the same hour a date with no hour has always meant, `AppSettings`) on [from], and
     * on each later day it is still open, until somebody deals with it. That is exactly what
     * [Interval] does with a stretch of the day, and it is what stops a range written at six in
     * the evening from being a reminder that never rings at all.
     *
     * And it is a *state* for every day from [from] to [to] inclusive. The state is the half
     * that does the work: "al llegar a casa, y a la vez entre el 1 y el 15" is the sentence this
     * makes writable, and the ring is there because a trigger that never rings is not a trigger.
     */
    @Serializable
    @SerialName("date_range")
    data class DateRange(val from: LocalDate, val to: LocalDate) : Trigger

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
     * (empty = every day). The moments are drawn deterministically (see RandomDraw) so the
     * screen and the scheduler agree without storing them.
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
    DATE_RANGE(TriggerFamily.TIME),
    REPEAT_TIME(TriggerFamily.TIME),
    TIME_OF_DAY(TriggerFamily.TIME),
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
 * The tiles in the order the sheet shows them: the favourite first, the rest behind it.
 *
 * The favourite is read through [offered] here as well as where it is stored, because this is
 * the place that breaks when it is not. A favourite outside [kinds] was put at the top *and*
 * left out of nothing, so it came out as an extra row — and once two kinds shared a name it was
 * a row word for word identical to the one under it, wearing "el que sueles usar" and opening
 * the same sheet. One normalisation, in the function that does the ordering, so no caller can
 * reintroduce it.
 */
fun kindsOrdered(preferred: TriggerKind?, kinds: List<TriggerKind> = OFFERED_KINDS): List<TriggerKind> {
    val favourite = preferred?.offered()?.takeIf { it in kinds } ?: return kinds
    return listOf(favourite) + kinds.filter { it != favourite }
}

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
        is Trigger.DayRandom, is Trigger.Repeat, is Trigger.DateRange, is Trigger.TimeOfDay -> TriggerFamily.TIME
        is Trigger.Location -> TriggerFamily.PLACE
        is Trigger.Countdown -> TriggerFamily.TIME
        is Trigger.Random -> TriggerFamily.CHANCE
    }

/**
 * The same trigger read as a *state* — "is this true right now?" — or null when it has none.
 *
 * This is what [RuleMatch.TOGETHER] is built on. A place is a state as much as an event, and
 * **which of the two it is, is decided by what the rule asked for**: "mientras esté en casa"
 * is being inside the circle, true for as long as you are there, and a state; "al salir de
 * casa" ([Trigger.Location.onCrossing]) is the doorway, one instant, and a **moment**. Folding
 * a doorway in as a state is how "al salir del club, y a la vez a las 13:30" came to ring at
 * 13:30 for the mere fact of being elsewhere — the crossing quietly dropped, and the reminder
 * announcing itself as next while it was at it. An interval is a state and nothing else.
 * Everything else is a *moment*: true at one instant and false either side of it, which is
 * exactly why two of them together can never both be true, and why a set with none of them has
 * nothing to start it.
 *
 * A day with no hour is a state too — for the hours this person is up on it, which is what
 * [shape] is for — and not a moment: "el jueves a cualquier hora, y a la vez en la oficina" is
 * a whole day at the office, not one minute of it. Its ring is the opening of that stretch
 * ([openingOf]), so the state and the moment are two readings of the same window.
 */
fun Trigger.asState(shape: DayShape = DayShape.DEFAULT): Condition? = when (this) {
    // A doorway is a moment; a side of a line is a state. See above.
    is Trigger.Location -> if (onCrossing) null else Condition.AtPlace(lat, lng, radiusM, label, inside = presence == Presence.INSIDE)
    is Trigger.Interval -> Condition.TimeWindow(from, to, days)
    // A stretch of the calendar, true on every one of its days. The same shape one unit up.
    is Trigger.DateRange -> Condition.DateRange(from, to)
    // A day with a window on it is a state for as long as the window lasts, exactly as an
    // interval is — and a day with none is a state for as long as its waking hours last.
    // With the date on it: a day is a state about *that* day, and a fold that kept only the
    // hours turned one Sunday evening into every evening. See [Condition.TimeWindow.date].
    is Trigger.DayRandom -> stretchOf(shape).let { Condition.TimeWindow(it.from.toLocalTime(), it.to.toLocalTime(), date = date) }
    is Trigger.AtDateTime, is Trigger.OnDate, is Trigger.AtTime, is Trigger.Countdown, is Trigger.Random -> null
    // A time of day is an instant, which is the whole difference between it and an interval.
    is Trigger.TimeOfDay -> null
    is Trigger.Repeat -> null
}

/** The stretch of the day a date with no hour covers: the one it was given, or the day this person is up for. */
fun Trigger.DayRandom.stretchOf(shape: DayShape): AwakeWindow = window?.on(date) ?: shape.awakeOn(date)

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
        is Trigger.TimeOfDay -> true
        // The default one, which is still an hour a rest must not be standing in front of.
        is Trigger.DateRange -> true
        is Trigger.Countdown, is Trigger.Location -> false
    }

/** Whether this trigger is true only at an instant. See [asState]. */
val Trigger.isMoment: Boolean get() = asState() == null

/**
 * Whether this trigger has a bounded run of moments and then nothing: a date, a day, a
 * countdown, a stretch of the calendar. Everything else comes round again on its own.
 *
 * One list, read by the catch-up (`owedUnderAll`: what a phone switched off across it still
 * owes) and by the editor (`warnings`: "ya ha pasado"). They used to keep two, and a countdown
 * that had run out was on one and not the other — legal to save, warned about by nothing, and
 * silent for ever.
 */
val Trigger.isOneShot: Boolean
    get() = this is Trigger.AtDateTime || this is Trigger.OnDate || this is Trigger.DayRandom ||
        this is Trigger.Countdown || this is Trigger.DateRange

/** The tile that edits an existing trigger (a countdown re-opens as a countdown). */
val Trigger.kind: TriggerKind
    get() = when (this) {
        // One tile edits all three: a date, with an hour or without one.
        is Trigger.AtDateTime, is Trigger.OnDate, is Trigger.DayRandom -> TriggerKind.DATE
        is Trigger.AtTime, is Trigger.Repeat -> TriggerKind.REPEAT_TIME
        is Trigger.Interval -> TriggerKind.INTERVAL
        is Trigger.TimeOfDay -> TriggerKind.TIME_OF_DAY
        is Trigger.DateRange -> TriggerKind.DATE_RANGE
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
 * Left alone when the window has not opened yet (nothing to narrow) and when it has closed (the
 * "ya ha pasado" word is then the right one). A day still open past midnight — the small hours
 * of a Saturday belong to a Friday's waking window — is laid on the day it is now: a window on
 * the Friday cannot start on the Saturday, and left as written it was born overdue and never
 * rang, for a stretch the same trigger read as *open* when asked as a state.
 */
fun settleDays(rules: List<TriggerRule>, now: Instant, zone: ZoneId, shape: DayShape): List<TriggerRule> = rules.map { rule ->
    val day = rule.trigger as? Trigger.DayRandom ?: return@map rule
    val window = day.stretchOf(shape)
    val nextMinute = now.atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
    if (nextMinute <= window.from || nextMinute >= window.to) return@map rule
    rule.copy(trigger = day.copy(date = nextMinute.toLocalDate(), window = DayWindow(nextMinute.toLocalTime(), window.to.toLocalTime())))
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
