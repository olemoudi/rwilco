package dev.rwilco.model

import java.time.Duration
import java.time.Instant

/*
 * "Hecha cuando pase por todos": a routine done by a round of places, not by one (0.216.0).
 *
 * A place that counts as done ([TriggerRule.resets]) used to count on its own: leaving the garage
 * is the car moving. Some deeds are a round, though — "la compra" is the greengrocer, the butcher
 * and the bakery; "recados" is the post office and the bank — and any one of them alone is not
 * the deed. So a routine with two or more places that count as done can ask for **all of them**,
 * each crossed the way its own rule says (arriving or leaving), **inside one stretch of time**
 * ([Recurrence.Since.allPlacesWithinMinutes]). The last one to complete the set is the "hecho";
 * the ones before it are only written down ([FiringKind.VISITED]).
 *
 * **The round is read off the history**, not kept on the row: a visit is a line, so it survives
 * the process dying between the bakery and the bank, the vault carries it, and the editor's
 * history card shows it. A line counts for a rule when:
 * - it is that rule's (its index) *and* that rule's doorway as the rule stands now (the side and
 *   the place's name) — an edit that moved, removed or replaced the place is not the place that
 *   was visited;
 * - it is inside the stretch, counted back from the crossing that would complete the set
 *   ([now] − the window): a **sliding** window, so the bakery visited twice counts its later visit;
 * - and it is after everything that ended the last round: the last "hecho" ([Reminder.lastDealtAt]),
 *   and the last place reset or its undo — undone, a reset was not a deed, and the visits that
 *   led to it must not complete the next one on their own.
 */

/** The rules that count as done, by index: places only ([TriggerRule.resetsRoutine]). */
val Reminder.vouchingRules: List<Int> get() = rules.indices.filter { rules[it].resetsRoutine }

/**
 * How long the round of places has to take, or null when each place counts on its own: not a
 * routine, no window asked for, or fewer than two places that count as done — one place is a
 * round of one, and the window has nothing to bound.
 */
val Reminder.allPlacesWindow: Duration?
    get() {
        val minutes = (recurrence as? Recurrence.Since)?.allPlacesWithinMinutes ?: return null
        if (minutes <= 0 || vouchingRules.size < 2) return null
        return Duration.ofMinutes(minutes.toLong())
    }

/** The history's word for a doorway: the side and the place's name. The same shape a snooze to a place keeps. */
fun Trigger.Location.doorDetail(): String = snoozeDetail()

/**
 * Which places of the round stand visited at [now], by rule index, read off [history] (any order).
 * Empty when each place counts on its own ([allPlacesWindow] null). See the file's note.
 */
fun Reminder.placesVisited(history: List<FiringEvent>, now: Instant): Set<Int> {
    val window = allPlacesWindow ?: return emptySet()
    val roundEnded = history.filter { it.kind == FiringKind.RESET || it.kind == FiringKind.UNRESET }.maxOfOrNull { it.at }
    val from = listOfNotNull(now.minus(window), lastDealtAt, roundEnded).max()
    val doors = vouchingRules.associateWith { (rules[it].trigger as Trigger.Location).doorDetail() }
    return history
        .filter { it.kind == FiringKind.VISITED && it.at > from && it.at <= now }
        .mapNotNull { line -> line.ruleIndex?.takeIf { doors[it] != null && doors[it] == line.detail } }
        .toSet()
}

/**
 * Whether the place of rule [ruleIndex], crossed at [now], completes the round — every place that
 * counts as done visited inside the window, this one included. True for a place that counts on
 * its own, which is what every place did before the window existed.
 */
fun Reminder.completesPlaces(ruleIndex: Int, history: List<FiringEvent>, now: Instant): Boolean {
    if (allPlacesWindow == null) return true
    return (placesVisited(history, now) + ruleIndex).containsAll(vouchingRules)
}
