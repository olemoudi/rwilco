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
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The app's own watch on the places it is waiting for.
 *
 * The phone's geofences are cheap and always on, but they answer in their own time and with
 * their own idea of where the line is. This is the second opinion: every so often the app reads
 * where the phone is, decides for itself which places it is inside, and — the part that keeps
 * the battery whole — works out how long it can safely look away. Walking up to a door, that is
 * two minutes; standing still near one, a quarter of an hour; at home with a place across town,
 * an hour; at home with a place three provinces away, most of the afternoon, because no road
 * gets anybody there sooner. Nothing here touches the phone: the reading, the sensor and the
 * alarm are the caller's job, and everything that decides is pure.
 */

/** One reading of where the phone is; [accuracyM] is the radius, in metres, it may be off by. */
@Serializable
data class Fix(val lat: Double, val lng: Double, val accuracyM: Double, val at: Instant)

/**
 * A place some rule is waiting on. [id] is whatever the caller needs to find the rule again.
 *
 * [fires] is false for a place that is only ever *asked about* — the circle behind a
 * [Condition.AtPlace], which has to be tracked so the answer is ready when a reminder rings,
 * but which is a state and not an event and so must never ring anything itself. It costs the
 * same to watch as any other place, which is the honest price of asking "and only if I am
 * home": the app has to know whether you are.
 */
data class WatchedPlace(
    val id: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Int,
    val transition: Transition,
    val label: String,
    val fires: Boolean = true,
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

    /** The least often a place within reach is watched: far from everything, or standing still. */
    val MAX_WAIT: Duration = Duration.ofMinutes(60)

    /** Standing still near a line — at home with a "when leaving" rule — backs off to this. */
    val STILL_NEAR_MAX: Duration = Duration.ofMinutes(15)

    /**
     * Already inside a place that is waiting for an arrival: the only thing that can happen is
     * leaving, and how soon that is noticed does not matter. Somebody who steps out for the bin
     * and back inside half an hour has not arrived anywhere. So this is the cheapest watch in
     * the app, and the one a phone sitting at home all evening spends the night in.
     */
    val INSIDE_MIN_WAIT: Duration = Duration.ofMinutes(30)

    /**
     * Inside a place that is waiting for a *leaving*: where that watch starts, and the fastest
     * it is ever allowed to go. See [leavingWait] for why it is its own pair of numbers.
     */
    val LEAVING_MAX_WAIT: Duration = Duration.ofMinutes(30)
    val LEAVING_MIN_WAIT: Duration = Duration.ofMinutes(5)

    /** Inside this of a line the check goes to GPS and the cadence to its ceiling. */
    const val NEAR_M = 400.0

    /** Under this the phone is taken to be still. */
    const val STILL_MPS = 0.5

    /** Somebody who has just started moving is at least walking. */
    const val WALK_MPS = 1.5

    /** With no speed to go on, plan for a slow car. */
    const val UNKNOWN_MPS = 8.0

    /**
     * And do not look away for long either: the first look of a journey cannot know it is a
     * journey, and an hour is ninety motorway kilometres. One extra look buys the speed.
     * Distance overrules it — ninety kilometres is no argument about a place three hundred
     * away — which is what [reachCeiling] is for.
     */
    val UNKNOWN_MAX_WAIT: Duration = Duration.ofMinutes(15)

    /** Planning speed over measured: people speed up. */
    const val HEADROOM = 1.5

    /**
     * The fastest anybody covers ground, averaged over a whole trip: 120 km/h. Not a top speed —
     * a speedometer says more — but the bound that holds over the hours [reachCeiling] plans in.
     */
    const val HIGHWAY_MPS = 33.4

    /** Past this a flight is on the table, and no road speed bounds anything. */
    const val FAR_M = 500_000.0

    /**
     * An earlier fix older than this says nothing about how fast the phone is going now.
     * Longer than the longest wait on purpose: the average speed over an hour's look-away is
     * exactly the speed the next plan needs, and forgetting it would make every long wait
     * start over blind.
     */
    val SPEED_MEMORY: Duration = Duration.ofMinutes(90)

    /** Longest the still back-off doubles for: 2 · 2⁶ min is already past MAX_WAIT. */
    const val MAX_STILL_DOUBLINGS = 6

    /**
     * How long before a clock rule's moment the circles it only *asks about* are watched.
     *
     * "A las nueve, y sólo si estoy en casa" needs to know where the phone is at nine, and at
     * no other time: the answer at four in the afternoon is worth nothing and costs a fix.
     * So a circle that is only ever asked about — a place condition on a clock rule, or a
     * place folded into one under "a la vez" — is left alone until this long before the
     * moment, when one look is taken and the ordinary cadence carries it to the alarm. Five
     * minutes is long enough for a first fix to land and short enough that what it says
     * still holds when the alarm goes off.
     */
    val ASK_LEAD: Duration = Duration.ofMinutes(5)

    /**
     * How long before a gating window opens the circle behind it starts being watched.
     *
     * A place under "a la vez" cannot ring outside its set's hours, so it is left alone until
     * they are near — but *near* and not *exactly*. Two reasons, and they are the same reason
     * twice. A watch that began at the stroke of the window would have no idea which side of
     * the line the phone was on, so its first fix would be a baseline and not an arrival, and
     * somebody who walked in at one minute past would not be rung. And a circle judged for the
     * first time at the very moment it can ring has had no chance to settle its cadence: the
     * ordinary approach — hourly far away, minutes near the line — needs a run-up to be worth
     * anything.
     *
     * Two hours is that run-up. It is long enough for the cadence to find the phone and for an
     * arrival to be an arrival, and short enough that a window once a day costs two hours of
     * adaptive looking rather than twenty-four — which is the whole point of gating at all.
     */
    val WINDOW_LEAD: Duration = Duration.ofMinutes(120)

    /** Above this much battery left, none of the below applies. See [batteryFloor]. */
    const val SPARING_FROM = 0.50

    /** At this much left and under, the hourly look and nothing faster, whatever else says. */
    const val SPARING_FLOOR = 0.25

    /**
     * More polls than this inside [BUSY_WINDOW] and something is wrong. Three fifths of the
     * arithmetic ceiling — MIN_WAIT is two minutes, so thirty an hour is all the watch can
     * physically do — which puts it above what an ordinary approach costs and below what only
     * a fault can reach: getting here takes over half the hour at the fastest cadence there is,
     * which is either a very long walk up to a place or the app getting something wrong.
     * See [WatchLog.busyNotice].
     */
    const val BUSY_POLLS = 18

    val BUSY_WINDOW: Duration = Duration.ofHours(1)
}

/**
 * The soonest a battery this low will allow a look — a floor under every other answer, never a
 * cap on one.
 *
 * Above [PlaceWatchPolicy.SPARING_FROM] there is nothing to discuss and this is the ordinary
 * [PlaceWatchPolicy.MIN_WAIT]. Below it the floor climbs, and climbs *geometrically*, so that
 * the half of the fall nobody worries about costs almost nothing and the last quarter costs
 * everything: at 37% left the two-minute floor is already eleven minutes, and by
 * [PlaceWatchPolicy.SPARING_FLOOR] it is the hour. That endpoint is not a number picked to
 * match; the span it climbs is exactly MAX_WAIT / MIN_WAIT, which is to say the fastest cadence
 * this app has becomes the slowest one it has, and there is nothing below that to fall to.
 *
 * A floor and not a cap, because the alternative eats itself: a place three hundred kilometres
 * off has already bought two and a half hours of sleep on the arithmetic of how fast anybody
 * drives, and a low battery is no reason to go and look sooner than that.
 *
 * [charge] is 0..1, or null when there is no reason to hold back at all — the phone is on a
 * charger, or it will not say.
 */
fun batteryFloor(charge: Double?): Duration {
    if (charge == null || charge >= PlaceWatchPolicy.SPARING_FROM) return PlaceWatchPolicy.MIN_WAIT
    if (charge <= PlaceWatchPolicy.SPARING_FLOOR) return PlaceWatchPolicy.MAX_WAIT
    val fallen = (PlaceWatchPolicy.SPARING_FROM - charge) / (PlaceWatchPolicy.SPARING_FROM - PlaceWatchPolicy.SPARING_FLOOR)
    val span = PlaceWatchPolicy.MAX_WAIT.toMillis().toDouble() / PlaceWatchPolicy.MIN_WAIT.toMillis()
    return Duration.ofMillis((PlaceWatchPolicy.MIN_WAIT.toMillis() * span.pow(fallen)).toLong())
}

/**
 * What the app knows about how the phone is moving, at the moment it plans the next look.
 *
 * Two witnesses, and neither is enough alone. [speedMps] and [movedM] come from comparing two
 * fixes, which is the accurate one and the expensive one — and which says nothing at all on the
 * first look of a session. [sensed] is the phone's own significant-motion sensor, which costs
 * nothing and runs while the app is asleep, but which only ever answers one question, and only
 * usefully in one direction: it firing means the phone went somewhere, and it *not* firing is
 * a hint and no more — a phone lying flat on a train table is not moving, as far as it knows.
 * So the rule everywhere below is the same: [sensed] true can shorten the watch, [sensed] false
 * can only lengthen it when a pair of fixes says the same thing.
 */
data class Movement(
    /** Metres per second between the last two fixes; null when nothing can be said. */
    val speedMps: Double? = null,
    /** Metres between the last two fixes, less their own doubt; null with no earlier fix. */
    val movedM: Double? = null,
    /** The significant-motion sensor since the last look; null when it was not listening. */
    val sensed: Boolean? = null,
    /** Consecutive checks that found the phone still. */
    val stillStreak: Int = 0,
) {
    /** Not moving, as far as anything can tell: the fixes agree and the sensor did not object. */
    val still: Boolean get() = sensed != true && speedMps != null && speedMps <= PlaceWatchPolicy.STILL_MPS

    /** Both witnesses agree the phone has not moved. Worth more than either on its own. */
    val settled: Boolean get() = sensed == false && speedMps != null && speedMps <= PlaceWatchPolicy.STILL_MPS

    /** Moving, on the evidence of two fixes. The sensor's word is not enough to wake the GPS. */
    val movingByFix: Boolean get() = speedMps != null && speedMps > PlaceWatchPolicy.STILL_MPS
}

/**
 * How soon a phone that has just *stirred* may be looked at.
 *
 * The one thing the motion sensor is unambiguously good for is the case the rest of this file
 * is worst at: resting inside a place with a "when I leave" rule, where the watch has settled
 * on half an hour precisely because nothing was happening — and then something does. The sensor
 * fires the moment somebody actually walks out, and the look is pulled forward to here rather
 * than waiting out the half hour it had planned.
 *
 * The number is [PlaceWatchPolicy.LEAVING_MIN_WAIT], and deliberately the same one: the floor
 * on being stirred is the floor that case already had, so a phone paced past all evening can
 * never cost more than that case was already allowed to cost. A low battery raises it like it
 * raises everything else, and at the bottom this stops buying anything at all, which is the
 * right answer there.
 */
fun stirredWait(charge: Double?): Duration = maxOf(PlaceWatchPolicy.LEAVING_MIN_WAIT, batteryFloor(charge))

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
 * The longest a distance on its own lets the phone look away: the time to cover the gap at
 * [PlaceWatchPolicy.HIGHWAY_MPS]. A place two hours down the motorway cannot be arrived at in
 * twenty minutes, so watching it every twenty is twenty minutes wasted, and this is what lets a
 * phone at home with an errand in the next province sleep for hours rather than for the hour
 * [PlaceWatchPolicy.MAX_WAIT] would allow. Past [PlaceWatchPolicy.FAR_M] a flight is on the
 * table and no road speed bounds anything, so it falls back to that plain hour — which next to
 * any flight, door to door, is still short.
 *
 * It only ever grants sleep. Nothing here shortens a wait: a nearby place returning a few
 * seconds is not an instruction to look every few seconds, it is silence on the question.
 */
fun reachCeiling(gapM: Double): Duration =
    if (gapM > PlaceWatchPolicy.FAR_M) PlaceWatchPolicy.MAX_WAIT
    else Duration.ofSeconds((gapM / PlaceWatchPolicy.HIGHWAY_MPS).toLong())

/**
 * How long the phone can look away: the soonest of what each place asks for. A place's own wait
 * is the time it would take to reach its line at a speed with headroom, clamped to the policy's
 * floor and a ceiling that distance can raise; standing still doubles it with every quiet check,
 * up to a cap that is lower near a line unless the motion sensor agrees nothing has moved. A
 * place already inside asks for one of the two cheap watches — [PlaceWatchPolicy.INSIDE_MIN_WAIT]
 * waiting for an arrival, [leavingWait] waiting for a leaving. Null when there is nothing to
 * watch.
 *
 * Then [charge] has the last word, because a watch that runs the battery down to nothing stops
 * being a watch: a low one raises the floor under whatever came out of all of the above
 * ([batteryFloor]), and at the bottom of it takes the GPS away as well — an hourly look is not
 * the last few hundred metres of an approach, which is the only thing the GPS was ever for.
 */
fun planNextCheck(
    fix: Fix,
    movement: Movement,
    places: List<WatchedPlace>,
    inside: Map<String, Boolean> = emptyMap(),
    charge: Double? = null,
): WatchPlan? = places
    .map { place -> planFor(place, fix, movement, inside[place.id]) }
    // The soonest look any one place asks for; on a tie, the one that is closest. A phone
    // sitting at home with an errand across town is planned by the errand, not by the sofa.
    .minWithOrNull(compareBy({ it.wait }, { it.gapM }))
    ?.let { plan ->
        val floor = batteryFloor(charge)
        plan.copy(wait = maxOf(plan.wait, floor), precise = plan.precise && floor < PlaceWatchPolicy.MAX_WAIT)
    }

private fun planFor(place: WatchedPlace, fix: Fix, movement: Movement, inside: Boolean?): WatchPlan {
    val gap = gapToLine(place, fix)
    val near = gap < PlaceWatchPolicy.NEAR_M
    val planningSpeed = when (val speed = movement.speedMps) {
        null -> PlaceWatchPolicy.UNKNOWN_MPS
        else -> max(speed * PlaceWatchPolicy.HEADROOM, PlaceWatchPolicy.WALK_MPS)
    }
    val blind = if (movement.speedMps == null) PlaceWatchPolicy.UNKNOWN_MAX_WAIT else PlaceWatchPolicy.MAX_WAIT
    val ceiling = maxOf(blind, reachCeiling(gap))
    var wait = Duration.ofSeconds((gap / planningSpeed).toLong()).clamp(PlaceWatchPolicy.MIN_WAIT, ceiling)
    if (movement.still) {
        val doublings = min(movement.stillStreak, PlaceWatchPolicy.MAX_STILL_DOUBLINGS)
        val backoff = PlaceWatchPolicy.MIN_WAIT.multipliedBy(1L shl doublings)
        // Still and near a line is a phone about to go through it, so the back-off is held
        // short — unless the sensor felt nothing either, which is a phone on a table.
        val cap = if (near && !movement.settled) PlaceWatchPolicy.STILL_NEAR_MAX else ceiling
        wait = maxOf(wait, backoff).clamp(PlaceWatchPolicy.MIN_WAIT, cap)
    }
    if (inside == true) {
        // Inside, only one of the two crossings is still ahead, and neither is urgent. Never
        // GPS: being close to the line is what being inside means, and that is not a reason.
        val floor = if (place.transition == Transition.ENTER) PlaceWatchPolicy.INSIDE_MIN_WAIT else leavingWait(place, movement)
        return WatchPlan(maxOf(wait, floor), precise = false, gapM = gap, nearest = place)
    }
    // GPS is for a line that is close and a phone KNOWN to be moving. Not "may be": the first
    // look of a session at home would wake it for a phone on a bedside table.
    return WatchPlan(wait = wait, precise = near && movement.movingByFix, gapM = gap, nearest = place)
}

/**
 * Inside a place with a "when I leave" rule: the least often that watch may look.
 *
 * This is the case the plain answer gets worst. Standing inside a place is standing next to its
 * line, and "time to the line at your speed" therefore asks for the fastest cadence in the app,
 * all evening, for a door nobody is walking through — with the GPS on, if pacing the kitchen
 * reads as movement. So the watch starts at half an hour instead, and buys its way down only
 * with evidence: how much of the place the phone actually crossed since the last look, as a
 * fraction of its radius — the tolerance the rule was written with — takes that fraction off
 * the half hour, and sixty per cent of the way across takes sixty per cent off it. The floor is
 * five minutes, which is the point: a phone circling inside its own front garden all night
 * never costs more than twelve looks an hour's worth of the cheapest fix there is.
 *
 * The wait this returns is a floor under the ordinary plan, never a cap on it: deep inside a
 * place kilometres wide, "time to the line" is the better answer and it wins.
 */
private fun leavingWait(place: WatchedPlace, movement: Movement): Duration {
    val crossed = movement.movedM?.let { (it / place.radiusM).coerceIn(0.0, 1.0) } ?: 0.0
    val scaled = PlaceWatchPolicy.LEAVING_MAX_WAIT.toMillis() * (1.0 - crossed)
    return Duration.ofMillis(scaled.toLong())
        .clamp(PlaceWatchPolicy.LEAVING_MIN_WAIT, PlaceWatchPolicy.LEAVING_MAX_WAIT)
}

private fun Duration.clamp(floor: Duration, ceiling: Duration): Duration = when {
    this < floor -> floor
    this > ceiling -> ceiling
    else -> this
}

/**
 * Whether the phone counts as inside a place after this fix, given whether it was before.
 *
 * With hysteresis: getting in takes a fix whose centre is inside and that is not so sloppy it
 * could be anywhere; getting out takes a fix clearly beyond the line. A fix wobbling on the
 * line changes nothing.
 *
 * With no history, the side that rings nothing. A first fix is often the worst one — the
 * cached cell fix a cold provider hands back — and the plain answer read off its centre seeds
 * the memory with a guess that the next good fix then "corrects" with an event: a phone that
 * never left home told it has just arrived, which is the one thing the baseline exists to
 * prevent. A place waiting for an arrival is therefore taken to be inside when the fix could
 * be — the real arrival is still caught, after the watch has seen the phone leave — and a
 * place waiting for a leaving is taken to be outside unless the fix is clearly in. With a
 * good fix the two read as the plain answer; only doubt is resolved towards silence.
 */
fun insideAfter(wasInside: Boolean?, place: WatchedPlace, fix: Fix): Boolean {
    val distance = distanceMeters(fix.lat, fix.lng, place.lat, place.lng)
    return when (wasInside) {
        null -> if (place.transition == Transition.ENTER) distance <= place.radiusM + fix.accuracyM else distance + fix.accuracyM <= place.radiusM
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
    /** Consecutive checks that got no fix worth having. See [blindRetry]. */
    val blindStreak: Int = 0,
)

/**
 * How long to wait after a check that got nothing: no fix at all, or one too old to speak for
 * now. Ten minutes, doubling, up to the ordinary ceiling.
 *
 * A phone with location switched off answers nothing, and will still be answering nothing in
 * ten minutes and in ten after that — so a flat retry is a wake-up every ten minutes, all day,
 * for a question whose answer cannot change until somebody opens Settings. What can change is
 * a provider that was merely cold, and that is what the first few short retries are for.
 */
fun blindRetry(streak: Int, first: Duration): Duration {
    val doublings = min(max(streak, 0), PlaceWatchPolicy.MAX_STILL_DOUBLINGS)
    val backoff = first.multipliedBy(1L shl doublings)
    return if (backoff > PlaceWatchPolicy.MAX_WAIT) PlaceWatchPolicy.MAX_WAIT else backoff
}

/** The phone crossed a line some rule was waiting on. */
data class PlaceEvent(val placeId: String, val transition: Transition)

/** [movement] is what the plan was decided from, kept so the log can say so (`WatchLog.kt`). */
data class WatchStep(
    val state: PlaceWatchState,
    val events: List<PlaceEvent>,
    val plan: WatchPlan?,
    val movement: Movement = Movement(),
)

/**
 * One check: judge every place against the fix, report the crossings that match what their
 * rules wait for, and plan the next look from [now] (not from the fix, which may be an old
 * one the phone had lying around). [sensed] is the motion sensor's word since the last check,
 * null when it had none; [charge] is how much battery is left to spend on the next one.
 *
 * [listening] is the other half of the watch, and it is free. A circle whose reminder cannot
 * ring for weeks asks for no fix of its own — that is the whole point of gating it — but a fix
 * is being read anyway, for somebody else, and judging one more circle against it costs
 * arithmetic. So these are judged into [PlaceWatchState.inside] and nowhere else: they cast no
 * vote on when to look again ([planNextCheck] never sees them, so no gated circle can pull the
 * cadence towards itself or wake the GPS) and they report no crossing, because a reminder that
 * cannot ring must not. What they get is the thing that costs a radio to buy and nothing to
 * keep: an up-to-date answer to which side of the line the phone is on, so that when the gate
 * opens the first crossing is a crossing rather than a baseline.
 */
fun stepPlaceWatch(
    state: PlaceWatchState,
    fix: Fix,
    places: List<WatchedPlace>,
    now: Instant,
    sensed: Boolean? = null,
    charge: Double? = null,
    listening: List<WatchedPlace> = emptyList(),
): WatchStep {
    val movement = movementSince(state.lastFix, fix, sensed, state.stillStreak)
    val inside = (places + listening).associate { place -> place.id to insideAfter(state.inside[place.id], place, fix) }
    val events = places.mapNotNull { place ->
        if (!place.fires) return@mapNotNull null
        val before = state.inside[place.id] ?: return@mapNotNull null
        val after = inside.getValue(place.id)
        when {
            !before && after && place.transition == Transition.ENTER -> PlaceEvent(place.id, Transition.ENTER)
            before && !after && place.transition == Transition.EXIT -> PlaceEvent(place.id, Transition.EXIT)
            else -> null
        }
    }
    val plan = planNextCheck(fix, movement, places, inside, charge)
    val next = PlaceWatchState(
        lastFix = fix,
        inside = inside,
        stillStreak = if (movement.still) state.stillStreak + 1 else 0,
        nextCheckAt = plan?.let { now + it.wait },
        lastGapM = plan?.gapM,
        nearestLabel = plan?.nearest?.label,
        precise = plan?.precise ?: false,
    )
    return WatchStep(next, events, plan, movement)
}

/**
 * The look that need not be taken, and the cheapest thing the watch does — or null when the
 * look must be taken after all.
 *
 * A fix costs radios. The motion sensor costs nothing and has been listening the whole time the
 * phone was asleep. When it says the phone has not moved AND the last check's pair of fixes said
 * the same — the corroboration matters, because a phone flat on a train table feels nothing —
 * then the fix about to be taken is one already in hand, and the step is run against the stored
 * one instead. That is exactly what a repeated reading would have produced: [insideAfter] is
 * idempotent, so a rested step has no crossings to miss and cannot invent one.
 *
 * The bound is that fix's own age. Everything downstream is measured from it — the speed the
 * next plan is drawn at, whether a geofence's crossing is news ([crossingIsNews]) — so a rest
 * allowed to outlive [PlaceWatchPolicy.SPEED_MEMORY] would have bought quiet with blindness.
 */
fun stepWithoutLooking(
    state: PlaceWatchState,
    places: List<WatchedPlace>,
    now: Instant,
    sensed: Boolean?,
    charge: Double? = null,
    listening: List<WatchedPlace> = emptyList(),
): WatchStep? {
    if (sensed != false || state.stillStreak == 0) return null
    val fix = state.lastFix ?: return null
    val step = stepPlaceWatch(state, fix, places, now, sensed = false, charge = charge, listening = listening)
    val wait = step.plan?.wait ?: return null
    if (Duration.between(fix.at, now + wait) > PlaceWatchPolicy.SPEED_MEMORY) return null
    return step
}

/**
 * What two fixes and the sensor say about the phone's motion. The metres are the step between
 * the fixes less their own doubt, for the same reason [speedBetween] uses that bound: a step
 * smaller than the noise is not a step, and reading it as one is how a phone on a table talks
 * the watch into looking more often.
 */
fun movementSince(previous: Fix?, fix: Fix, sensed: Boolean?, stillStreak: Int): Movement {
    val moved = previous?.let {
        max(0.0, distanceMeters(it.lat, it.lng, fix.lat, fix.lng) - it.accuracyM - fix.accuracyM)
    }
    return Movement(speedBetween(previous, fix), moved, sensed, stillStreak)
}

/**
 * Whether a crossing the phone's geofences report is news to the app.
 *
 * Two eyes on every place, and only one of them knows where the phone was a moment ago. An
 * arrival reported while the app's own last fix still has the phone inside is not an arrival —
 * it is Play Services re-reading a line the phone never left, which is how a place reminder
 * rings at somebody sitting at home. Symmetrically for a leaving.
 *
 * Anything the app cannot vouch for is news: no fix, a fix too old to speak for now, or a place
 * never judged. A crossing that reaches here was seen by the system, and ringing once too often
 * beats the reminder that never arrives.
 *
 * Except [strict], which is the reading for a place that has already rung: it rings *again*
 * only for a crossing the app has seen the other side of — an arrival after the phone was
 * seen outside, a leaving after it was seen inside — and what it cannot vouch for is then not
 * news. The first ring is owed the benefit of the doubt; the second is owed a leaving.
 */
fun crossingIsNews(
    state: PlaceWatchState,
    placeId: String,
    transition: Transition,
    now: Instant,
    staleAfter: Duration = PlaceWatchPolicy.SPEED_MEMORY,
    strict: Boolean = false,
): Boolean {
    if (strict) return state.inside[placeId] == (transition == Transition.EXIT)
    val fix = state.lastFix ?: return true
    if (Duration.between(fix.at, now) > staleAfter) return true
    val inside = state.inside[placeId] ?: return true
    return inside != (transition == Transition.ENTER)
}

/** The same crossing written down, so the eye that saw it second knows it is old news. */
fun PlaceWatchState.remembering(placeId: String, transition: Transition): PlaceWatchState =
    copy(inside = inside + (placeId to (transition == Transition.ENTER)))
