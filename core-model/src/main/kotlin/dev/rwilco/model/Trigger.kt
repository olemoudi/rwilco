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

    /** Once, at a wall-clock moment. Also what the "countdown" tile produces (now + duration). */
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
    COUNTDOWN(TriggerFamily.TIME),
    PLACE(TriggerFamily.PLACE),
    RANDOM(TriggerFamily.CHANCE),
}

val Trigger.family: TriggerFamily
    get() = when (this) {
        is Trigger.AtDateTime, is Trigger.OnDate, is Trigger.AtTime -> TriggerFamily.TIME
        is Trigger.Location -> TriggerFamily.PLACE
        is Trigger.Countdown -> TriggerFamily.TIME
        is Trigger.Random -> TriggerFamily.CHANCE
    }

/** The tile that edits an existing trigger (a countdown re-opens as a date-time: it is one). */
val Trigger.kind: TriggerKind
    get() = when (this) {
        is Trigger.AtDateTime -> TriggerKind.DATE_TIME
        is Trigger.OnDate -> TriggerKind.DATE
        is Trigger.AtTime -> TriggerKind.REPEAT_TIME
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
