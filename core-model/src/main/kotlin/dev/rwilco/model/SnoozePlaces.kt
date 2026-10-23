package dev.rwilco.model

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/*
 * "Posponer hasta llegar a un lugar."
 *
 * The answers to a ring used to be clock-shaped only, and on a phone whose reminders are mostly
 * places the honest answer to "comprar filtros" ringing on the metro is "cuando llegue a casa".
 * Two offers of a circle: the saved place this person's reminders use most, as a doorway in
 * — and "al salir de aquí", a circle drawn around wherever the phone is right now, thrown away
 * once it has rung. The reminder carries the circle in [Reminder.snoozedToPlace]; the watch and
 * the geofences treat it as the reminder's only circle until it rings.
 *
 * And a third that is not a circle at all (0.196.0): **"la próxima vez que llegue a Mercadona"**,
 * the reminder's own doorway left to ring it again ([SnoozePlace.NextTime]).
 */

/** One of the ways a ring can be put off to a place. */
sealed interface SnoozePlace {
    /** "Al llegar a [place]". */
    data class Arrive(val place: SavedPlace) : SnoozePlace

    /**
     * "Al salir de aquí": a circle around the phone's own position, made at the moment of the
     * tap. [radiusM] is how big that circle would be *now* ([hereRadiusM] of the watch's last
     * position), so the button can say it; what is actually written comes from the fresh fix
     * the tap goes and asks for, which is usually the same or tighter.
     */
    data class LeaveHere(val radiusM: Int = SNOOZE_HERE_MIN_RADIUS_M) : SnoozePlace

    /**
     * "La próxima vez que llegue a Mercadona": no circle of its own, but the reminder's, left to
     * ring it the way it would have — with its hours, its days, its fences and every other rule
     * it has ([Reminder.snoozedToRules]). [door] is the one doorway the button can name, and null
     * when more than one thing can ring it next ("cuando vuelva a tocar"). See [nextTime].
     */
    data class NextTime(val door: Trigger.Location?) : SnoozePlace
}

/**
 * The smallest "aquí" there is.
 *
 * A hundred metres rather than nothing, because the circle is also the guard: a wifi position
 * indoors can jump a hundred and eighty metres with the phone flat on a table (it is in the
 * owner's own watch log), and a circle smaller than that noise is a reminder that comes back
 * for standing still.
 */
const val SNOOZE_HERE_MIN_RADIUS_M = 100

/**
 * The circle "aquí" means: **as small as the fix can defend**, which is twice its own doubt,
 * and never under [SNOOZE_HERE_MIN_RADIUS_M].
 *
 * It was a flat 150 m (0.79.0 and before), which is a promise about a distance nobody could
 * see and one the phone often could not keep the other way round either. Leaving a circle takes
 * the radius *plus* the fix's own accuracy ([insideAfter]'s `true` branch), so a flat 150 with a
 * ±50 m position meant two hundred metres of walking — and a reminder put off "al salir de
 * aquí" in a park next door to the house never came back. Twice the doubt is the same guard
 * expressed in the units it is actually about: with a ±15 m fix under the open sky, "aquí" is
 * a hundred metres and the walk home rings it; with a ±70 m one indoors it is a hundred and
 * forty, and nothing is claimed that the fix cannot carry. The ceiling comes for free: nothing
 * sloppier than [HERE_FIX_MAX_ACCURACY_M] may draw "aquí" at all, so the widest it goes is 300.
 */
fun hereRadiusM(accuracyM: Double): Int =
    maxOf(SNOOZE_HERE_MIN_RADIUS_M, kotlin.math.round(accuracyM * 2).toInt())

/** How far apart two circles may be and still be the same place; four decimals, as the suggestions count. */
private const val SAME_PLACE_M = 11.0

/** A position older than this, or sloppier than the circle it would draw, cannot say where "aquí" is. */
val HERE_FIX_MAX_AGE: Duration = Duration.ofMinutes(2)
const val HERE_FIX_MAX_ACCURACY_M = 150.0

/** The doorway in: "cuando llegue a casa", whichever side the phone is on when it is said. */
fun SnoozePlace.Arrive.circle(): Trigger.Location =
    Trigger.Location(place.lat, place.lng, place.radiusM, Presence.INSIDE, place.label, onCrossing = true, placeId = place.id.ifBlank { null })

/** The doorway out of a circle drawn around [fix]; [label] is the word for "here" in the person's language. */
fun hereCircle(fix: Fix, label: String): Trigger.Location =
    Trigger.Location(fix.lat, fix.lng, hereRadiusM(fix.accuracyM), Presence.OUTSIDE, label, onCrossing = true)

/** Whether a position is fresh and tight enough to draw "aquí" around. */
fun Fix.speaksForHere(now: Instant): Boolean =
    Duration.between(at, now).abs() <= HERE_FIX_MAX_AGE && accuracyM <= HERE_FIX_MAX_ACCURACY_M

/**
 * How old the place watch's own last position may be and still be worth *drawing* on the map.
 *
 * Wider than [HERE_FIX_MAX_AGE] on purpose, and the difference is what the two are for: two
 * minutes is what it takes to draw a circle somebody will be woken by, and this is a dot that
 * says "roughly here" while the phone is asked again. The watch looks every fifteen minutes at
 * its most awake ([PlaceWatchState]'s own cadence), so anything tighter would mean no dot at
 * all on a phone whose platform providers are slow to answer — which is the state the map was
 * in when this was written.
 */
val MAP_FIX_MAX_AGE: Duration = Duration.ofMinutes(15)

/**
 * Whether a position is worth drawing as the blue dot when nothing fresher has answered. Same
 * accuracy floor as "aquí": a dot drawn from a fix vaguer than that is not where anybody is.
 */
fun Fix.worthDrawing(now: Instant): Boolean =
    Duration.between(at, now).abs() <= MAP_FIX_MAX_AGE && accuracyM <= HERE_FIX_MAX_ACCURACY_M

/**
 * The saved place this person's reminders name most — in their rules or their "y sólo si"
 * fences, open and done alike — and the first one saved when none of them names any. Null with
 * nothing saved: there is no place to offer.
 */
fun mostUsedPlace(saved: List<SavedPlace>, reminders: List<Reminder>): SavedPlace? {
    if (saved.isEmpty()) return null
    val circles = reminders.flatMap { reminder ->
        reminder.rules.flatMap { rule ->
            val own = (rule.trigger as? Trigger.Location)?.let { listOf(it.lat to it.lng) }.orEmpty()
            own + rule.conditions.mapNotNull { (it as? Condition.AtPlace)?.let { place -> place.lat to place.lng } }
        }
    }
    return saved.maxByOrNull { place ->
        circles.count { (lat, lng) -> distanceMeters(lat, lng, place.lat, place.lng) <= SAME_PLACE_M }
    }
}

/**
 * Whether "al llegar a [place]" is worth offering: not when the watch knows the phone is
 * already inside it. A doorway in from inside is a leaving and a coming back, which is not what
 * anybody standing at home means by "when I get home".
 */
fun arriveOffered(place: SavedPlace, watch: PlaceWatchState): Boolean {
    // A doorway's first judgement leans towards the side it waits for, so a wide fix from the
    // office can read as "inside home" on an "al llegar" circle; only a state's memory — a
    // rule read as one, or a fence — is a word about where the phone is.
    val key = GeofenceIds.circleKey(place.lat, place.lng, place.radiusM)
    val states = watch.inside.entries.filter { it.key.contains(key) && !it.key.endsWith("!") }
    val inside = states.firstOrNull { it.key.endsWith(",I") }?.value ?: states.firstOrNull()?.value
    return inside != true
}

/**
 * The offers, in the order they are shown: the doorway into the most-used place (when there is
 * one and the phone is not already in it), then "al salir de aquí". Nothing at all without the
 * background location grant — the fences and the watch that would keep either do not run
 * without it, and the watch's own memory is pruned on every sync.
 */
fun snoozePlaceOffers(
    saved: List<SavedPlace>,
    reminders: List<Reminder>,
    watch: PlaceWatchState,
    locationAllowed: Boolean,
): List<SnoozePlace> {
    if (!locationAllowed) return emptyList()
    val arrive = mostUsedPlace(saved, reminders)?.takeIf { arriveOffered(it, watch) }?.let { SnoozePlace.Arrive(it) }
    // The size the offer would draw if it were tapped now, from the last position anything took.
    // An estimate on purpose: the tap asks for a fresh fix, and a better one only makes the
    // circle smaller — which is the direction that rings sooner.
    val here = SnoozePlace.LeaveHere(watch.lastFix?.let { hereRadiusM(it.accuracyM) } ?: SNOOZE_HERE_MIN_RADIUS_M)
    return listOfNotNull(arrive) + here
}

/**
 * What "la próxima vez" would wait for, whether or not anything is waiting yet — null when none
 * of the reminder's own doorways can ring it again, which is when there is nothing to offer.
 *
 * **Only a doorway makes the offer.** "Al llegar a Mercadona" that rang and was left rings again
 * at the next arrival by itself — the watch and the fences are owed the other side of the line
 * first ([presenceAlreadyRang] never spends a crossing) — so leaving it to the rules is honest.
 * A *state* that has had its say keeps quiet until a "hecho", so "la próxima vez que esté en
 * casa" would be a promise nothing keeps; and a clock alone is already what the clock snoozes
 * and "saltar la próxima" are for. A routine's rules never ring, a contact's moment is the
 * list's, and a span that has taken over has taken the rules out of the loop: none of them.
 *
 * **The button names the door only when the door is all there is.** Anything else that can ring
 * it — another doorway, a sibling's moment still ahead, a state that has not rung, a set read
 * "a la vez" whose windows open on their own — rings it too, because the rules decide; so the
 * button says "cuando vuelva a tocar" rather than promise one place and ring at another.
 */
fun Reminder.nextTime(
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    dayStart: LocalTime = DEFAULT_DAY_START,
    shape: DayShape = DayShape.DEFAULT,
): SnoozePlace.NextTime? {
    if (status != Status.ACTIVE || isRoutine || isContact || spanHasTakenOver) return null
    val from = rulesLookFrom(now, restUntil(zone, dayStart, shape))
    val doors = ArrayList<Trigger.Location>()
    var others = false
    for (index in pendingRules()) {
        val rule = ruleInSet(index, shape, zone) ?: continue
        // A rule whose fences are over for good (a date gone by) rings nothing, door or not.
        nextFireOfRule(rule, id, from, zone, defaultTime, shape) ?: continue
        val place = rules[index].trigger as? Trigger.Location
        when {
            place == null -> others = true
            place.onCrossing -> doors += place
            // A state held quiet by its own ring would ring nothing; any other state can —
            // one under «desde que suena» included, once its rest is over.
            !presenceAlreadyRang(place, index, now, zone, dayStart, shape) -> others = true
        }
    }
    if (doors.isEmpty()) return null
    val alone = !others && (ruleMatch == RuleMatch.ANY || !rulesCombine)
    return SnoozePlace.NextTime(doors.singleOrNull()?.takeIf { alone })
}

/** [nextTime], as an answer to a ring: only while one is owed — anything else is already waiting for its rules. */
fun Reminder.nextTimeOffer(
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    dayStart: LocalTime = DEFAULT_DAY_START,
    shape: DayShape = DayShape.DEFAULT,
): SnoozePlace.NextTime? = if (awaitingAnswer(now)) nextTime(now, zone, defaultTime, dayStart, shape) else null

/**
 * The place offers for one ring: its own "la próxima vez" first, then the screen's ([offers],
 * [snoozePlaceOffers]' answer) — less the arrival at the very door it names, which would be the
 * same place put the other way: a circle that outranks the rules beside the rules themselves.
 *
 * Nothing at all when [offers] is empty, which is exactly when the background location grant is
 * missing ("al salir de aquí" is otherwise always there): the rules' own circles are not watched
 * without it either, so "la próxima vez" would be a wait nobody keeps.
 */
fun placeOffersFor(offers: List<SnoozePlace>, nextTime: SnoozePlace.NextTime?): List<SnoozePlace> {
    if (offers.isEmpty() || nextTime == null) return offers
    val door = nextTime.door?.takeIf { it.presence == Presence.INSIDE }
    val rest = offers.filterNot { offer ->
        door != null && offer is SnoozePlace.Arrive && distanceMeters(offer.place.lat, offer.place.lng, door.lat, door.lng) <= SAME_PLACE_M
    }
    return listOf(nextTime) + rest
}

/** The detail a history line keeps for "la próxima vez": this, then the door's own when the offer named one. */
const val NEXT_TIME_DETAIL = "next"

fun nextTimeDetail(door: Trigger.Location?): String = NEXT_TIME_DETAIL + (door?.let { ":" + it.snoozeDetail() } ?: "")

fun isNextTimeDetail(detail: String): Boolean = detail == NEXT_TIME_DETAIL || detail.startsWith("$NEXT_TIME_DETAIL:")

/** The side and the name a "la próxima vez" line named, or null for "cuando vuelva a tocar" (and for any other line). */
fun nextTimeDoorOf(detail: String): Pair<Presence, String>? =
    if (detail.startsWith("$NEXT_TIME_DETAIL:")) snoozeDetailOf(detail.removePrefix("$NEXT_TIME_DETAIL:")) else null

/** What a history line keeps about a snooze to a place: which way, and the place's name. */
fun Trigger.Location.snoozeDetail(): String = (if (presence == Presence.INSIDE) "arrive:" else "leave:") + label

/** The history line's word back: the side and the name, or null for a detail that is a clock. */
fun snoozeDetailOf(detail: String): Pair<Presence, String>? = when {
    detail.startsWith("arrive:") -> Presence.INSIDE to detail.removePrefix("arrive:")
    detail.startsWith("leave:") -> Presence.OUTSIDE to detail.removePrefix("leave:")
    else -> null
}
