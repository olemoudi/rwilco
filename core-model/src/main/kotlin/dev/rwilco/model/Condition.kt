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

    /**
     * Being somewhere, or not being there: "a las nueve, y sólo si estoy en casa".
     *
     * The state that matches [Trigger.Location]'s event, and the reason it is a condition and
     * not a trigger: arriving is something that happens once and can be waited for, whereas
     * being there is something that is simply true or not when somebody asks. Which is also why
     * it is the one condition nothing can answer in advance — see [holdsAt].
     */
    @Serializable
    @SerialName("at_place")
    data class AtPlace(
        val lat: Double,
        val lng: Double,
        val radiusM: Int,
        val label: String,
        /** True is "and only if I am there"; false is "and only if I am not". */
        val inside: Boolean = true,
    ) : Condition
}

/**
 * Whether the condition was true at [at], for a phone that was at [where].
 *
 * [where] is null when nobody knows — no fix, or one too old to speak for the moment — and a
 * place condition then **holds**. That is the house rule everywhere in this app: erring towards
 * ringing too often is the right way round, because the failure somebody notices is the one
 * that never arrives. A time window never needs it.
 */
fun Condition.holdsAt(at: Instant, zone: ZoneId, where: Fix? = null): Boolean = when (this) {
    is Condition.TimeWindow -> {
        val moment = at.atZone(zone)
        val time = moment.toLocalTime()
        val crossesMidnight = to <= from
        val inside = if (crossesMidnight) time >= from || time < to else time >= from && time < to
        // On the far side of midnight the moment still belongs to the day the window opened.
        val day = if (crossesMidnight && time < to) moment.toLocalDate().minusDays(1).dayOfWeek else moment.dayOfWeek
        inside && (days.isEmpty() || day in days)
    }
    is Condition.AtPlace -> {
        if (where == null) true else (distanceMeters(where.lat, where.lng, lat, lng) <= radiusM) == inside
    }
}

/** Whether every condition on a rule held at [at]. */
fun List<Condition>.allHoldAt(at: Instant, zone: ZoneId, where: Fix? = null): Boolean =
    all { it.holdsAt(at, zone, where) }

/**
 * The conditions nothing can answer before the moment arrives.
 *
 * The scheduler works out when a rule will next fire by walking candidate moments and asking
 * each condition whether it would hold then ([nextFireOfRule]). A time window can answer that
 * about next Tuesday; a place cannot answer it about anywhere. So the scheduler leaves these
 * out and arms the alarm, and they are asked once, for real, when it goes off.
 */
val Condition.knownInAdvance: Boolean get() = this !is Condition.AtPlace

/** The circle a condition is about, for the conflict checks and for the watch to keep an eye on. */
val Condition.place: Condition.AtPlace? get() = this as? Condition.AtPlace
