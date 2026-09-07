package dev.rwilco.model

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/*
 * Routines: the reminders that count time since the last time something was done.
 *
 * "Mover el coche", "regar las plantas", "cambiar el filtro" — none of them is an appointment.
 * What matters is how long it has been, and the only thing that resets that is doing it. A
 * routine is a reminder whose "Vuelve" says so ([Recurrence.Since]), and everything about how it
 * behaves follows from that one reading:
 *
 * - The span is the ring, counted from the last "hecho" (or from where the count started: the
 *   day it was written, or a moment the person named — see [routineStart]).
 * - "Hecho" is *now*: the count starts again from this moment ([Reminder.momentDealtWith]).
 * - A pause freezes the count ([Reminder.pausedAt], [routineClock], [routineAnchorAfterPause]):
 *   nothing is owed while it rests, and the time it rested is not time that passed.
 * - The rules never ring and never rest. A clock rule *asks* whether it has been done; a place
 *   asks too, or — when it can vouch for the deed — counts as having done it
 *   ([TriggerRule.resets]). Both are the scheduler's and the watch's business, on an alarm of
 *   their own; `nextFire` and `nextWake` only ever answer with the deadline.
 * - Home does not list them ([groupForHome]); it carries one line about the overdue ones, and
 *   the routines screen has the rest.
 */

/** Whether this reminder is a routine. The one predicate everything else here hangs off. */
val Reminder.isRoutine: Boolean get() = recurrence is Recurrence.Since

/**
 * Where the count starts before anything has been done: the moment the person named
 * ("empezando el 1 de octubre"), or the day the routine was written. Null unless it is a
 * routine. See [Recurrence.Since.startsAt].
 */
fun Reminder.routineStart(): Instant? = if (isRoutine) (recurrence as Recurrence.Since).startsAt ?: createdAt else null

/** The moment the count runs from: the last "hecho", or where it started until then. */
fun Reminder.routineAnchor(): Instant = lastDealtAt ?: routineStart() ?: createdAt

/**
 * The clock the count is read against: frozen at the moment a pause began, [now] otherwise.
 *
 * Every "how long has it been" and every "is it owed" on a paused routine is asked of this
 * rather than of the wall clock, so a routine paused ten days into its three weeks reads
 * "hace 10 d" for as long as it rests — and reads "Sí", because nothing is owed while it does.
 */
fun Reminder.routineClock(now: Instant): Instant = pausedAt ?: now

/**
 * Where the count runs from once a pause is lifted at [now]: the anchor pushed forward by
 * exactly the time paused, so the count continues where it stopped. What the repository writes
 * into `lastDealtAt` on resume (the history table keeps the real "hechos"; `lastDealtAt` is
 * the count's anchor and nothing else on a routine). The anchor as it is when nothing was
 * paused.
 */
fun Reminder.routineAnchorAfterPause(now: Instant): Instant {
    val paused = pausedAt ?: return routineAnchor()
    return routineAnchor().plus(Duration.between(paused, now).coerceAtLeast(Duration.ZERO))
}

/**
 * Whether the count has not begun yet: a routine that starts in the future and has never been
 * done. Nothing is owed meanwhile — the deadline is a span past the start — and the screens say
 * so rather than counting time that has not passed.
 */
fun Reminder.routineWaitingToStart(now: Instant): Boolean = lastDealtAt == null && routineStart()?.let { it > now } == true

/** When the span is up, counted from [routineAnchor]; null for anything that is not a routine. */
fun Reminder.routineDeadline(zone: ZoneId, dayStart: LocalTime = DEFAULT_DAY_START): Instant? =
    if (isRoutine) nextRecurrence(recurrence, routineAnchor(), zone, dayStart) else null

/** How long the span is, as it lands from [routineAnchor] — a whole number of its unit, landed on its hour. */
fun Reminder.routineSpan(zone: ZoneId, dayStart: LocalTime = DEFAULT_DAY_START): Duration? =
    routineDeadline(zone, dayStart)?.let { Duration.between(routineAnchor(), it) }

/**
 * Whether the routine stands done right now — the **"Sí"** on its row. "No" is the deadline having
 * passed with nothing done since: the ring is what says so out loud, and this is the same
 * question asked of the row. Not [Status]: a routine is never DONE, only done *for now*.
 */
fun Reminder.routineDone(now: Instant, zone: ZoneId, dayStart: LocalTime = DEFAULT_DAY_START): Boolean {
    val deadline = routineDeadline(zone, dayStart) ?: return false
    return deadline > routineClock(now)
}

/** The open routines whose span is up, the one that has waited longest first. What Home's line says. */
fun overdueRoutines(
    reminders: List<Reminder>,
    now: Instant,
    zone: ZoneId,
    dayStart: LocalTime = DEFAULT_DAY_START,
): List<Reminder> = reminders
    .filter { it.isRoutine && it.status == Status.ACTIVE && !it.routineDone(now, zone, dayStart) }
    .sortedWith(compareBy({ it.routineDeadline(zone, dayStart) }, { it.createdAt }))

/** What the routines screen shows: everything, only the overdue ones, or the ones wearing a tag. */
sealed interface RoutineFilter {
    data object All : RoutineFilter
    data object Overdue : RoutineFilter
    data class Tag(val tag: String) : RoutineFilter
}

/**
 * The routines under [filter], **overdue ones first** — the one that has waited longest on top —
 * then the rest by how soon their span is up, and the paused ones last: a paused routine is
 * still a routine, but nothing is owed while it rests.
 */
fun routinesFor(
    reminders: List<Reminder>,
    filter: RoutineFilter,
    now: Instant,
    zone: ZoneId,
    dayStart: LocalTime = DEFAULT_DAY_START,
    /**
     * Words to narrow the list by, forgivingly ([fuzzyScore], the same match Home searches
     * with); blank is no narrowing. The **order does not change**: this list means "what is
     * owed, soonest first", and a search that re-sorted it by how well each row matched would
     * answer a different question from the one the screen is for.
     */
    query: String = "",
): List<Reminder> = reminders
    .filter { it.isRoutine && it.status != Status.DONE }
    .filter { matchesWords(it, query) }
    .filter {
        when (filter) {
            RoutineFilter.All -> true
            RoutineFilter.Overdue -> it.status == Status.ACTIVE && !it.routineDone(now, zone, dayStart)
            is RoutineFilter.Tag -> it.tags.any { tag -> tag.equals(filter.tag, ignoreCase = true) }
        }
    }
    .sortedWith(
        compareBy<Reminder> {
            when {
                it.status != Status.ACTIVE -> 2
                it.routineDone(now, zone, dayStart) -> 1
                else -> 0
            }
        }
            .thenBy { it.routineDeadline(zone, dayStart) }
            .thenBy { it.createdAt },
    )

/** Whether [reminder]'s own words answer [query]; a blank query answers itself. */
private fun matchesWords(reminder: Reminder, query: String): Boolean {
    val needle = fold(query)
    return needle.isEmpty() || fuzzyScore(needle, fold(reminder.text)) != null
}

/** The filters worth offering: the app's own "vencidas" only while something is, then the routines' tags. */
fun routineFilters(reminders: List<Reminder>, now: Instant, zone: ZoneId, dayStart: LocalTime = DEFAULT_DAY_START): List<RoutineFilter> {
    val overdue = if (overdueRoutines(reminders, now, zone, dayStart).isEmpty()) emptyList() else listOf(RoutineFilter.Overdue)
    return overdue + routineTags(reminders).map { RoutineFilter.Tag(it) }
}

/** Every tag an open routine wears, most used first; the routines screen's own chips. */
fun routineTags(reminders: List<Reminder>): List<String> =
    rankTags(reminders.filter { it.isRoutine && it.status != Status.DONE })
