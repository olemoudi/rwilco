@file:UseSerializers(
    LocalDateSerializer::class,
    LocalTimeSerializer::class,
    LocalDateTimeSerializer::class,
    DayOfWeekSerializer::class,
)

package dev.rwilco.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
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
        is Trigger.Random -> TriggerFamily.CHANCE
    }

/** The tile that edits an existing trigger (a countdown re-opens as a date-time: it is one). */
val Trigger.kind: TriggerKind
    get() = when (this) {
        is Trigger.AtDateTime -> TriggerKind.DATE_TIME
        is Trigger.OnDate -> TriggerKind.DATE
        is Trigger.AtTime -> TriggerKind.REPEAT_TIME
        is Trigger.Location -> TriggerKind.PLACE
        is Trigger.Random -> TriggerKind.RANDOM
    }
