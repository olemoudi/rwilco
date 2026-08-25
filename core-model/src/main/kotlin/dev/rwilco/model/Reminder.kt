package dev.rwilco.model

import java.time.Instant

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
 * ALL is the other honest reading: "cuando llegue a casa Y sean más de las nueve", which for
 * events (rather than states) can only mean *the last of them to happen* is what rings. Which
 * is why the ones that already happened are remembered: see [Reminder.firedRules].
 *
 * The third possible reading — every rule true at the same instant — is what conditions are
 * for ("y sólo si"), and it stays there.
 */
enum class RuleMatch { ANY, ALL }

/** What happens when a reminder fires. Stored by name; unknown names are dropped on read. */
enum class Action { FULL_SCREEN, NOTIFICATION, SOUND, VIBRATE }

enum class Status { ACTIVE, PAUSED, DONE }

val DEFAULT_ACTIONS: Set<Action> = setOf(Action.NOTIFICATION, Action.VIBRATE)

/** The events, without their conditions — for anything that only cares what kind they are. */
val Reminder.triggers: List<Trigger> get() = rules.map { it.trigger }

/** Whether the rules actually combine: one rule is one rule, whatever the toggle says. */
val Reminder.rulesCombine: Boolean get() = rules.size > 1

/**
 * The rules still waiting to happen: all of them under ANY (any one still rings it), and the
 * ones not yet ticked off under ALL. Indices that no longer exist are ignored, so editing a
 * reminder down to fewer rules cannot leave it waiting for a rule that is gone.
 */
fun Reminder.pendingRules(): List<Int> = when {
    ruleMatch == RuleMatch.ANY || !rulesCombine -> rules.indices.toList()
    else -> rules.indices.filter { it !in firedRules }
}
