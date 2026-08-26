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

    /** Once, on a day; rings at the user's default time (a setting, not stored here). */
    @Serializable
    @SerialName("on_date")
    data class OnDate(val date: LocalDate) : Trigger

    /** Every week on [days], at [time]. */
    @Serializable
    @SerialName("at_time")
    data class AtTime(val time: LocalTime, val days: Set<DayOfWeek>) : Trigger

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

/**
 * The colour a trigger is recognised by, everywhere it appears. Three, not six: past three a
 * palette stops helping recognition. The amber of the theme is deliberately not among them — it
 * means "this is what fires next", never "this kind of trigger".
 */
enum class TriggerFamily { TIME, PLACE, CHANCE }

/** The six tiles of the "add trigger" sheet; how a person picks, not how it is stored. */
enum class TriggerKind(val family: TriggerFamily) {
    DATE_TIME(TriggerFamily.TIME),
    DATE(TriggerFamily.TIME),
    REPEAT_TIME(TriggerFamily.TIME),
    INTERVAL(TriggerFamily.TIME),
    COUNTDOWN(TriggerFamily.TIME),
    PLACE(TriggerFamily.PLACE),
    RANDOM(TriggerFamily.CHANCE),
}

val Trigger.family: TriggerFamily
    get() = when (this) {
        is Trigger.AtDateTime, is Trigger.OnDate, is Trigger.AtTime, is Trigger.Interval -> TriggerFamily.TIME
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
}

/** Whether this trigger is true only at an instant. See [asState]. */
val Trigger.isMoment: Boolean get() = asState() == null

/** The tile that edits an existing trigger (a countdown re-opens as a countdown). */
val Trigger.kind: TriggerKind
    get() = when (this) {
        is Trigger.AtDateTime -> TriggerKind.DATE_TIME
        is Trigger.OnDate -> TriggerKind.DATE
        is Trigger.AtTime -> TriggerKind.REPEAT_TIME
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

/** The other way: a preset keeps the length and never the moment, or it could only be used once. */
fun clearCountdowns(rules: List<TriggerRule>): List<TriggerRule> = rules.map { rule ->
    val trigger = rule.trigger
    if (trigger is Trigger.Countdown && trigger.startedAt != null) rule.copy(trigger = trigger.copy(startedAt = null)) else rule
}
