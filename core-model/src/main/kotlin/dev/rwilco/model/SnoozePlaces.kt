package dev.rwilco.model

import java.time.Duration
import java.time.Instant

/*
 * "Posponer hasta llegar a un lugar."
 *
 * The answers to a ring used to be clock-shaped only, and on a phone whose reminders are mostly
 * places the honest answer to "comprar filtros" ringing on the metro is "cuando llegue a casa".
 * Two offers, and no more: the saved place this person's reminders use most, as a doorway in
 * — and "al salir de aquí", a circle drawn around wherever the phone is right now, thrown away
 * once it has rung. The reminder carries the circle in [Reminder.snoozedToPlace]; the watch and
 * the geofences treat it as the reminder's only circle until it rings.
 */

/** One of the two ways a ring can be put off to a place. */
sealed interface SnoozePlace {
    /** "Al llegar a [place]". */
    data class Arrive(val place: SavedPlace) : SnoozePlace

    /** "Al salir de aquí": a circle around the phone's own position, made at the moment of the tap. */
    data object LeaveHere : SnoozePlace
}

/** The radius of "aquí": generous, so a fix a street off and a walk to the corner are both inside. */
const val SNOOZE_HERE_RADIUS_M = 150

/** How far apart two circles may be and still be the same place; four decimals, as the suggestions count. */
private const val SAME_PLACE_M = 11.0

/** A position older than this, or sloppier than the circle it would draw, cannot say where "aquí" is. */
val HERE_FIX_MAX_AGE: Duration = Duration.ofMinutes(2)
const val HERE_FIX_MAX_ACCURACY_M = 150.0

/** The doorway in: "cuando llegue a casa", whichever side the phone is on when it is said. */
fun SnoozePlace.Arrive.circle(): Trigger.Location =
    Trigger.Location(place.lat, place.lng, place.radiusM, Presence.INSIDE, place.label, onCrossing = true)

/** The doorway out of a circle drawn around [fix]; [label] is the word for "here" in the person's language. */
fun hereCircle(fix: Fix, label: String): Trigger.Location =
    Trigger.Location(fix.lat, fix.lng, SNOOZE_HERE_RADIUS_M, Presence.OUTSIDE, label, onCrossing = true)

/** Whether a position is fresh and tight enough to draw "aquí" around. */
fun Fix.speaksForHere(now: Instant): Boolean =
    Duration.between(at, now).abs() <= HERE_FIX_MAX_AGE && accuracyM <= HERE_FIX_MAX_ACCURACY_M

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
fun arriveOffered(place: SavedPlace, watch: PlaceWatchState): Boolean =
    watch.sideOf(place.lat, place.lng, place.radiusM, inside = true) != true

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
    return listOfNotNull(arrive) + SnoozePlace.LeaveHere
}

/** What a history line keeps about a snooze to a place: which way, and the place's name. */
fun Trigger.Location.snoozeDetail(): String = (if (presence == Presence.INSIDE) "arrive:" else "leave:") + label

/** The history line's word back: the side and the name, or null for a detail that is a clock. */
fun snoozeDetailOf(detail: String): Pair<Presence, String>? = when {
    detail.startsWith("arrive:") -> Presence.INSIDE to detail.removePrefix("arrive:")
    detail.startsWith("leave:") -> Presence.OUTSIDE to detail.removePrefix("leave:")
    else -> null
}
