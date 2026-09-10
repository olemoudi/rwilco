@file:UseSerializers(DayOfWeekSerializer::class, LocalTimeSerializer::class)

package dev.rwilco.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/*
 * Contacts — "keep in touch": the people you would stop speaking to if nobody kept count.
 *
 * A contact is a routine ([Recurrence.Since], so [Reminder.isRoutine] holds) wearing a
 * [ContactKind] and a [Closeness]. Everything a routine does it does too — the count since the
 * last time, "hablado" being now, a pause freezing the count — and three things are its own:
 *
 * - **Nothing else triggers it.** Only its cadence. No place, no clock, no question: asking
 *   "¿has llamado a tu madre?" at the door of the supermarket is not the shape of this.
 * - **How often, on which days and in which stretch of them come from Settings** — one
 *   [ContactSchedule] per kind — and follow Settings when they change there, except whatever was
 *   changed by hand on the contact itself ([Reminder.contactCadenceByHand],
 *   [Reminder.contactDays], [Reminder.contactWindow]).
 * - **It is told at a drawn moment, and one of each kind a day at most** ([contactQueue]). Every
 *   window on a day gets a moment drawn inside it; at that moment the contacts that are due and
 *   whose window holds it are drawn between — the close before the sporadic, the one told about
 *   longest ago first, chance after that — and when a work window and a personal one coincide,
 *   the first of their two moments goes to whoever ranks first and the second to the other kind.
 *
 * The queue is worked out and never written down: it is a function of the whole set, and a
 * stored answer would be a second truth to keep in step with "hablado".
 */

/** Which half of a life somebody belongs to. What decides the Settings they are told by. */
enum class ContactKind { WORK, PERSONAL }

/**
 * How close somebody is: somebody you really are close to, or somebody to keep up with now and
 * then — at least once a year. Decides how often they come round, and who goes first.
 */
enum class Closeness { CLOSE, DISTANT }

/**
 * How one kind of contact is told about, as Settings has it: on which [days], inside which
 * [window] of them, and every how many months somebody close and somebody sporadic comes round.
 */
@Serializable
data class ContactSchedule(
    val days: Set<DayOfWeek>,
    val window: DayWindow,
    val closeMonths: Int,
    val distantMonths: Int,
)

/** What the month steppers in Settings run between. */
val CONTACT_MONTHS: IntRange = 1..24

/** Wednesday mornings; three months for somebody close, five for the rest. The owner's numbers. */
val DEFAULT_WORK_CONTACTS: ContactSchedule = ContactSchedule(
    days = setOf(DayOfWeek.WEDNESDAY),
    window = DayWindow(LocalTime.of(9, 0), LocalTime.of(12, 0)),
    closeMonths = 3,
    distantMonths = 5,
)

/** Friday and Saturday evenings; two months for a close friend, five for the rest. */
val DEFAULT_PERSONAL_CONTACTS: ContactSchedule = ContactSchedule(
    days = setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
    window = DayWindow(LocalTime.of(17, 0), LocalTime.of(19, 0)),
    closeMonths = 2,
    distantMonths = 5,
)

/**
 * A week, in whole days: how long a telling nobody answered keeps its contact out of the draw,
 * and how far "posponer 1 semana" puts one off. Counted from the start of the day it was told on,
 * so it is back for that weekday's window whichever minute the draw lands on there.
 */
const val CONTACT_RETURN_DAYS = 7L

/** How far ahead turns are worked out. A turn more than a year away is not one worth naming. */
const val CONTACT_HORIZON_DAYS = 366

/**
 * How a contact is always told about: a card in the shade and nothing else.
 *
 * Not read from the row's actions, and there is no way to change it — "discreet, never a sound"
 * is what this feature *is*, not a preference inside it. A row that carried FULL_SCREEN before it
 * became a contact, or one restored from a vault, cannot take the screen.
 */
val CONTACT_PLAN: FiringPlan = FiringPlan(fullScreen = false, notification = true, sound = false, vibrate = false)

/** What Settings say about one kind. */
fun AppSettings.contactScheduleOf(kind: ContactKind): ContactSchedule = when (kind) {
    ContactKind.WORK -> workContacts
    ContactKind.PERSONAL -> personalContacts
}

fun ContactSchedule.monthsFor(closeness: Closeness): Int = when (closeness) {
    Closeness.CLOSE -> closeMonths
    Closeness.DISTANT -> distantMonths
}

/** The cadence Settings give somebody this close, as the span a routine counts. */
fun ContactSchedule.cadenceFor(closeness: Closeness): Recurrence.Since =
    Recurrence.Since(monthsFor(closeness), RecurrenceUnit.MONTHS)

/** Whether this reminder is a contact: a routine that belongs to somebody. */
val Reminder.isContact: Boolean get() = isRoutine && contactKind != null

/** How close, with a contact written before the question existed (0.117.0) read as close. */
val Reminder.closeness: Closeness get() = contactCloseness ?: Closeness.CLOSE

/** The days this contact is told on: its own, when somebody set them by hand, or its kind's. */
fun Reminder.contactDaysIn(schedule: ContactSchedule): Set<DayOfWeek> = contactDays ?: schedule.days

/** And the stretch of them, the same way. */
fun Reminder.contactWindowIn(schedule: ContactSchedule): DayWindow = contactWindow ?: schedule.window

/**
 * This contact with its cadence rewritten to what [schedule] says now, or null when there is
 * nothing to rewrite: not a contact, a cadence changed by hand, or one already in step. Where the
 * count starts and the hour it lands on stay as they were — only how often changes.
 */
fun Reminder.withCadenceFrom(schedule: ContactSchedule): Reminder? {
    if (!isContact || contactCadenceByHand) return null
    val since = recurrence as? Recurrence.Since ?: return null
    val months = schedule.monthsFor(closeness)
    if (since.amount == months && since.unit == RecurrenceUnit.MONTHS) return null
    return copy(recurrence = since.copy(amount = months, unit = RecurrenceUnit.MONTHS))
}

/**
 * Every contact that follows Settings and no longer says what they say, rewritten.
 *
 * What makes "change it in Settings and it changes for every contact" true. The cadence lives in
 * the row, where everything that reads a routine's span already looks, so a change in Settings is
 * carried into the rows rather than read around them — and one changed by hand is left alone.
 */
fun contactsOutOfStep(reminders: List<Reminder>, schedules: (ContactKind) -> ContactSchedule): List<Reminder> =
    reminders.mapNotNull { reminder -> reminder.contactKind?.let { reminder.withCadenceFrom(schedules(it)) } }

/** One contact as the draw sees it. */
private class Waiting(
    val id: String,
    val kind: ContactKind,
    val closeness: Closeness,
    val days: Set<DayOfWeek>,
    val window: DayWindow,
    /** The earliest it may be told: its cadence up, a put-off over, a week past a telling ignored. */
    val dueFrom: Instant,
    /** When it was last told about — never told, when its count began. "Longest waiting" is this. */
    val lastTold: Instant,
)

/** A turn handed out on the day being walked. */
private class Turn(val contact: Waiting, val at: Instant)

/**
 * When each contact is told about next: id to the moment.
 *
 * Walked a day at a time from today. For each kind not yet told about that day, every window one
 * of its contacts is told in gets a moment drawn inside it, and the moments are tried in order: at
 * each, the contacts due by then whose own window holds it are drawn between ([drawOrder]), and
 * the first of them is that kind's turn for the day. **One of each kind a day, at most.** Two
 * kinds whose windows coincide therefore get one each, and [inOwnersOrder] hands the earlier of
 * the two moments to whoever ranks first: a close friend before a close colleague, a close
 * colleague before a sporadic friend.
 *
 * A turn is always **ahead of [now]**: a moment already gone was somebody's, or nobody's, and
 * handing it out again is how two contacts would land in a day built for one. A kind already told
 * about today ([Reminder.lastFiredAt]) has no turn left today either. Past the first turn the walk
 * assumes each telling gets answered — what it names for next week is a forecast, and every ring
 * re-arms from the rows as they really are.
 */
fun contactQueue(
    reminders: List<Reminder>,
    now: Instant,
    zone: ZoneId,
    schedules: (ContactKind) -> ContactSchedule,
    dayStart: LocalTime = DEFAULT_DAY_START,
): Map<String, Instant> {
    val waiting = reminders.mapNotNull { it.inTheDraw(now, zone, schedules, dayStart) }
    if (waiting.isEmpty()) return emptyMap()
    val today = now.atZone(zone).toLocalDate()
    val toldToday = reminders.mapNotNullTo(HashSet()) { reminder ->
        reminder.contactKind?.takeIf { reminder.lastFiredAt?.atZone(zone)?.toLocalDate() == today }
    }
    val turns = HashMap<String, Instant>()
    for (offset in 0..CONTACT_HORIZON_DAYS) {
        if (turns.size == waiting.size) break
        val day = today.plusDays(offset.toLong())
        val order = drawOrder(day)
        val picked = ArrayList<Turn>(ContactKind.entries.size)
        for (kind in ContactKind.entries) {
            if (offset == 0 && kind in toldToday) continue
            val own = waiting.filter { it.kind == kind && it.id !in turns && day.dayOfWeek in it.days }
            if (own.isEmpty()) continue
            val moments = own.mapTo(LinkedHashSet()) { it.window }
                .map { momentIn(it, day, kind, zone) }
                .filter { it > now }
                .sorted()
            for (moment in moments) {
                val chosen = own
                    .filter { it.dueFrom <= moment && it.window.holds(moment, day, zone) }
                    .minWithOrNull(order) ?: continue
                picked += Turn(chosen, moment)
                break
            }
        }
        for (turn in inOwnersOrder(picked, day, zone, order)) turns[turn.contact.id] = turn.at
    }
    return turns
}

/** This contact as the draw sees it, or null when it is not in the draw at all. */
private fun Reminder.inTheDraw(
    now: Instant,
    zone: ZoneId,
    schedules: (ContactKind) -> ContactSchedule,
    dayStart: LocalTime,
): Waiting? {
    val kind = contactKind
    // Resting, waiting at a place, or not active: nothing is owed, and holding a turn for it
    // would keep somebody else waiting for nothing.
    if (!isContact || kind == null || status != Status.ACTIVE || pausedAt != null || snoozedToPlace != null) return null
    val schedule = schedules(kind)
    val days = contactDaysIn(schedule).takeIf { it.isNotEmpty() } ?: return null
    // A window with no length has no moment in it (and [DayWindow.on] would read it as a whole day).
    val window = contactWindowIn(schedule).takeIf { it.from != it.to } ?: return null
    val deadline = routineDeadline(zone, dayStart) ?: return null
    val putOff = snoozedUntil?.takeIf { it > now }
    // Told about and not answered: it had its turn, and sits the week out.
    val ignored = lastFiredAt?.takeIf { awaitingAnswer(now) }?.let { startOfDayAfter(it, CONTACT_RETURN_DAYS, zone) }
    return Waiting(
        id = id,
        kind = kind,
        closeness = closeness,
        days = days,
        window = window,
        dueFrom = listOfNotNull(deadline, putOff, ignored).max(),
        lastTold = lastFiredAt ?: routineAnchor(),
    )
}

/**
 * Who is drawn first on [day]: the close before the sporadic; a personal contact before a work
 * one (which only ever decides anything between two kinds, in [inOwnersOrder]); the one told about
 * longest ago; and then chance — drawn from the id and the day, so it holds still for as long as
 * the day does and a re-arm does not move a turn about for no reason anybody could see.
 */
private fun drawOrder(day: LocalDate): Comparator<Waiting> =
    compareBy<Waiting>(
        { it.closeness.ordinal },
        { if (it.kind == ContactKind.PERSONAL) 0 else 1 },
        { it.lastTold },
    )
        .thenBy { RandomDraw.SplitMix64(RandomDraw.seed(it.id, day.toEpochDay(), Period.DAY)).nextLong() }
        .thenBy { it.id }

/**
 * A day's turns, one of each kind at most, with the owner's order laid over a pair whose windows
 * coincide: the earlier moment to whoever ranks first. Only when each window holds both moments —
 * two different stretches are two different draws — and only when the one moving up is due by
 * the earlier moment.
 */
private fun inOwnersOrder(turns: List<Turn>, day: LocalDate, zone: ZoneId, order: Comparator<Waiting>): List<Turn> {
    if (turns.size != 2) return turns
    val (early, late) = turns.sortedBy { it.at }
    if (order.compare(early.contact, late.contact) <= 0) return turns
    val coincide = early.contact.window.holds(late.at, day, zone) && late.contact.window.holds(early.at, day, zone)
    if (!coincide || late.contact.dueFrom > early.at) return turns
    return listOf(Turn(late.contact, early.at), Turn(early.contact, late.at))
}

/**
 * A minute of [window] on [day], drawn from the kind, the window and the day: the same moment on
 * every pass, a different one each day, and a different one for each kind when two coincide.
 */
private fun momentIn(window: DayWindow, day: LocalDate, kind: ContactKind, zone: ZoneId): Instant {
    val span = window.on(day)
    val minutes = Duration.between(span.from, span.to).toMinutes().toInt()
    val seed = RandomDraw.seed("contact:${kind.name}:${window.from}-${window.to}", day.toEpochDay(), Period.DAY)
    val offset = if (minutes <= 0) 0 else RandomDraw.SplitMix64(seed).nextInt(minutes)
    return span.from.plusMinutes(offset.toLong()).atZone(zone).toInstant()
}

/** Whether [moment] falls inside this window as laid on [day]. */
private fun DayWindow.holds(moment: Instant, day: LocalDate, zone: ZoneId): Boolean {
    val span = on(day)
    val at = moment.atZone(zone).toLocalDateTime()
    return !at.isBefore(span.from) && at.isBefore(span.to)
}

private fun startOfDayAfter(at: Instant, days: Long, zone: ZoneId): Instant =
    at.atZone(zone).toLocalDate().plusDays(days).atStartOfDay(zone).toInstant()

/**
 * "Posponer 1 semana": until the start of the day a week after the telling being answered — so
 * it is back for that same weekday's window, which is what "el miércoles siguiente" means — or a
 * week from today when there is no telling to answer (or that week has already gone by).
 */
fun Reminder.contactPutOffUntil(now: Instant, zone: ZoneId): Instant {
    val fromTelling = lastFiredAt?.takeIf { awaitingAnswer(now) }?.let { startOfDayAfter(it, CONTACT_RETURN_DAYS, zone) }
    return fromTelling?.takeIf { it > now } ?: startOfDayAfter(now, CONTACT_RETURN_DAYS, zone)
}

/**
 * When the safety net says its word about a contact told at [rang]: the next day, at the same
 * time on the clock — across a change of the clocks too, which twenty-four hours would not be.
 */
fun contactNetAt(rang: Instant, zone: ZoneId): Instant = rang.atZone(zone).plusDays(1).toInstant()

/**
 * Whether this contact was told about and nobody has said anything — the predicate Home hangs off.
 *
 * Deliberately **not** "the cadence has run out": one waiting its turn is being paced, and showing
 * everybody due on Home the day they come due would hand back the very pile the draw spreads out.
 * And not after a "posponer" either, even once its week is up: that was an answer, and what brings
 * the contact back is the next telling, not the calendar.
 */
fun Reminder.contactOwed(now: Instant): Boolean = isContact && awaitingAnswer(now) && snoozedUntil == null

/** The contacts told about and left unanswered, the one that rang longest ago first. Home's line. */
fun overdueContacts(reminders: List<Reminder>, now: Instant): List<Reminder> = reminders
    .filter { it.contactOwed(now) }
    .sortedWith(compareBy({ it.lastFiredAt }, { it.createdAt }))
