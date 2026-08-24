@file:UseSerializers(LocalTimeSerializer::class, DayOfWeekSerializer::class)

package dev.rwilco.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * A restriction on a trigger: the moment only counts if every condition attached to it holds.
 * "Al llegar a casa" becomes "al llegar a casa, y sólo si es por la tarde".
 *
 * Conditions are states, not events — they are asked "were you true at that moment?" — which is
 * what makes them safe to AND with anything. The discriminators are frozen like the triggers'.
 */
@Serializable
sealed interface Condition {

    /**
     * Between two times of day, on [days] (empty means every day).
     *
     * A window that ends before it starts crosses midnight, and the moment then belongs to the
     * day the window opened: 02:00 on Wednesday is inside "los martes, de 22:00 a 06:00".
     */
    @Serializable
    @SerialName("time_window")
    data class TimeWindow(
        val from: LocalTime,
        val to: LocalTime,
        val days: Set<DayOfWeek> = emptySet(),
    ) : Condition
}

/** Whether the condition was true at [at]. */
fun Condition.holdsAt(at: Instant, zone: ZoneId): Boolean = when (this) {
    is Condition.TimeWindow -> {
        val moment = at.atZone(zone)
        val time = moment.toLocalTime()
        val crossesMidnight = to <= from
        val inside = if (crossesMidnight) time >= from || time < to else time >= from && time < to
        // On the far side of midnight the moment still belongs to the day the window opened.
        val day = if (crossesMidnight && time < to) moment.toLocalDate().minusDays(1).dayOfWeek else moment.dayOfWeek
        inside && (days.isEmpty() || day in days)
    }
}

/** Whether every condition on a rule held at [at]. */
fun List<Condition>.allHoldAt(at: Instant, zone: ZoneId): Boolean = all { it.holdsAt(at, zone) }
