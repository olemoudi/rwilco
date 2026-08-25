package dev.rwilco.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class Reminder(
    val id: String,
    val text: String,
    val tags: List<String> = emptyList(),
    /** How [ruleMatch] combines them; a rule's own conditions always all have to hold (ANDed). */
    val rules: List<TriggerRule> = emptyList(),
    /**
     * Whether dealing with a firing leaves it waiting for the next one, and when that is.
     *
     * [Recurrence.None] by default, and the default is the whole point: "hecho" means finished.
     * A place, a repeating time and a random window can all technically come round again, and
     * treating "can" as "should" is how a reminder somebody has dealt with rings again the same
     * afternoon. Recurrence is a thing you ask for.
     */
    val recurrence: Recurrence = Recurrence.None,
    /** Whether any one rule is enough, or every one of them has to have happened. */
    val ruleMatch: RuleMatch = RuleMatch.ANY,
    val actions: Set<Action> = DEFAULT_ACTIONS,
    val status: Status = Status.ACTIVE,
    val createdAt: Instant,
    val updatedAt: Instant,
    val doneAt: Instant? = null,
    /** While this is in the future the reminder rings then, instead of at its trigger's moment. */
    val snoozedUntil: Instant? = null,
    /** When it last actually rang. Paired with [armedFor] it is how a missed firing is spotted. */
    val lastFiredAt: Instant? = null,
    /**
     * When a firing was last dealt with. The anchor every recurrence counts from — "six hours
     * after the last one" is six hours after this — and null until it has happened once, when
     * the reminder's own beginning stands in for it.
     */
    val lastDealtAt: Instant? = null,
    /**
     * The moment the scheduler last set an alarm for. Persisted because it is the only way to
     * tell "the phone was off when this should have rung" from "it rang and I ignored it":
     * an [armedFor] in the past with no [lastFiredAt] to match is a firing the device slept
     * through.
     */
    val armedFor: Instant? = null,
    /**
     * Which rule [armedFor] belongs to. Without it a firing the phone slept through could be
     * recorded against the wrong rule, which under [RuleMatch.ALL] is the difference between
     * ringing and waiting for something that already happened.
     */
    val armedRule: Int? = null,
    /**
     * Under [RuleMatch.ALL]: the rules whose event has already happened in this round, by
     * index. Cleared when the person deals with the firing, which is what starts the next
     * round — "llamar a Marta cuando llegue a casa y sean más de las nueve" is a thing that can
     * happen again next week, and half of it having happened last week is not a head start.
     */
    val firedRules: Set<Int> = emptySet(),
)

/**
 * What a list of rules means together.
 *
 * ANY is the everyday one and the default: "a las nueve, o al llegar a casa" — either does it.
 * ALL is the accumulating reading: every one of them has to have happened, in any order and
 * however far apart, and the *last* of them rings. Which is why the ones that already happened
 * are remembered: see [Reminder.firedRules].
 *
 * TOGETHER is the third: every rule true **at the same moment**, ringing on the instant the
 * last of them becomes true. That needs each rule read as a state rather than an event
 * ([Trigger.asState]) — a place is "being inside it", a window is "being in it", and everything
 * else is a moment, true at an instant and false either side. So a set with two moments in it
 * never rings (two instants do not coincide) and a set with no moment and no window has nothing
 * to start it; [warnings] says so rather than letting anybody find out in a week.
 *
 * It is the same conjunction a rule's own conditions have always been, reached the other way
 * round: whoever writes three triggers and means "all at once" should not have to know that.
 */
enum class RuleMatch { ANY, ALL, TOGETHER }

/** What happens when a reminder fires. Stored by name; unknown names are dropped on read. */
enum class Action { FULL_SCREEN, NOTIFICATION, SOUND, VIBRATE }

enum class Status { ACTIVE, PAUSED, DONE }

val DEFAULT_ACTIONS: Set<Action> = setOf(Action.NOTIFICATION, Action.VIBRATE)

/** The events, without their conditions — for anything that only cares what kind they are. */
val Reminder.triggers: List<Trigger> get() = rules.map { it.trigger }

/** Whether the rules actually combine: one rule is one rule, whatever the toggle says. */
val Reminder.rulesCombine: Boolean get() = rules.size > 1

/**
 * The rules still waiting to happen: all of them under ANY (any one still rings it) and under
 * TOGETHER (nothing accumulates — a rule that was true an hour ago says nothing about now), and
 * the ones not yet ticked off under ALL. Indices that no longer exist are ignored, so editing a
 * reminder down to fewer rules cannot leave it waiting for a rule that is gone.
 */
fun Reminder.pendingRules(): List<Int> = when {
    ruleMatch != RuleMatch.ALL || !rulesCombine -> rules.indices.toList()
    else -> rules.indices.filter { it !in firedRules }
}

/**
 * One rule of a TOGETHER set, with every other rule folded into it as a condition.
 *
 * This is the whole of how "a la vez" works, and why it needed almost no new machinery: asking
 * "did rule 2 just happen, and is everything else true right now?" is asking whether rule 2's
 * conditions hold, once the others have been read as states. A sibling with no state reading —
 * another moment — folds to nothing and takes the set with it, which is what [cannotCoincide]
 * exists to say before anybody waits for it.
 */
fun Reminder.togetherRule(index: Int): TriggerRule? {
    val rule = rules.getOrNull(index) ?: return null
    if (ruleMatch != RuleMatch.TOGETHER || !rulesCombine) return rule
    val others = rules.filterIndexed { at, _ -> at != index }
    // A sibling that is only ever true at an instant cannot be true at *this* instant, so the
    // set cannot hold and this rule must not ring. Folding it to nothing and carrying on would
    // ring a set that [momentsCannotCoincide] has already called impossible — which is the one
    // way this could have quietly done the wrong thing.
    if (others.any { it.trigger.isMoment }) return null
    return rule.copy(conditions = rule.conditions + others.flatMap { it.conditions } + others.mapNotNull { it.trigger.asState() })
}

/**
 * Whether a TOGETHER set asks for two instants to be the same instant, which they are not.
 *
 * The only thing about a set of rules that cannot be seen by folding it together: everything
 * else that can go wrong — circles that do not touch, a moment outside every window — is the
 * ordinary business of [TriggerRule.placesConflict] and [nextFireOfRule] once it is folded.
 * There is no set that nothing can start: a moment arrives, a window opens, a line is crossed.
 */
/**
 * When a rule's circle is worth watching at all, and when it next will be.
 *
 * A place under "a la vez" can only ring while the rest of the set is true, and the part of
 * "the rest" that a clock can settle on its own is its windows. "En la oficina, entre las cinco
 * y las siete" cannot ring at three in the morning, so the watch has no business spending a fix
 * on it until five — which for the phone in a pocket all night is the difference between a
 * dozen reads and none.
 *
 * Only windows gate. A sibling *place* cannot gate another place: answering it would need the
 * very fix this exists to avoid spending.
 *
 * Returns the moment the gate is open from — [now] when it already is, and null when these
 * windows never all hold at once, which is a circle to leave alone entirely.
 */
fun List<Condition.TimeWindow>.openFrom(now: Instant, zone: ZoneId): Instant? {
    if (isEmpty() || allHoldAt(now, zone)) return now
    // A conjunction of windows can only begin where one of them begins, so those are the only
    // candidates worth trying — and one that does not satisfy the others is not the beginning.
    return mapNotNull { window ->
        (nextFireOf(Trigger.Interval(window.from, window.to, window.days), "", now, zone, LocalTime.MIDNIGHT) as? NextFire.Scheduled)
            ?.at
            ?.takeIf { allHoldAt(it, zone) }
    }.minOrNull()
}

/** The windows on a rule, wherever they came from: its own conditions or a folded-in sibling. */
fun TriggerRule.windows(): List<Condition.TimeWindow> = conditions.filterIsInstance<Condition.TimeWindow>()

fun Reminder.momentsCannotCoincide(): Boolean =
    ruleMatch == RuleMatch.TOGETHER && rulesCombine && rules.count { it.trigger.isMoment } > 1
