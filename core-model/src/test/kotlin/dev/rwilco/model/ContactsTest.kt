package dev.rwilco.model

import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * A contact: somebody you would drift away from if nobody kept count.
 *
 * The count is a routine's. What is this feature's own is where the telling lands — never at the
 * deadline, always at the next free weekly slot — and that a slot holds one person, which is what
 * makes "two of each a week" true without a rule saying so anywhere.
 */
class ContactsTest {

    private val dayStart: LocalTime = LocalTime.of(9, 0)

    /** A Monday, so the week's slots are all still ahead. 2026-08-31 09:00 Madrid. */
    private val monday: Instant = local(2026, 8, 31, 9, 0)

    private val workSlots = DEFAULT_WORK_SLOTS
    private val personalSlots = DEFAULT_PERSONAL_SLOTS

    private fun slots(kind: ContactKind) = when (kind) {
        ContactKind.WORK -> workSlots
        ContactKind.PERSONAL -> personalSlots
    }

    private fun contact(
        id: String,
        kind: ContactKind = ContactKind.WORK,
        every: Int = 8,
        unit: RecurrenceUnit = RecurrenceUnit.WEEKS,
        lastDealtAt: Instant? = null,
        createdAt: Instant = monday.minus(120, ChronoUnit.DAYS),
        status: Status = Status.ACTIVE,
        pausedAt: Instant? = null,
        snoozedUntil: Instant? = null,
        lastFiredAt: Instant? = null,
    ) = Reminder(
        id = id,
        text = id,
        recurrence = Recurrence.Since(every, unit),
        contactKind = kind,
        status = status,
        createdAt = createdAt,
        updatedAt = createdAt,
        lastDealtAt = lastDealtAt,
        pausedAt = pausedAt,
        snoozedUntil = snoozedUntil,
        lastFiredAt = lastFiredAt,
    )

    private fun queue(vararg contacts: Reminder, now: Instant = monday) =
        contactQueue(contacts.toList(), now, zone, ::slots, dayStart)

    private fun dayOf(at: Instant) = at.atZone(zone).toLocalDate()

    private fun timeOf(at: Instant) = at.atZone(zone).toLocalTime()

    @Test
    fun `a contact is a routine that belongs to somebody`() {
        val ana = contact("ana")
        assertTrue(ana.isRoutine, "a contact counts time like any routine")
        assertTrue(ana.isContact)
        assertFalse(ana.copy(contactKind = null).isContact)
        // A plain reminder wearing a kind is still not one: the count is what a contact is.
        assertFalse(ana.copy(recurrence = Recurrence.None).isContact)
    }

    @Test
    fun `the cadence lands within a tenth either way, and holds still while the round does`() {
        val ana = contact("ana", every = 10)
        val plain = ana.routineDeadline(zone, dayStart)!!
        val shaken = ana.contactDeadline(zone, dayStart)!!
        val span = Duration.between(ana.routineAnchor(), plain).seconds
        val moved = Duration.between(plain, shaken).seconds
        assertTrue(Math.abs(moved) <= span * CONTACT_JITTER_PERCENT / 100, "moved $moved of $span")
        // Asked twice is answered twice the same, or the alarm would walk about on every recompute.
        assertEquals(shaken, ana.contactDeadline(zone, dayStart))
    }

    @Test
    fun `two contacts on the same cadence are not shaken the same way`() {
        val ana = contact("ana", every = 8)
        val beto = contact("beto", every = 8)
        assertEquals(ana.routineDeadline(zone, dayStart), beto.routineDeadline(zone, dayStart))
        assertNotEquals(ana.contactDeadline(zone, dayStart), beto.contactDeadline(zone, dayStart))
    }

    @Test
    fun `a hablado draws the shake afresh`() {
        val ana = contact("ana")
        val before = ana.contactDeadline(zone, dayStart)
        val after = ana.copy(lastDealtAt = monday).contactDeadline(zone, dayStart)
        // Not merely different because the anchor moved: the *offset* from the plain landing is.
        val offsetBefore = Duration.between(ana.routineDeadline(zone, dayStart), before)
        val done = ana.copy(lastDealtAt = monday)
        val offsetAfter = Duration.between(done.routineDeadline(zone, dayStart), after)
        assertNotEquals(offsetBefore, offsetAfter)
    }

    @Test
    fun `a work contact is told on a work day, inside the window`() {
        val turn = queue(contact("ana")).getValue("ana")
        assertTrue(dayOf(turn).dayOfWeek in setOf(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY), "on ${dayOf(turn)}")
        assertTrue(timeOf(turn) >= LocalTime.of(9, 0) && timeOf(turn) < LocalTime.of(12, 0), "at ${timeOf(turn)}")
    }

    @Test
    fun `a personal contact is told on a friday or a saturday, inside its window`() {
        val turn = queue(contact("mama", kind = ContactKind.PERSONAL)).getValue("mama")
        val day = dayOf(turn).dayOfWeek
        assertTrue(day in setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY), "on $day")
        val window = if (day == DayOfWeek.FRIDAY) LocalTime.of(16, 0) to LocalTime.of(19, 0) else LocalTime.of(12, 0) to LocalTime.of(19, 0)
        assertTrue(timeOf(turn) >= window.first && timeOf(turn) < window.second, "at ${timeOf(turn)}")
    }

    @Test
    fun `a slot holds one person`() {
        val turns = queue(contact("ana"), contact("beto"), contact("carlos"), contact("dani"))
        assertEquals(4, turns.size)
        assertEquals(4, turns.values.map { dayOf(it) }.distinct().size, "four contacts, four different days")
    }

    @Test
    fun `two of a kind a week is what the slots make true`() {
        val five = (1..5).map { contact("c$it") }
        val turns = contactQueue(five, monday, zone, ::slots, dayStart)
        val weeks = turns.values.groupingBy { dayOf(it).with(DayOfWeek.MONDAY) }.eachCount()
        assertTrue(weeks.values.all { it <= 2 }, "never more than two of a kind a week: $weeks")
        // Five people, two openings a week: the last one is three weeks out.
        val first = turns.values.min()
        val last = turns.values.max()
        assertEquals(2L, ChronoUnit.WEEKS.between(dayOf(first).with(DayOfWeek.MONDAY), dayOf(last).with(DayOfWeek.MONDAY)))
    }

    @Test
    fun `work and personal do not take slots from each other`() {
        val turns = queue(
            contact("ana"), contact("beto"),
            contact("mama", kind = ContactKind.PERSONAL), contact("papa", kind = ContactKind.PERSONAL),
        )
        assertEquals(4, turns.size, "two of each fit in one week")
        assertTrue(turns.values.all { dayOf(it) < dayOf(monday).plusDays(7) }, "all inside the week: $turns")
    }

    @Test
    fun `the one that has waited longest goes first`() {
        // Beto is a round further behind, so he takes Wednesday whatever the ids say.
        val ana = contact("ana", lastDealtAt = monday.minus(60, ChronoUnit.DAYS))
        val beto = contact("beto", lastDealtAt = monday.minus(120, ChronoUnit.DAYS))
        val turns = queue(ana, beto)
        assertTrue(turns.getValue("beto") < turns.getValue("ana"))
    }

    @Test
    fun `a contact told about and ignored comes back next week, and first`() {
        // Rang on the Wednesday, nobody said anything. Not tomorrow's opening — it had its turn
        // this week — and not the week after that either: the Wednesday following, ahead of
        // somebody less overdue, because its anchor never moved.
        val rang = local(2026, 9, 2, 10, 15)
        val ana = contact("ana", lastDealtAt = monday.minus(120, ChronoUnit.DAYS), lastFiredAt = rang)
        val beto = contact("beto", lastDealtAt = monday.minus(20, ChronoUnit.DAYS))
        val thursday = local(2026, 9, 3, 8, 0)
        val turns = contactQueue(listOf(ana, beto), thursday, zone, ::slots, dayStart)
        assertTrue(ana.contactOwed(thursday), "it rang and nobody answered")
        assertEquals(DayOfWeek.WEDNESDAY, dayOf(turns.getValue("ana")).dayOfWeek)
        assertEquals(local(2026, 9, 7, 0, 0), dayOf(turns.getValue("ana")).with(DayOfWeek.MONDAY).atStartOfDay(zone).toInstant(), "the week after")
        assertTrue(turns.getValue("ana") < turns.getValue("beto"), "and ahead of beto")
    }

    @Test
    fun `an opening that has already opened is never handed to anybody`() {
        // The bug this guards: marking ana done at ten past nine on a Wednesday recomputes the
        // queue, and beto would inherit this morning's opening — a moment already gone, rung at
        // once, two people in a slot built for one.
        val wednesday = local(2026, 9, 2, 9, 10)
        val turns = contactQueue(
            listOf(contact("ana"), contact("beto")), wednesday, zone, ::slots, dayStart,
        )
        assertTrue(turns.values.all { it > wednesday }, "every turn is ahead of now: $turns")
        assertTrue(turns.values.none { dayOf(it) == dayOf(wednesday) }, "this morning is spent")
    }

    @Test
    fun `a contact resting takes no slot at all`() {
        val ana = contact("ana", status = Status.PAUSED, pausedAt = monday.minusSeconds(3600))
        val beto = contact("beto")
        val turns = queue(ana, beto)
        assertNull(turns["ana"], "nothing is owed while it rests")
        assertEquals(DayOfWeek.WEDNESDAY, dayOf(turns.getValue("beto")).dayOfWeek, "and beto gets the first opening")
    }

    @Test
    fun `a contact put off takes no slot either`() {
        val ana = contact("ana", snoozedUntil = monday.plus(30, ChronoUnit.DAYS))
        assertNull(queue(ana)["ana"], "not now was said about this very thing")
    }

    @Test
    fun `a turn still ahead is not something to be red about`() {
        val ana = contact("ana")
        assertFalse(ana.contactOwed(monday), "waiting its turn is not being overdue")
        assertTrue(overdueContacts(listOf(ana), monday).isEmpty())
        // Told about, and nobody said anything: that, and only that, is what Home shows.
        val rung = ana.copy(lastFiredAt = monday.plus(2, ChronoUnit.DAYS))
        val after = monday.plus(3, ChronoUnit.DAYS)
        assertTrue(rung.contactOwed(after))
        assertEquals(listOf(rung), overdueContacts(listOf(rung), after))
        // And answered, it drops off again.
        assertFalse(rung.copy(lastDealtAt = after).contactOwed(after))
    }

    @Test
    fun `a contact never reaches the routines own line on Home`() {
        // Its plazo running out is not red; the routines' surfaces must not claim it.
        val ana = contact("ana", lastDealtAt = monday.minus(200, ChronoUnit.DAYS))
        assertTrue(ana.routineOwed(monday, zone, dayStart), "as a routine it would be owed")
        assertTrue(overdueRoutines(listOf(ana), monday, zone, dayStart).isEmpty())
        assertNull(nextDueRoutine(listOf(contact("beto")), monday, zone, dayStart))
    }

    @Test
    fun `more contacts than a year of slots ends, and leaves the rest unnamed`() {
        // Two openings a week, fifty-two weeks looked at: past that there is no turn to name.
        val many = (1..120).map { contact("c$it") }
        val turns = contactQueue(many, monday, zone, ::slots, dayStart)
        assertTrue(turns.size in 104..106, "a year of openings, and no more: ${turns.size}")
        assertTrue(turns.values.all { it < monday.plus(MAX_QUEUE_WEEKS * 7L + 7, ChronoUnit.DAYS) })
    }

    @Test
    fun `a kind with no openings is not raised at all`() {
        val turns = contactQueue(
            listOf(contact("ana"), contact("mama", kind = ContactKind.PERSONAL)),
            monday, zone,
            { kind -> if (kind == ContactKind.WORK) emptyList() else personalSlots },
            dayStart,
        )
        assertNull(turns["ana"], "no opening, no telling")
        assertTrue(turns.containsKey("mama"))
    }

    @Test
    fun `a contact is never raised before its cadence is up`() {
        // A deadline landing mid-window takes what is left of that window, not the whole of it.
        val ana = contact("ana", every = 1, unit = RecurrenceUnit.WEEKS, lastDealtAt = local(2026, 8, 26, 10, 30))
        val turn = queue(ana).getValue("ana")
        assertTrue(turn >= ana.contactDeadline(zone, dayStart)!!, "told at $turn, due ${ana.contactDeadline(zone, dayStart)}")
    }

    @Test
    fun `an ordinary routine is left out of the queue entirely`() {
        val plants = Reminder(
            id = "plants", text = "Regar",
            recurrence = Recurrence.Since(3, RecurrenceUnit.DAYS),
            createdAt = monday.minus(30, ChronoUnit.DAYS), updatedAt = monday.minus(30, ChronoUnit.DAYS),
        )
        assertTrue(queue(plants).isEmpty())
        assertNull(plants.contactDeadline(zone, dayStart))
    }
}
