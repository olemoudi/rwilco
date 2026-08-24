package dev.rwilco.model

import kotlinx.serialization.Serializable

/**
 * One way a reminder can ring: an event, and the conditions that have to hold when it happens.
 *
 * A reminder holds a list of these and any one of them is enough — so the rules are ORed and a
 * rule's own conditions are ANDed. That shape (an OR of ANDs) can express any combination a
 * person can reasonably mean, and unlike a free-form tree it can be read off a phone screen:
 * "cualquiera de estos: al llegar a casa (y sólo si es entre las 18:00 y las 22:00)".
 */
@Serializable
data class TriggerRule(
    val trigger: Trigger,
    val conditions: List<Condition> = emptyList(),
) {
    val restricted: Boolean get() = conditions.isNotEmpty()
}

/** The plain reading of a list of triggers, from before conditions existed. */
fun List<Trigger>.asRules(): List<TriggerRule> = map { TriggerRule(it) }
