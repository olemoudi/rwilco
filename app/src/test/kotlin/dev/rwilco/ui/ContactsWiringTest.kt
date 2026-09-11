package dev.rwilco.ui

import dev.rwilco.model.Closeness
import dev.rwilco.model.ContactKind
import dev.rwilco.model.ContactSchedule
import dev.rwilco.model.DEFAULT_PERSONAL_CONTACTS
import dev.rwilco.model.DEFAULT_WORK_CONTACTS
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.RoutineFilter
import dev.rwilco.model.contactDeadline
import dev.rwilco.ui.home.buildHomeState
import dev.rwilco.ui.routines.buildRoutinesState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The two screens' side of contacts.
 *
 * The arithmetic is pinned in `ContactsTest` next door; what is pinned here is the wiring, which
 * is where this could go wrong quietly: a contact reaching Home before its turn, a row reading
 * its cadence instead of its turn and drawing a red track over somebody being paced, or the
 * screen arming from different Settings than the scheduler does.
 */
class ContactsWiringTest {

    private val zone = ZoneId.of("Europe/Madrid")

    /** A Monday, so the week's windows are all still ahead. */
    private val now: Instant = LocalDateTime.of(2026, 8, 31, 9, 0).atZone(zone).toInstant()
    private val dayStart = LocalTime.of(9, 0)

    private fun schedules(kind: ContactKind): ContactSchedule =
        if (kind == ContactKind.WORK) DEFAULT_WORK_CONTACTS else DEFAULT_PERSONAL_CONTACTS

    private fun contact(id: String, kind: ContactKind = ContactKind.WORK, lastFiredAt: Instant? = null) = Reminder(
        id = id,
        text = id,
        recurrence = Recurrence.Since(3, RecurrenceUnit.MONTHS),
        contactKind = kind,
        contactCloseness = Closeness.CLOSE,
        createdAt = now.minus(Duration.ofDays(200)),
        updatedAt = now.minus(Duration.ofDays(200)),
        lastFiredAt = lastFiredAt,
    )

    private fun routine(id: String) = Reminder(
        id = id,
        text = id,
        recurrence = Recurrence.Since(21, RecurrenceUnit.DAYS),
        createdAt = now.minus(Duration.ofDays(60)),
        updatedAt = now.minus(Duration.ofDays(60)),
    )

    @Test
    fun `a contact waiting its turn is nowhere on Home`() {
        // Its cadence ran out months ago; the draw is pacing it, and that is not a complaint.
        val state = buildHomeState(listOf(contact("ana")), dayStart, now, zone, null, dayStart)
        assertTrue(state.routines.contacts.isEmpty(), "not yet its turn")
        assertTrue(state.routines.overdue.isEmpty(), "and never on the routines' own line")
        assertNull(state.routines.nextDue, "nor named as the next routine due")
    }

    @Test
    fun `a contact told about and unanswered is on Home, with its kind`() {
        val rang = now.minus(Duration.ofDays(1))
        val state = buildHomeState(listOf(contact("ana", lastFiredAt = rang)), dayStart, now, zone, null, dayStart)
        val row = state.routines.contacts.single()
        assertEquals("ana", row.id)
        assertEquals(ContactKind.WORK, row.kind)
        assertTrue(state.routines.overdue.isEmpty(), "still not one of the routines")
    }

    @Test
    fun `an overdue routine still reaches Home the way it always did`() {
        val state = buildHomeState(listOf(routine("plants"), contact("ana")), dayStart, now, zone, null, dayStart)
        assertEquals(listOf("plants"), state.routines.overdue.map { it.id })
        assertTrue(state.routines.contacts.isEmpty())
    }

    @Test
    fun `a contact's row is about its turn, not its cadence`() {
        val state = buildRoutinesState(listOf(contact("ana")), RoutineFilter.All, now, zone, dayStart, schedules = ::schedules)
        val row = state.rows.single { it.id == "ana" }
        assertEquals(ContactKind.WORK, row.contactKind)
        assertNotNull(row.turnAt, "it has a turn")
        assertEquals(row.turnAt, row.deadline, "and the row reads against it")
        assertTrue(row.done, "waiting its turn is not a No")
        assertEquals(DayOfWeek.WEDNESDAY, row.turnAt!!.atZone(zone).dayOfWeek)
    }

    @Test
    fun `the row reads the Settings it is given`() {
        // The screen and the scheduler must arm from the same place: moved to Thursday, the row says Thursday.
        val thursdays = DEFAULT_WORK_CONTACTS.copy(days = setOf(DayOfWeek.THURSDAY))
        val row = buildRoutinesState(listOf(contact("ana")), RoutineFilter.All, now, zone, dayStart, schedules = { thursdays }).rows.single()
        assertEquals(DayOfWeek.THURSDAY, row.turnAt!!.atZone(zone).dayOfWeek)
        // With no day at all there is no turn to name.
        val never = buildRoutinesState(listOf(contact("ana")), RoutineFilter.All, now, zone, dayStart, schedules = { thursdays.copy(days = emptySet()) }).rows.single()
        assertNull(never.turnAt)
        // And without a turn it reads its own shaken due, as the draw would, not the plain count.
        assertEquals(contact("ana").contactDeadline(zone, dayStart), never.deadline)
    }

    @Test
    fun `a contact told about and unanswered reads as a No`() {
        val row = buildRoutinesState(
            listOf(contact("ana", lastFiredAt = now.minus(Duration.ofDays(1)))),
            RoutineFilter.All, now, zone, dayStart, schedules = ::schedules,
        ).rows.single()
        assertFalse(row.done, "told, and waiting on you")
    }

    @Test
    fun `the kind chips are offered only when somebody is of that kind`() {
        val work = buildRoutinesState(listOf(contact("ana")), RoutineFilter.All, now, zone, dayStart, schedules = ::schedules)
        assertTrue(RoutineFilter.Kind(ContactKind.WORK) in work.filters)
        assertFalse(RoutineFilter.Kind(ContactKind.PERSONAL) in work.filters)
        val filtered = buildRoutinesState(
            listOf(contact("ana"), contact("mama", kind = ContactKind.PERSONAL), routine("plants")),
            RoutineFilter.Kind(ContactKind.PERSONAL), now, zone, dayStart, schedules = ::schedules,
        )
        assertEquals(listOf("mama"), filtered.rows.map { it.id })
    }

    @Test
    fun `routines and contacts share the one list`() {
        val state = buildRoutinesState(
            listOf(routine("plants"), contact("ana")), RoutineFilter.All, now, zone, dayStart, schedules = ::schedules,
        )
        assertEquals(setOf("plants", "ana"), state.rows.map { it.id }.toSet())
        assertEquals(2, state.total)
        assertEquals(1, state.overdue, "only the routine is overdue; the contact is paced")
    }
}
