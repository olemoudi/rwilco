package dev.rwilco.ui.routines

import dev.rwilco.model.DEFAULT_DAY_START
import dev.rwilco.model.Reminder
import dev.rwilco.model.RoutineFilter
import dev.rwilco.model.Status
import dev.rwilco.model.awaitingAnswer
import dev.rwilco.model.isRoutine
import dev.rwilco.model.overdueRoutines
import dev.rwilco.model.routineAnchor
import dev.rwilco.model.routineDeadline
import dev.rwilco.model.routineDone
import dev.rwilco.model.routineFilters
import dev.rwilco.model.routinesFor
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * One routine as its row reads: «¿He hecho «[text]»? → Sí/No», how long since, and how long
 * until (or since) the span ran out. Elapsed and remaining are worked out on screen from
 * [anchor] and [deadline] by the minute, so a row is not rebuilt every time the clock moves.
 */
data class RoutineRowUi(
    val id: String,
    val text: String,
    val tags: List<String>,
    /** The moment the count runs from: the last "hecho", or the day it was written. */
    val anchor: Instant,
    /** When the span is up, counted from [anchor]. */
    val deadline: Instant,
    /** The span itself, as it lands: what the progress track is a fraction of. */
    val span: Duration,
    /** The "Sí": the span has not run out. False is the "No" — the deadline has passed. */
    val done: Boolean,
    val paused: Boolean,
    /** Whether "posponer" is an answer right now: its deadline rang, or it is already put off. */
    val snoozeOffered: Boolean,
    val snoozed: Boolean,
)

data class RoutinesUiState(
    val loaded: Boolean = false,
    val rows: List<RoutineRowUi> = emptyList(),
    /** What the rows are filtered by; [RoutineFilter.All] when nothing is. */
    val filter: RoutineFilter = RoutineFilter.All,
    /** The chips worth offering: "vencidas" while any is, then the routines' own tags. */
    val filters: List<RoutineFilter> = emptyList(),
    /** How many routines there are at all, filter or no filter: an empty list under a filter is not "none". */
    val total: Int = 0,
    /** The words the list is narrowed by; blank when nobody is searching. */
    val query: String = "",
    val overdue: Int = 0,
    val failed: Boolean = false,
) {
    /** No routine at all, as opposed to a filter that found none. */
    val empty: Boolean get() = loaded && !failed && total == 0
}

/** Everything the routines screen shows, from the open reminders. Pure and JVM-tested. */
fun buildRoutinesState(
    reminders: List<Reminder>,
    selected: RoutineFilter,
    now: Instant,
    zone: ZoneId,
    dayStart: LocalTime = DEFAULT_DAY_START,
    query: String = "",
): RoutinesUiState {
    val filters = routineFilters(reminders, now, zone, dayStart)
    // A filter on something no longer offered is no filter: the last overdue one was done, the
    // last routine wearing that tag was deleted. By the spelling on offer, as Home's chips do.
    val filter = when (selected) {
        RoutineFilter.All -> selected
        RoutineFilter.Overdue -> selected.takeIf { it in filters } ?: RoutineFilter.All
        is RoutineFilter.Tag -> filters.firstOrNull { it is RoutineFilter.Tag && it.tag.equals(selected.tag, ignoreCase = true) } ?: RoutineFilter.All
    }
    val rows = routinesFor(reminders, filter, now, zone, dayStart, query).mapNotNull { reminder ->
        val anchor = reminder.routineAnchor()
        val deadline = reminder.routineDeadline(zone, dayStart) ?: return@mapNotNull null
        RoutineRowUi(
            id = reminder.id,
            text = reminder.text,
            tags = reminder.tags,
            anchor = anchor,
            deadline = deadline,
            span = Duration.between(anchor, deadline),
            done = reminder.routineDone(now, zone, dayStart),
            paused = reminder.status == Status.PAUSED,
            snoozeOffered = reminder.awaitingAnswer(now) ||
                (reminder.status == Status.ACTIVE && (reminder.snoozedUntil?.let { it > now } == true || reminder.snoozedToPlace != null)),
            snoozed = reminder.status == Status.ACTIVE && (reminder.snoozedUntil?.let { it > now } == true || reminder.snoozedToPlace != null),
        )
    }
    return RoutinesUiState(
        loaded = true,
        rows = rows,
        filter = filter,
        filters = filters,
        query = query,
        // Counted, not listed: how many there are does not need them sorted, and this is
        // asked again every minute. The same count Home's line is built from.
        total = reminders.count { it.isRoutine && it.status != Status.DONE },
        overdue = overdueRoutines(reminders, now, zone, dayStart).size,
    )
}
