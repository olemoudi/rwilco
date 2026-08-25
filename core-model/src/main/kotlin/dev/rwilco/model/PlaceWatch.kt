@file:UseSerializers(InstantSerializer::class)

package dev.rwilco.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Duration
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The app's own watch on the places it is waiting for.
 *
 * The phone's geofences are cheap and always on, but they answer in their own time and with
 * their own idea of where the line is. This is the second opinion: every so often the app reads
 * where the phone is, decides for itself which places it is inside, and — the part that keeps
 * the battery whole — works out how long it can safely look away. Far from every place, and
 * standing still, that is an hour; walking up to a door, two minutes. Nothing here touches the
 * phone: the reading and the alarm are the caller's job, and everything that decides is pure.
 */

/** One reading of where the phone is; [accuracyM] is the radius, in metres, it may be off by. */
@Serializable
data class Fix(val lat: Double, val lng: Double, val accuracyM: Double, val at: Instant)

/** A place some rule is waiting on. [id] is whatever the caller needs to find the rule again. */
data class WatchedPlace(
    val id: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Int,
    val transition: Transition,
    val label: String,
)

/** A place kept in Settings, offered whole — name, pin and radius — when a rule needs one. */
@Serializable
data class SavedPlace(val label: String, val lat: Double, val lng: Double, val radiusM: Int)

/** Great-circle distance in metres. Haversine: good to a fraction of a percent, inside any fix. */
fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private const val EARTH_RADIUS_M = 6_371_000.0

/**
 * Metres per second between two fixes, or null when nothing can be said: no earlier fix, or
 * one too old to describe the present. A step shorter than the two fixes' own uncertainty is
 * zero, not a crawl — the phone did not measurably move. So is the same fix handed back
 * twice (indoors, nothing fresh, the provider repeats what it had): reading that as "unknown"
 * would plan for a moving car and keep the GPS trying every two minutes for nothing.
 */
fun speedBetween(previous: Fix?, current: Fix): Double? {
    if (previous == null) return null
    val elapsed = Duration.between(previous.at, current.at)
    if (elapsed.isNegative || elapsed > PlaceWatchPolicy.SPEED_MEMORY) return null
    if (elapsed.isZero) return 0.0
    val moved = distanceMeters(previous.lat, previous.lng, current.lat, current.lng)
    if (moved <= previous.accuracyM + current.accuracyM) return 0.0
    return moved / (elapsed.toMillis() / 1000.0)
}

/** The numbers that decide how often the phone is asked where it is. */
object PlaceWatchPolicy {
    /** The most often the app will ever look, and only while moving near a line. */
    val MIN_WAIT: Duration = Duration.ofMinutes(2)

    /** The least often: far from everything, or standing still far away. */
    val MAX_WAIT: Duration = Duration.ofMinutes(60)

    /** Standing still near a line — at home with a "when leaving" rule — backs off to this. */
    val STILL_NEAR_MAX: Duration = Duration.ofMinutes(15)

    /** Inside this of a line the check goes to GPS and the cadence to its ceiling. */
    const val NEAR_M = 400.0

    /** Under this the phone is taken to be still. */
    const val STILL_MPS = 0.5

    /** Somebody who has just started moving is at least walking. */
    const val WALK_MPS = 1.5

    /** With no speed to go on, plan for a slow car. */
    const val UNKNOWN_MPS = 8.0

    /** Planning speed over measured: people speed up. */
    const val HEADROOM = 1.5

    /** An earlier fix older than this says nothing about how fast the phone is going now. */
    val SPEED_MEMORY: Duration = Duration.ofMinutes(30)

    /** Longest the still back-off doubles for: 2 · 2⁶ min is already past MAX_WAIT. */
    const val MAX_STILL_DOUBLINGS = 6
}

/** What the next check should be: how long to wait, and whether it is worth waking the GPS. */
data class WatchPlan(val wait: Duration, val precise: Boolean, val gapM: Double, val nearest: WatchedPlace)

/**
 * Distance from the fix to a place's line, whichever side it is on, less the fix's own
 * uncertainty: a fix that could be on either side is a fix at the line.
 */
fun gapToLine(place: WatchedPlace, fix: Fix): Double {
    val distance = distanceMeters(fix.lat, fix.lng, place.lat, place.lng)
    val raw = kotlin.math.abs(distance - place.radiusM)
    return max(0.0, raw - fix.accuracyM)
}

/**
 * How long the phone can look away. The wait is the time it would take to reach the nearest
 * line at a speed with headroom, clamped to the policy's floor and ceiling; standing still
 * doubles it with every quiet check, up to a cap that is lower near a line. Null when there is
 * nothing to watch.
 */
fun planNextCheck(fix: Fix, speedMps: Double?, places: List<WatchedPlace>, stillStreak: Int): WatchPlan? {
    val nearest = places.minByOrNull { gapToLine(it, fix) } ?: return null
    val gap = gapToLine(nearest, fix)
    val near = gap < PlaceWatchPolicy.NEAR_M
    val still = speedMps != null && speedMps <= PlaceWatchPolicy.STILL_MPS
    val planningSpeed = when (speedMps) {
        null -> PlaceWatchPolicy.UNKNOWN_MPS
        else -> max(speedMps * PlaceWatchPolicy.HEADROOM, PlaceWatchPolicy.WALK_MPS)
    }
    var wait = Duration.ofSeconds((gap / planningSpeed).toLong()).clamp(PlaceWatchPolicy.MIN_WAIT, PlaceWatchPolicy.MAX_WAIT)
    if (still) {
        val doublings = min(stillStreak, PlaceWatchPolicy.MAX_STILL_DOUBLINGS)
        val backoff = PlaceWatchPolicy.MIN_WAIT.multipliedBy(1L shl doublings)
        val cap = if (near) PlaceWatchPolicy.STILL_NEAR_MAX else PlaceWatchPolicy.MAX_WAIT
        wait = maxOf(wait, backoff).clamp(PlaceWatchPolicy.MIN_WAIT, cap)
    }
    // GPS is for a line that is close and a phone that is (or may be) moving towards it.
    return WatchPlan(wait = wait, precise = near && !still, gapM = gap, nearest = nearest)
}

private fun Duration.clamp(floor: Duration, ceiling: Duration): Duration = when {
    this < floor -> floor
    this > ceiling -> ceiling
    else -> this
}

/**
 * Whether the phone counts as inside a place after this fix, given whether it was before.
 * With no history, the plain answer. Otherwise with hysteresis: getting in takes a fix whose
 * centre is inside and that is not so sloppy it could be anywhere; getting out takes a fix
 * clearly beyond the line. A fix wobbling on the line changes nothing.
 */
fun insideAfter(wasInside: Boolean?, place: WatchedPlace, fix: Fix): Boolean {
    val distance = distanceMeters(fix.lat, fix.lng, place.lat, place.lng)
    return when (wasInside) {
        null -> distance <= place.radiusM
        false -> distance <= place.radiusM && fix.accuracyM <= place.radiusM
        true -> distance <= place.radiusM + fix.accuracyM
    }
}

/**
 * What the watch remembers between checks. Persisted as JSON; every field has a default.
 * [inside] only has entries for places that have been judged once: a place with no entry is
 * baselined by the next fix without an event, which is how a reminder written while standing
 * at home does not ring for "arriving home".
 */
@Serializable
data class PlaceWatchState(
    val lastFix: Fix? = null,
    val inside: Map<String, Boolean> = emptyMap(),
    /** Consecutive checks that found the phone still. */
    val stillStreak: Int = 0,
    val nextCheckAt: Instant? = null,
    val lastGapM: Double? = null,
    val nearestLabel: String? = null,
    /** Whether the next check should use GPS; decided by the last plan. */
    val precise: Boolean = false,
)

/** The phone crossed a line some rule was waiting on. */
data class PlaceEvent(val placeId: String, val transition: Transition)

data class WatchStep(val state: PlaceWatchState, val events: List<PlaceEvent>, val plan: WatchPlan?)

/**
 * One check: judge every place against the fix, report the crossings that match what their
 * rules wait for, and plan the next look from [now] (not from the fix, which may be an old
 * one the phone had lying around).
 */
fun stepPlaceWatch(state: PlaceWatchState, fix: Fix, places: List<WatchedPlace>, now: Instant): WatchStep {
    val speed = speedBetween(state.lastFix, fix)
    val inside = places.associate { place -> place.id to insideAfter(state.inside[place.id], place, fix) }
    val events = places.mapNotNull { place ->
        val before = state.inside[place.id] ?: return@mapNotNull null
        val after = inside.getValue(place.id)
        when {
            !before && after && place.transition == Transition.ENTER -> PlaceEvent(place.id, Transition.ENTER)
            before && !after && place.transition == Transition.EXIT -> PlaceEvent(place.id, Transition.EXIT)
            else -> null
        }
    }
    val still = speed != null && speed <= PlaceWatchPolicy.STILL_MPS
    val streak = if (still) state.stillStreak + 1 else 0
    val plan = planNextCheck(fix, speed, places, streak)
    val next = PlaceWatchState(
        lastFix = fix,
        inside = inside,
        stillStreak = streak,
        nextCheckAt = plan?.let { now + it.wait },
        lastGapM = plan?.gapM,
        nearestLabel = plan?.nearest?.label,
        precise = plan?.precise ?: false,
    )
    return WatchStep(next, events, plan)
}
