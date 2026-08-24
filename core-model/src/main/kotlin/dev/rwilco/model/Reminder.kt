package dev.rwilco.model

import java.time.Instant

data class Reminder(
    val id: String,
    val text: String,
    val tags: List<String> = emptyList(),
    val triggers: List<Trigger> = emptyList(),
    val actions: Set<Action> = DEFAULT_ACTIONS,
    val status: Status = Status.ACTIVE,
    val createdAt: Instant,
    val updatedAt: Instant,
    val doneAt: Instant? = null,
)

/** What happens when a reminder fires. Stored by name; unknown names are dropped on read. */
enum class Action { FULL_SCREEN, NOTIFICATION, SOUND, VIBRATE }

enum class Status { ACTIVE, PAUSED, DONE }

val DEFAULT_ACTIONS: Set<Action> = setOf(Action.NOTIFICATION, Action.VIBRATE)
