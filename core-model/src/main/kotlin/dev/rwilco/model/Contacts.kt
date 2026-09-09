@file:UseSerializers(DayOfWeekSerializer::class)

package dev.rwilco.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/*
 * Contacts: the people you would stop speaking to if nobody kept count.
 *
 * A contact is a routine ([Recurrence.Since], so [Reminder.isRoutine] holds) wearing a
 * [ContactKind]. Everything a routine does it does too — the count since the last time, "hecho"
 * being now, a pause freezing the count — and two things are its own:
 *
 * - **Nothing else triggers it.** Only "cada X semanas / X meses". No place, no clock, no
 *   question: asking "¿has llamado a tu madre?" at the door of the supermarket is not the shape
 *   of this.
 * - **It is told at a slot, not at its deadline.** Nobody wants to be asked about a colleague on
 *   a Sunday night or about a friend on a Tuesday morning, so a kind owns a handful of weekly
 *   [ContactSlot]s — a weekday and a stretch of it — and the deadline is rounded forward to the
 *   next free one. One contact per slot, which *is* the weekly budget: two of each kind a week,
 *   because that is how many slots there are. What does not fit waits.
 *
 * The queue is worked out and never written down ([contactQueue]): it is a function of the whole
 * set, and a stored answer would be a second truth to keep in step with "hecho". The behaviour
 * the owner asked for — a contact you ignore comes back next week and goes first — is not a rule
 * here. It falls out of the order: ignoring it leaves its anchor where it was, so next week it is
 * still the one that has waited longest and takes the first slot going.
 */

/** Which half of a life somebody belongs to. What decides the slots they are told at. */
enum class ContactKind { WORK, PERSONAL }

/**
 * One weekly opening: a day, and the stretch of it a contact may be raised in. The moment inside
 * it is drawn, never fixed — a contact is not an appointment, and the same minute every week
 * would read as one.
 */
@Serializable
data class ContactSlot(val day: DayOfWeek, val window: DayWindow)

/** Mornings on the two days work is asked about; the owner's default, and settings' to change. */
val DEFAULT_WORK_SLOTS: List<ContactSlot> = listOf(
    ContactSlot(DayOfWeek.WEDNESDAY, DayWindow(LocalTime.of(9, 0), LocalTime.of(12, 0))),
    ContactSlot(DayOfWeek.THURSDAY, DayWindow(LocalTime.of(9, 0), LocalTime.of(12, 0))),
)

/** Friday evening and Saturday afternoon: when somebody has time to actually ring a friend. */
val DEFAULT_PERSONAL_SLOTS: List<ContactSlot> = listOf(
    ContactSlot(DayOfWeek.FRIDAY, DayWindow(LocalTime.of(16, 0), LocalTime.of(19, 0))),
    ContactSlot(DayOfWeek.SATURDAY, DayWindow(LocalTime.of(12, 0), LocalTime.of(19, 0))),
)

/**
 * How far either way a cadence is allowed to land, in percent.
 *
 * Two contacts written the same day at "cada 8 semanas" would come due together for ever, and
 * with one slot each week that means one of them is always a week late. A tenth is enough to
 * shake them apart within a couple of rounds and small enough that "cada 8 semanas" is still
 * true.
 */
const val CONTACT_JITTER_PERCENT = 10

/**
 * How far ahead the queue is worked out. With more contacts than slots the walk would otherwise
 * not end, and a turn more than a year away is not a turn — it is a sign there are too many
 * contacts, which the screen says rather than hides.
 */
const val MAX_QUEUE_WEEKS = 52

/**
 * How a contact is always told about: a card in the shade and nothing else.
 *
 * Not read from the row's actions, and there is no way to change it — "discreet, never a sound"
 * is what this feature *is*, not a preference inside it. A row that carried FULL_SCREEN before it
 * became a contact, or one restored from a vault, cannot take the screen.
 */
val CONTACT_PLAN: FiringPlan = FiringPlan(fullScreen = false, notification = true, sound = false, vibrate = false)

/** The openings a kind is told at, as this person has drawn them. */
fun AppSettings.contactSlotsOf(kind: ContactKind): List<ContactSlot> = when (kind) {
    ContactKind.WORK -> workContactSlots
    ContactKind.PERSONAL -> personalContactSlots
}

/** Whether this reminder is a contact: a routine that belongs to somebody. */
val Reminder.isContact: Boolean get() = isRoutine && contactKind != null

/**
 * The cadence's landing for this round, shaken by up to [CONTACT_JITTER_PERCENT] either way.
 *
 * Drawn from the id and the anchor, so it holds still for as long as the round does and is drawn
 * afresh the moment "hablado" moves the anchor — which is what makes two contacts on the same
 * cadence drift apart instead of marching in step. No counter is kept: the anchor is the round.
 */
fun Reminder.contactDeadline(zone: ZoneId, dayStart: LocalTime = DEFAULT_DAY_START): Instant? {
    if (!isContact) return null
    val anchor = routineAnchor()
    val plain = routineDeadline(zone, dayStart) ?: return null
    val span = Duration.between(anchor, plain).seconds
    if (span <= 0L) return plain
    // Tenths of a percent, so the shake has room to be different between two contacts and still
    // reads as the cadence somebody asked for.
    val tenths = CONTACT_JITTER_PERCENT * 10
    val draw = RandomDraw.SplitMix64(RandomDraw.seed(id, anchor.epochSecond / SECONDS_PER_DAY, Period.DAY))
        .nextInt(tenths * 2 + 1) - tenths
    return anchor.plusSeconds(span + span * draw / 1000L)
}

/**
 * Whether a contact is somebody the queue should be finding a slot for: active, not put off, and
 * not resting. A paused contact takes no slot at all — its count is frozen, so it is not owed,
 * and holding a slot for it would keep somebody else waiting for nothing.
 */
private fun Reminder.contactInQueue(now: Instant): Boolean =
    status == Status.ACTIVE && !routinePutOff(now) && pausedAt == null

/**
 * The earliest a telling may land for this contact.
 *
 * Its deadline, normally. But one already told about and not answered ([awaitingAnswer]) is not
 * up for another opening this week — it had one — so its floor is the Monday after the week it
 * rang in. That is the whole of "it comes back next week": no counter, no retry field, just a
 * floor. And because its anchor never moved it is still the one that has waited longest, so it
 * takes that week's first opening, which is the other half of what the owner asked for.
 */
private fun Reminder.contactFloor(deadline: Instant, now: Instant, zone: ZoneId): Instant {
    val rang = lastFiredAt?.takeIf { awaitingAnswer(now) } ?: return deadline
    return rang.atZone(zone).toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant()
}

/**
 * When each contact's turn comes: id to the moment it will be raised.
 *
 * The whole of the slot logic, in one pure function of the whole set, because that is what it is
 * a function of — one contact's turn depends on everybody else's. Per kind: take everybody in the
 * queue, oldest deadline first, and hand out the openings in order, one contact to a slot. A
 * contact whose turn falls past [MAX_QUEUE_WEEKS] is left out of the map; there is no slot to
 * name for it yet.
 *
 * Every turn it names is **ahead of [now]**, always: an opening that has already opened belongs
 * to whoever it was handed to this morning, and handing it out again is the one way this could
 * put two people in a slot built for one. A telling the phone slept through is not re-slotted
 * either — it stays armed and is delivered late by the catch-up, the way every other missed
 * moment in the app is.
 */
fun contactQueue(
    contacts: List<Reminder>,
    now: Instant,
    zone: ZoneId,
    slots: (ContactKind) -> List<ContactSlot>,
    dayStart: LocalTime = DEFAULT_DAY_START,
): Map<String, Instant> {
    val turns = HashMap<String, Instant>()
    for (kind in ContactKind.entries) {
        val openings = slots(kind).filter { it.window.from != it.window.to }
        if (openings.isEmpty()) continue
        val waiting = contacts
            .filter { it.isContact && it.contactKind == kind && it.contactInQueue(now) }
            .mapNotNull { contact -> contact.contactDeadline(zone, dayStart)?.let { contact to it } }
            // Longest waiting first. The id is the last word, not the tie-break of a whim: two
            // contacts due the same second must be handed their openings the same way on every
            // pass, or the alarm would move about for no reason anybody could see.
            .sortedWith(compareBy({ it.second }, { it.first.createdAt }, { it.first.id }))
        if (waiting.isEmpty()) continue

        val from = now.atZone(zone).toLocalDate()
        val taken = HashSet<Pair<LocalDate, Int>>()
        for ((contact, deadline) in waiting) {
            val floor = contact.contactFloor(deadline, now, zone)
            var day = from
            var turn: Instant? = null
            var days = 0
            while (turn == null && days <= MAX_QUEUE_WEEKS * 7) {
                for ((index, slot) in openings.withIndex()) {
                    if (slot.day != day.dayOfWeek || (day to index) in taken) continue
                    val window = slot.window.on(day)
                    val opens = window.from.atZone(zone).toInstant()
                    val closes = window.to.atZone(zone).toInstant()
                    // **An opening that has already opened is never handed to anybody.** Without
                    // this, marking one contact done at ten past nine would hand the next one
                    // this morning's opening — a moment in the past, delivered at once, and two
                    // contacts in the slot that was meant to hold one.
                    if (opens <= now || closes < floor) continue
                    taken += day to index
                    turn = drawInside(maxOf(opens, floor), closes, contact.id, day)
                    break
                }
                day = day.plusDays(1)
                days++
            }
            turn?.let { turns[contact.id] = it }
        }
    }
    return turns
}

/** A minute of [opens, closes), drawn from the id and the day so it holds still across recomputes. */
private fun drawInside(opens: Instant, closes: Instant, reminderId: String, day: LocalDate): Instant {
    val minutes = Duration.between(opens, closes).toMinutes().toInt()
    if (minutes <= 0) return opens
    val rng = RandomDraw.SplitMix64(RandomDraw.seed(reminderId, day.toEpochDay(), Period.DAY))
    return opens.plusSeconds(rng.nextInt(minutes) * 60L)
}

/**
 * Whether this contact was told about and nobody has said anything — the predicate Home hangs off.
 *
 * Deliberately **not** "the deadline has passed": a contact the budget has pushed three weeks out
 * is not something to be red about. The budget exists so four people do not land on one week, and
 * showing all four on Home the day they come due would hand back the very pile it spreads out.
 * And deliberately not "its turn is in the past" either — a turn is always ahead by construction
 * ([contactQueue]); what "you have not answered" is written in is the row itself, the same
 * [awaitingAnswer] every other door in the app reads.
 */
fun Reminder.contactOwed(now: Instant): Boolean = isContact && awaitingAnswer(now)

/** The contacts told about and left unanswered, the one that rang longest ago first. Home's line. */
fun overdueContacts(reminders: List<Reminder>, now: Instant): List<Reminder> = reminders
    .filter { it.contactOwed(now) }
    .sortedWith(compareBy({ it.lastFiredAt }, { it.createdAt }))

private const val SECONDS_PER_DAY = 24L * 60 * 60
