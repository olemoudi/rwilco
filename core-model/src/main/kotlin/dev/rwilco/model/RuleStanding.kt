package dev.rwilco.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Where one rule of a set stands right now — the thing a card cannot say with words alone.
 *
 * Two of the three readings of a list of rules keep state, and it is invisible: under ALL some
 * of them have already happened and the reminder is waiting for the rest; under TOGETHER each
 * one is either true at this moment or not, and the ring is the instant they all are. Both are
 * decided somewhere the person cannot see, and the difference between them is exactly what
 * people get wrong about the two words. So each rule carries a mark, and the mark changes back
 * when the rule stops holding.
 */
enum class RuleStanding {
    /** ALL: it has happened this round and is not being waited on any more. */
    DONE,

    /** ALL: still to happen. */
    PENDING,

    /** TOGETHER: true at this moment — the phone is inside the circle, the hour is in the window. */
    HOLDING,

    /** TOGETHER: not true at this moment. */
    NOT_HOLDING,

    /** TOGETHER, a place nobody has looked at yet: no fix, no answer. */
    UNKNOWN,
}

/**
 * The standing of every rule, in order; null where a rule has none to give — a single rule, a
 * set read as "either of them", or a moment under TOGETHER, which is not a state but the thing
 * that rings when the states around it hold.
 *
 * **A resting set has no standings at all.** Dealt with and coming back on a span, its rules
 * are not being asked: no circle of theirs is watched, no window of theirs is judged, and what
 * it is waiting for is the rest — which the card's own recurrence row already says. A mark
 * there would be an answer to a question nobody put, and the place ones would be worse than
 * that: the watch keeps a resting circle's last judgement on purpose (`Watching.remembered`,
 * so a place that has rung is owed a leaving before it rings again), and reading that as
 * "no se cumple ahora mismo" states last night's memory as this minute's fact.
 *
 * [inside] answers "is the phone inside rule `index`'s circle?", or null when nothing knows;
 * only the app can say, and only from the place watch's last fix.
 */
fun Reminder.ruleStandings(
    now: Instant,
    zone: ZoneId,
    dayStart: LocalTime = DEFAULT_DAY_START,
    shape: DayShape = DayShape.DEFAULT,
    inside: (Int) -> Boolean? = { null },
): List<RuleStanding?> {
    if (!rulesCombine) return rules.map { null }
    val rest = restUntil(zone, dayStart, shape)
    if (rest != null && rest > now) return rules.map { null }
    return rules.mapIndexed { index, rule ->
        when (ruleMatch) {
            RuleMatch.ALL -> if (index in firedRules) RuleStanding.DONE else RuleStanding.PENDING
            RuleMatch.TOGETHER -> when (val state = rule.trigger.asState(shape)) {
                is Condition.TimeWindow -> if (state.holdsAt(now, zone)) RuleStanding.HOLDING else RuleStanding.NOT_HOLDING
                is Condition.AtPlace -> when (inside(index)) {
                    // A rule waiting to arrive holds while the phone is in; one waiting to leave
                    // holds while it is out. The circle is the same; what is asked of it is not.
                    null -> RuleStanding.UNKNOWN
                    state.inside -> RuleStanding.HOLDING
                    else -> RuleStanding.NOT_HOLDING
                }
                null -> null
            }
            RuleMatch.ANY -> null
        }
    }
}
