package dev.rwilco.model

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/*
 * Which circles are worth a position, and when the ones that are not become worth one.
 *
 * A position is the most expensive answer this app buys, so this is where it decides not to.
 * Everything here is arithmetic on the rules and the clock — no fix is read, nothing is
 * watched — which is what lets the same answer serve the two callers that need it: the watch,
 * deciding what to spend, and a card, saying whether it is spending.
 */


/**
 * One circle of one reminder, and what the watch owes it right now.
 *
 * [opensAt] is the whole of the saving. Null means the circle is worth a position now; anything
 * else is the moment it becomes worth one, and until then the circle costs nothing — it is
 * judged against whatever positions the other circles pay for and never asks for one of its own
 * (see `PlaceWatcher`). [resting] is the one memory that outlives even a look that takes no
 * position at all: which side of the line the phone was on when the reminder went to rest.
 */
data class Gated(
    val place: WatchedPlace,
    val ruleIndex: Int,
    val opensAt: Instant? = null,
    val resting: Boolean = false,
)

/**
 * Every circle this reminder needs an eye on, gated.
 *
 * Three gates, and a circle asks for a position only while every one that applies to it is open.
 * The **hours**: a place whose rule cannot ring outside a window ("en la oficina, entre las
 * cinco y las siete") cannot ring at three in the morning however far anybody walks, so nothing
 * is spent on it until the window is near — [PlaceWatchPolicy.WINDOW_LEAD] before it and not at
 * it, so the run-up is what settles the cadence and a genuine arrival at one minute past is an
 * arrival and not a first reading. The **moment**: a circle a clock rule only *asks about* ("a
 * las nueve, y sólo si estoy en casa") is asked at that rule's next moment and at no other time,
 * so it is left alone until [PlaceWatchPolicy.ASK_LEAD] before it — and the same for a place
 * under "a la vez" that cannot ring on its own and is only ever read at a sibling's moment. And
 * the **recurrence**: a reminder resting on a span is not being asked anything at all.
 *
 * **Under "todos" a place is never gated by its siblings.** A place is a *state* everywhere
 * now — "cuando salga de la oficina" is met by being out of it and un-met by walking back in —
 * and under "todos" the set rings when the last of its rules is met, which may be a date six
 * weeks off. A circle switched off until that date throws away
 * every crossing in between, and the set is then waiting for a leaving that already happened
 * and will not happen again. So it stays on and pays for the waiting instead:
 * [WatchedPlace.floor] holds it to the cheapest cadence there is
 * ([PlaceWatchPolicy.MAX_WAIT], an hourly wifi position, never the GPS) while the soonest
 * sibling moment is more than a run-up away, and lets the ordinary distance arithmetic have it
 * back once the set is within [PlaceWatchPolicy.WINDOW_LEAD] of being able to ring. A floor is
 * this circle's own: a doorstep three streets away still sets the cadence for everybody, and
 * this one is judged on the way past for nothing.
 *
 * A place already ticked off under "todos" is watched too, wearing [Crossing.TAKES_BACK] and
 * the crossing it is now waiting for — the opposite one — because that is what un-meets it.
 * Its id does not change with the flip: the memory of which side of the line the phone is on
 * belongs to the circle and has to survive being met and un-met.
 */
fun Reminder.watchedCircles(
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    shape: DayShape = DayShape.DEFAULT,
    dayStart: LocalTime = DEFAULT_DAY_START,
): List<Gated> {
    if (status != Status.ACTIVE) return emptyList()
    val pending = pendingRules().toSet()
    val folded = rules.indices.map { togetherRule(it) }
    // A rule's moment cannot be asked before a snooze is over: the snooze rings instead, with
    // no rule behind it and nothing asked. Nor before a rest is — dealt with and coming back on
    // a span, the rules say nothing until it is up.
    val rest = restUntil(zone, dayStart, shape)?.takeIf { it > now }
    val from = maxOf(now, snoozedUntil ?: now, rest ?: now)
    // When each pending clock rule next rings — the moment its own circles, and under "a la
    // vez" every sibling place, are going to be asked about. A rule that cannot ring (a fold of
    // two moments, a window that never holds) asks nothing and is not here.
    val moments = HashMap<Int, Instant>()
    for (index in pending) {
        val rule = folded[index] ?: continue
        if (rule.trigger is Trigger.Location) continue
        val at = when (val next = nextFireOfRule(rule, id, from, zone, defaultTime, shape)) {
            is NextFire.Scheduled -> next.at
            is NextFire.Sometime -> next.at
            is NextFire.WhenAt, null -> null
        }
        if (at != null) moments[index] = at
    }
    val soonest = moments.values.minOrNull()
    val accumulates = ruleMatch == RuleMatch.ALL && rulesCombine
    val floor = when {
        !accumulates || soonest == null -> Duration.ZERO
        soonest > now + PlaceWatchPolicy.WINDOW_LEAD -> PlaceWatchPolicy.MAX_WAIT
        else -> Duration.ZERO
    }
    // A little before the hour it opens, so the first position of a window is taken before
    // anything is judged by it rather than after.
    val soon = now + PlaceWatchPolicy.MIN_WAIT
    return rules.flatMapIndexed { index, rule ->
        val place = rule.trigger as? Trigger.Location
        val ticked = accumulates && place != null && index in firedRules
        // A rule that is not pending has nothing left to report — unless it is a place under
        // "todos", which is a state and can come undone.
        if (index !in pending && !ticked) return@flatMapIndexed emptyList()
        // **A state that has already had its say is not worth a radio.** "Mientras esté en casa"
        // rings once a round (`presenceAlreadyRang`), so once it has rung, every firing of this
        // rule is dropped until somebody deals with it — and watching a circle that can only
        // produce dropped firings is paying for a position to learn nothing. It was the one
        // circle on a real phone with no gate at all: a single-rule set has no window to close
        // it, and its recurrence cannot rest a reminder nobody has dealt with. So it cost a fix
        // every few minutes, GPS included, for a reminder that could not ring.
        //
        // Only when it is the reminder's *only* rule. With siblings the circle is still wanted:
        // under "a la vez" it is folded into every other rule as a state, and those rules are
        // not spent — a window beside it can ring again, and it is this map that answers where
        // the phone was when it did.
        if (place != null && !place.onCrossing && rules.size == 1 && presenceAlreadyRang(place)) {
            return@flatMapIndexed emptyList()
        }
        val fold = folded[index]
        val gate: Instant? = if (place != null) {
            // Its own hours and, folded in, its siblings'. A fold that comes back null is a
            // crossing that can never ring — the circle is still watched, quietly, because a
            // sibling's moment is going to ask where the phone is; but only from that moment's
            // lead, and not at all if there is no such moment.
            val hours = (fold ?: rule).windows().openFrom(now, zone)?.minus(PlaceWatchPolicy.WINDOW_LEAD)
            val opens = when {
                hours == null -> null
                fold != null -> hours
                soonest == null -> null
                else -> maxOf(hours, soonest - PlaceWatchPolicy.ASK_LEAD)
            }
            opens?.let { maxOf(it, rest ?: it) }
        } else {
            // A clock rule asks about its circles at its own next moment and at no other time.
            moments[index]?.minus(PlaceWatchPolicy.ASK_LEAD)
        }
        // No gate at all is a circle nothing is ever going to ask about: not watched, not
        // listened to, and its memory is not worth keeping either.
        if (gate == null) return@flatMapIndexed emptyList()
        val opensAt = gate.takeIf { it > soon }
        val trigger = place?.let {
            Gated(
                place = WatchedPlace(
                    id = GeofenceIds.encode(id, index, it),
                    lat = it.lat,
                    lng = it.lng,
                    radiusM = it.radiusM,
                    transition = if (ticked) it.presence.opposite.asTransition else it.presence.asTransition,
                    label = it.label,
                    // A tick comes off when the state stops holding, never on a doorway: "al
                    // llegar a casa" ticked off is un-ticked by not being at home any more,
                    // and asking for a second crossing to say so would leave the set holding a
                    // rule that is plainly untrue.
                    onCrossing = it.onCrossing && !ticked,
                    crossing = when {
                        ticked -> Crossing.TAKES_BACK
                        // A crossing that cannot complete the set is worth knowing about and
                        // not worth ringing about.
                        fold == null -> Crossing.NOTHING
                        else -> Crossing.RINGS
                    },
                    floor = floor,
                ),
                ruleIndex = index,
                opensAt = opensAt,
                resting = rest != null,
            )
        }
        // A ticked rule is not being waited on; only the crossing back is, and that asks
        // nothing of its conditions.
        val asked = if (ticked) {
            emptyList()
        } else {
            rule.conditions.mapIndexedNotNull { at, condition ->
                condition.place?.let { circle ->
                    Gated(
                        place = WatchedPlace(
                            id = GeofenceIds.encodeCondition(id, index, at, circle),
                            lat = circle.lat,
                            lng = circle.lng,
                            radiusM = circle.radiusM,
                            // Waiting to be there reads as an arrival, waiting not to be as a
                            // leaving; it is the cadence that reads it, never a firing.
                            transition = if (circle.inside) Transition.ENTER else Transition.EXIT,
                            label = circle.label,
                            crossing = Crossing.NOTHING,
                        ),
                        ruleIndex = index,
                        opensAt = opensAt,
                        resting = false,
                    )
                }
            }
        }
        listOfNotNull(trigger) + asked
    }
}

/** Whether rule [index]'s own circle is being watched right now — what a card's mark says. */
fun List<Gated>.watchingRule(index: Int): Boolean =
    any { it.ruleIndex == index && it.opensAt == null && it.place.crossing != Crossing.NOTHING }
