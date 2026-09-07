package dev.rwilco.ui.editor

import dev.rwilco.data.FiringEvent
import dev.rwilco.data.FiringKind
import java.time.Duration

/**
 * What a routine's history comes to: how many times it has been done, and how far apart on
 * average. Pure, so the sentence over the list is a thing a JVM test can hold.
 *
 * A "hecho" is a [FiringKind.DEALT] or a [FiringKind.RESET] (a place vouching for the deed);
 * an [FiringKind.UNRESET] takes the reset before it back. The mean gap is between consecutive
 * "hechos", newest first as the history is kept, and null with fewer than two.
 */
data class RoutineHistory(val done: Int, val meanGap: Duration?)

fun routineHistory(events: List<FiringEvent>): RoutineHistory {
    val dones = ArrayList<FiringEvent>()
    var undone = 0
    // Newest first: an UNRESET met before its RESET cancels the next RESET seen.
    for (event in events) {
        when (event.kind) {
            FiringKind.UNRESET -> undone++
            FiringKind.RESET -> if (undone > 0) undone-- else dones += event
            FiringKind.DEALT -> dones += event
            else -> Unit
        }
    }
    val gaps = dones.zipWithNext { later, earlier -> Duration.between(earlier.at, later.at) }
    val mean = gaps.takeIf { it.isNotEmpty() }?.let { list -> list.fold(Duration.ZERO) { acc, gap -> acc.plus(gap) }.dividedBy(list.size.toLong()) }
    return RoutineHistory(dones.size, mean)
}
