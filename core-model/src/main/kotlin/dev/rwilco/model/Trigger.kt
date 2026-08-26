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
     * Once, on a day, at a moment nobody chose: drawn from the hours this person is awake.
     *
     * The other half of the date tile. "Some time on Thursday" is a real thing to want — take
     * the bins out, ring your mother — and pinning it to 09:00 makes it an appointment, which
     * is the thing it is not. The draw is deterministic (see [RandomDraw]) so the app and the
     * scheduler agree on it without storing it, and it moves with the day: a Saturday is drawn
     * from the weekend's longer hours, a Sunday from a night that ends earlier.
     */
    @Serializable
    @SerialName("day_random")
    data class DayRandom(val date: LocalDate) : Trigger

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
     * What "una hora que se repite" grew into. The weekly case it replaces was the only one the
     * app could say, and everything people actually keep in a reminders app — the rent on the
     * first, a birthday, the bins every other Tuesday, a fortnightly review — is one of the
     * other three. The pieces are the ones an RRULE has, minus the ones nobody sets by hand:
     *
     * - [every] and [unit]: how far apart. Every counts blocks of the unit, not occurrences —
     *   "every 2 weeks on Monday and Thursday" is two rings a fortnight, not one a fortnight.
     * - [days]: which days of the week, for [RepeatUnit.WEEK] only. Empty means the weekday
     *   [startsOn] falls on, so a week with nothing ticked is still a sensible weekly.
     * - [monthly]: for [RepeatUnit.MONTH] only, "day 26" or "the fourth Wednesday". Null means
     *   the day of the month [startsOn] falls on.
     * - [time]: the hour, or null for a moment drawn from that day's waking hours — the same
     *   choice, and the same words, as the date tile's.
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

    /** Arriving at or leaving a circle around a place. */
    @Serializable
    @SerialName("location")
    data class Location(
        val lat: Double,
        val lng: Double,
        val radiusM: Int,
        val transition: Transition,
        val label: String,
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

enum class Transition { ENTER, EXIT }

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
 * [DATE_TIME] is no longer one of them — see [OFFERED_KINDS]. A day and a day-with-an-hour were
 * two tiles asking the same question, and the answer to "which one do I want" was always "the
 * one that lets me change my mind", so they are one tile with an hour in it. The entry stays
 * because it is a stored value: somebody's favourite kind is written down by name, and an enum
 * that loses a name loses the whole settings file with it.
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
val OFFERED_KINDS: List<TriggerKind> = TriggerKind.entries - TriggerKind.DATE_TIME

/** What a stored favourite means now that the two date tiles are one. */
fun TriggerKind.offered(): TriggerKind = if (this == TriggerKind.DATE_TIME) TriggerKind.DATE else this

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
 */
fun Trigger.asState(): Condition? = when (this) {
    is Trigger.Location -> Condition.AtPlace(lat, lng, radiusM, label, inside = transition == Transition.ENTER)
    is Trigger.Interval -> Condition.TimeWindow(from, to, days)
    is Trigger.AtDateTime, is Trigger.OnDate, is Trigger.AtTime, is Trigger.Countdown, is Trigger.Random -> null
    is Trigger.DayRandom, is Trigger.Repeat -> null
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
