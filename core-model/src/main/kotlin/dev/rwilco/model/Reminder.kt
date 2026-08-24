package dev.rwilco.model

import java.time.Instant

data class Reminder(
    val id: String,
    val text: String,
    val tags: List<String> = emptyList(),
    /** Any one of them is enough (ORed); a rule's own conditions all have to hold (ANDed). */
    val rules: List<TriggerRule> = emptyList(),
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
     * The moment the scheduler last set an alarm for. Persisted because it is the only way to
     * tell "the phone was off when this should have rung" from "it rang and I ignored it":
     * an [armedFor] in the past with no [lastFiredAt] to match is a firing the device slept
     * through.
     */
    val armedFor: Instant? = null,
)

/** What happens when a reminder fires. Stored by name; unknown names are dropped on read. */
enum class Action { FULL_SCREEN, NOTIFICATION, SOUND, VIBRATE }

enum class Status { ACTIVE, PAUSED, DONE }

val DEFAULT_ACTIONS: Set<Action> = setOf(Action.NOTIFICATION, Action.VIBRATE)

/** The events, without their conditions — for anything that only cares what kind they are. */
val Reminder.triggers: List<Trigger> get() = rules.map { it.trigger }
