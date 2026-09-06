package dev.rwilco.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * One way a reminder can ring: an event, and the conditions that have to hold when it happens.
 *
 * A reminder holds a list of these and any one of them is enough — so the rules are ORed and a
 * rule's own conditions are ANDed. That shape (an OR of ANDs) can express any combination a
 * person can reasonably mean, and unlike a free-form tree it can be read off a phone screen:
 * "cualquiera de estos: al llegar a casa (y sólo si es entre las 18:00 y las 22:00)".
 *
 * [resets] is only read under a routine ([Recurrence.Since]) and only on a place: a crossing
 * that **counts as having done it** — leaving the garage is the car moving — rather than one that
 * asks. Never written when it is not asked for, so no rule already on a phone changes shape on
 * disk over a field it does not use.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TriggerRule(
    val trigger: Trigger,
    val conditions: List<Condition> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val resets: Boolean = false,
) {
    val restricted: Boolean get() = conditions.isNotEmpty()
}

/** The plain reading of a list of triggers, from before conditions existed. */
fun List<Trigger>.asRules(): List<TriggerRule> = map { TriggerRule(it) }
