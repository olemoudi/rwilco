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
 *
 * **Only for a routine that has been done at least once.** One that never has counts from where
 * it *started*, and that is a different slot — see [recurrenceAfterPause], which is what the
 * resume writes then.
 */
fun Reminder.routineAnchorAfterPause(now: Instant): Instant {
    val paused = pausedAt ?: return routineAnchor()
    return routineAnchor().plus(Duration.between(paused, now).coerceAtLeast(Duration.ZERO))
}

/**
 * The same rest, moved in the slot a routine that has **never been done** counts from: its own
 * start ([Recurrence.Since.startsAt]), pushed forward by exactly the time it rested. The
 * recurrence as it stands for anything else.
 *
 * The resume used to write [routineAnchorAfterPause] into `lastDealtAt` whatever the routine had
 * done, and for one that had done nothing that anchor is `startsAt ?: createdAt` — so a pause and
 * a resume left a "hecho" nobody gave. What it cost was not arithmetic but everything hung off
 * "never done": [routineWaitingToStart] wants `lastDealtAt` null, so "aún no empieza" was lost for
 * good and, with a start still ahead, the row was left counting from an anchor in the future; and
 * `promptQuietUntil` went from null to a tenth of the span, holding the routine's questions quiet
 * over a "hecho" that never happened.
 *
 * A start still ahead of [now] stays ahead of it, which is the point: the month the pause lasted
 * is a month the count did not begin in either.
 */
fun Reminder.recurrenceAfterPause(now: Instant): Recurrence {
    val since = recurrence as? Recurrence.Since ?: return recurrence
    val paused = pausedAt ?: return recurrence
    val rested = Duration.between(paused, now).coerceAtLeast(Duration.ZERO)
    return since.copy(startsAt = (since.startsAt ?: createdAt).plus(rested))
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

/**
 * Whether the routine has been put off — to a clock still ahead, or to a place — which is an
 * answer: "not now" was said about this very thing, and until it comes back the routine is not
 * something owed, however far past its span it is. The row still says so ("pospuesta"), but
 * Home's line, the launcher and the "vencidas" chip leave it alone.
 */
fun Reminder.routinePutOff(now: Instant): Boolean =
    (snoozedUntil?.let { it > now } ?: false) || snoozedToPlace != null

/** The one predicate every "you still owe this" surface hangs off: active, span up, not put off. */
fun Reminder.routineOwed(now: Instant, zone: ZoneId, dayStart: LocalTime = DEFAULT_DAY_START): Boolean =
    status == Status.ACTIVE && !routineDone(now, zone, dayStart) && !routinePutOff(now)

/**
 * The open routines that are owed, the one that has waited longest first. What Home's line says.
 *
 * **Contacts are not among them.** A contact's plazo running out is not something to be red
 * about — it waits its turn in the queue, and Home hears about it only once it has been told
 * about and left unanswered (`Contacts.kt`, [overdueContacts]).
 */
fun overdueRoutines(
    reminders: List<Reminder>,
    now: Instant,
    zone: ZoneId,
    dayStart: LocalTime = DEFAULT_DAY_START,
): List<Reminder> = reminders
    .filter { it.isRoutine && !it.isContact && it.routineOwed(now, zone, dayStart) }
    .sortedWith(compareBy({ it.routineDeadline(zone, dayStart) }, { it.createdAt }))

/**
 * The routine whose span runs out soonest among the ones still inside it — what Home's door
 * says when nothing is owed: "la próxima: regar las plantas, en 3 d". Null when none is coming
 * (none at all, all owed, all paused, or all waiting to start).
 */
fun nextDueRoutine(
    reminders: List<Reminder>,
    now: Instant,
    zone: ZoneId,
    dayStart: LocalTime = DEFAULT_DAY_START,
): Reminder? = reminders
    .filter { it.isRoutine && !it.isContact && it.status == Status.ACTIVE && it.routineDone(now, zone, dayStart) && !it.routineWaitingToStart(now) }
    .minWithOrNull(compareBy({ it.routineDeadline(zone, dayStart) }, { it.createdAt }))

/**
 * What the routines screen shows: everything, only the ones owed, the ones resting, the ones
 * whose count has not begun, or the ones wearing a tag.
 */
sealed interface RoutineFilter {
    data object All : RoutineFilter
    data object Overdue : RoutineFilter
    data object Paused : RoutineFilter
    data object Waiting : RoutineFilter
    /** Only the contacts of one kind: "trabajo", "personales". One member, not two objects. */
    data class Kind(val kind: ContactKind) : RoutineFilter
    data class Tag(val tag: String) : RoutineFilter
}

/**
 * The "this is owed" **the routines screen** hangs off, which for a contact is not its plazo.
 *
 * A contact whose cadence ran out is not late: it is waiting its turn, which is the whole of what
 * the draw is for. Read against the plazo, one written months ago sorted to the top of the list as
 * overdue while its own card read "Sí", and the "vencidas" chip could offer a list made entirely
 * of contacts saying yes — the screen disagreeing with itself in two places at once. What is owed
 * about a contact is what Home already asks: it was told about, and nobody answered.
 */
fun Reminder.listedAsOwed(now: Instant, zone: ZoneId, dayStart: LocalTime = DEFAULT_DAY_START): Boolean =
    if (isContact) contactOwed(now) else routineOwed(now, zone, dayStart)

/** What a row is ordered by: a contact's drawn turn, anything else's plazo. Null when it has none. */
private fun Reminder.listOrder(zone: ZoneId, dayStart: LocalTime, turns: Map<String, Instant>): Instant? =
    if (isContact) turns[id] else routineDeadline(zone, dayStart)

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
    /**
     * When each contact's turn is ([contactQueue]). Handed in rather than worked out here: the
     * queue is a function of the whole set plus the schedules, which this cannot see, and the
     * screen has already asked for it to build the rows. Without it a contact sorts last among
     * the ones not owed, which is also what one with no turn inside the year does.
     */
    turns: Map<String, Instant> = emptyMap(),
): List<Reminder> = reminders
    .filter { it.isRoutine && it.status != Status.DONE }
    .filter { matchesWords(it, query) }
    .filter {
        when (filter) {
            RoutineFilter.All -> true
            RoutineFilter.Overdue -> it.listedAsOwed(now, zone, dayStart)
            RoutineFilter.Paused -> it.status == Status.PAUSED
            RoutineFilter.Waiting -> it.status == Status.ACTIVE && it.routineWaitingToStart(now)
            is RoutineFilter.Kind -> it.contactKind == filter.kind
            is RoutineFilter.Tag -> it.tags.any { tag -> tag.equals(filter.tag, ignoreCase = true) }
        }
    }
    .sortedWith(
        // What is owed first; then the rest inside their plazo and the ones put off (an answer
        // given), by how soon; the paused ones last. **By the same reading the row shows**: a
        // contact by the turn on its card, everything else by its plazo, and anything with
        // neither at the end of its group rather than at the top of it.
        compareBy<Reminder> {
            when {
                it.status != Status.ACTIVE -> 2
                it.listedAsOwed(now, zone, dayStart) -> 0
                else -> 1
            }
        }
            .thenBy { it.listOrder(zone, dayStart, turns) ?: Instant.MAX }
            .thenBy { it.createdAt },
    )

/** Whether [reminder]'s own words answer [query]; a blank query answers itself. */
private fun matchesWords(reminder: Reminder, query: String): Boolean {
    val needle = fold(query)
    return needle.isEmpty() || fuzzyScore(needle, fold(reminder.text)) != null
}

/**
 * The filters worth offering: the app's own "vencidas", "en pausa" and "aún no empieza" only
 * while something is each of those, then the routines' tags.
 */
fun routineFilters(reminders: List<Reminder>, now: Instant, zone: ZoneId, dayStart: LocalTime = DEFAULT_DAY_START): List<RoutineFilter> {
    val routines = reminders.filter { it.isRoutine && it.status != Status.DONE }
    val own = listOfNotNull(
        RoutineFilter.Overdue.takeIf { routines.any { it.listedAsOwed(now, zone, dayStart) } },
        RoutineFilter.Paused.takeIf { routines.any { it.status == Status.PAUSED } },
        RoutineFilter.Waiting.takeIf { routines.any { it.status == Status.ACTIVE && it.routineWaitingToStart(now) } },
    ) + ContactKind.entries.mapNotNull { kind -> RoutineFilter.Kind(kind).takeIf { routines.any { r -> r.contactKind == kind } } }
    return own + routineTags(reminders).map { RoutineFilter.Tag(it) }
}

/** Every tag an open routine wears, most used first; the routines screen's own chips. */
fun routineTags(reminders: List<Reminder>): List<String> =
    rankTags(reminders.filter { it.isRoutine && it.status != Status.DONE })
