package dev.rwilco.ui.routines

import dev.rwilco.model.ContactKind
import dev.rwilco.model.ContactSchedule
import dev.rwilco.model.DEFAULT_DAY_START
import dev.rwilco.model.DEFAULT_PERSONAL_CONTACTS
import dev.rwilco.model.DEFAULT_WORK_CONTACTS
import dev.rwilco.model.Reminder
import dev.rwilco.model.contactDeadline
import dev.rwilco.model.contactOwed
import dev.rwilco.model.contactQueue
import dev.rwilco.model.isContact
import dev.rwilco.model.RoutineFilter
import dev.rwilco.model.Status
import dev.rwilco.model.awaitingAnswer
import dev.rwilco.model.isRoutine
import dev.rwilco.model.overdueRoutines
import dev.rwilco.model.routineAnchor
import dev.rwilco.model.routineDeadline
import dev.rwilco.model.routineDone
import dev.rwilco.model.routineFilters
import dev.rwilco.model.routinePutOff
import dev.rwilco.model.routineWaitingToStart
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
    /** The moment the count runs from: the last "hecho", or where the count starts. */
    val anchor: Instant,
    /**
     * Whether the count has not begun: a routine told to start later, never done since. The row
     * says "empieza en 5 d" rather than counting time that has not passed.
     */
    val startsLater: Boolean,
    /**
     * The clock the count is read against: frozen where a pause began, null for the wall clock.
     * "hace 10 d" stays "hace 10 d" for as long as the routine rests ([Reminder.routineClock]).
     */
    val pausedAt: Instant?,
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
    /** Until when it is put off, for the row to say so; null for a snooze to a place, and for none. */
    val snoozedUntil: Instant?,
    /** Put off, to a clock or a place: an answer given, so not owed — and said on the row. */
    val putOff: Boolean,
    /** Which kind of contact this is; null for an ordinary routine. */
    val contactKind: ContactKind? = null,
    /**
     * When a contact is next told about. Null on an ordinary routine — and on a contact with no
     * turn inside the year the draw looks over, which is what "sin turno" says.
     */
    val turnAt: Instant? = null,
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
    schedules: (ContactKind) -> ContactSchedule = { kind -> if (kind == ContactKind.WORK) DEFAULT_WORK_CONTACTS else DEFAULT_PERSONAL_CONTACTS },
): RoutinesUiState {
    // Worked out once for the list, because that is what it is a function of (`Contacts.kt`).
    val turns = contactQueue(reminders, now, zone, schedules, dayStart)
    val filters = routineFilters(reminders, now, zone, dayStart)
    // A filter on something no longer offered is no filter: the last overdue one was done, the
    // last routine wearing that tag was deleted. By the spelling on offer, as Home's chips do.
    val filter = when (selected) {
        RoutineFilter.All -> selected
        RoutineFilter.Overdue, RoutineFilter.Paused, RoutineFilter.Waiting, is RoutineFilter.Kind -> selected.takeIf { it in filters } ?: RoutineFilter.All
        is RoutineFilter.Tag -> filters.firstOrNull { it is RoutineFilter.Tag && it.tag.equals(selected.tag, ignoreCase = true) } ?: RoutineFilter.All
    }
    val rows = routinesFor(reminders, filter, now, zone, dayStart, query).mapNotNull { reminder ->
        val anchor = reminder.routineAnchor()
        val turn = turns[reminder.id]
        // A contact's row is about its turn, not its plazo: one whose cadence ran out three
        // weeks ago is waiting quite properly, and reading it against the plazo would draw a
        // full red track over somebody the budget is simply pacing.
        val deadline = (if (reminder.isContact) turn ?: reminder.contactDeadline(zone, dayStart) else null)
            ?: reminder.routineDeadline(zone, dayStart) ?: return@mapNotNull null
        RoutineRowUi(
            id = reminder.id,
            text = reminder.text,
            tags = reminder.tags,
            anchor = anchor,
            startsLater = reminder.routineWaitingToStart(now),
            pausedAt = reminder.pausedAt,
            deadline = deadline,
            span = Duration.between(anchor, deadline),
            done = if (reminder.isContact) !reminder.contactOwed(now) else reminder.routineDone(now, zone, dayStart),
            paused = reminder.status == Status.PAUSED,
            snoozeOffered = reminder.awaitingAnswer(now) ||
                (reminder.status == Status.ACTIVE && (reminder.snoozedUntil?.let { it > now } == true || reminder.snoozedToPlace != null)),
            snoozed = reminder.status == Status.ACTIVE && (reminder.snoozedUntil?.let { it > now } == true || reminder.snoozedToPlace != null),
            snoozedUntil = reminder.snoozedUntil?.takeIf { it > now && reminder.status == Status.ACTIVE },
            putOff = reminder.status == Status.ACTIVE && reminder.routinePutOff(now),
            contactKind = reminder.contactKind,
            turnAt = turn,
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
