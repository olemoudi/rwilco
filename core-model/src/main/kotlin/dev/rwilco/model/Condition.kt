@file:UseSerializers(LocalTimeSerializer::class, LocalDateSerializer::class, DayOfWeekSerializer::class)

package dev.rwilco.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
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
     * Between two times of day, on [days] (empty means every day) and on [date] (null means
     * every date those days allow).
     *
     * A window that ends before it starts crosses midnight, and the moment then belongs to the
     * day the window opened: 02:00 on Wednesday is inside "los martes, de 22:00 a 06:00".
     *
     * [date] is never typed by anybody — the "y sólo si" sheet offers hours and days, not dates
     * — and is the one thing a **dated** rule has to keep when it is folded into its siblings as
     * a state ([Trigger.asState]). "El domingo de 20:30 a 22:00, y a la vez en casa" is a state
     * about one Sunday evening; folded as hours alone it became every evening, and a set that
     * could not ring until Sunday rang on the Friday somebody walked through their own front
     * door. Written only when it is set, so nothing already on disk changes shape.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @SerialName("time_window")
    data class TimeWindow(
        val from: LocalTime,
        val to: LocalTime,
        val days: Set<DayOfWeek> = emptySet(),
        // Never written unless it is set, which is never: this one is synthesised at the moment
        // a rule is folded into its siblings and no sheet offers it. So the shape on disk does
        // not move for anything anybody has already typed — the frozen-shape test says so.
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val date: LocalDate? = null,
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
 * that never arrives. A fix sloppier than the circle is the same thing said with a number: a
 * cell tower's kilometre of doubt cannot say which side of a two-hundred-metre line the phone
 * is on, and reading its centre as a confident "no" would silence "y sólo si estoy en casa"
 * for somebody sitting at home. A time window never needs any of it.
 */
fun Condition.holdsAt(at: Instant, zone: ZoneId, where: Fix? = null): Boolean = when (this) {
    is Condition.TimeWindow -> holdsAt(at.atZone(zone).toLocalDateTime())
    is Condition.AtPlace -> {
        if (where == null || where.accuracyM > radiusM) true
        else (distanceMeters(where.lat, where.lng, lat, lng) <= radiusM) == inside
    }
}

/**
 * The same question of a wall-clock moment, which is all a window ever needed: the zone only
 * served to turn the instant into one. Asked directly wherever the moment is already on the
 * clock — a draw being narrowed to its fences, a gate being opened.
 */
fun Condition.TimeWindow.holdsAt(at: LocalDateTime): Boolean {
    val time = at.toLocalTime()
    val crossesMidnight = to <= from
    val inside = if (crossesMidnight) time >= from || time < to else time >= from && time < to
    // On the far side of midnight the moment still belongs to the day the window opened, which
    // is the day both the weekday and the date are asked of.
    val day = if (crossesMidnight && time < to) at.toLocalDate().minusDays(1) else at.toLocalDate()
    return inside && (days.isEmpty() || day.dayOfWeek in days) && (date == null || day == date)
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
