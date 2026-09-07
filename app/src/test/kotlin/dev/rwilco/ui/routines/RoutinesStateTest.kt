package dev.rwilco.ui.routines

import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.RoutineFilter
import dev.rwilco.model.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** The routines screen's rows: the question, its answer, and the chips over them. */
class RoutinesStateTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private val now: Instant = LocalDateTime.of(2026, 8, 27, 15, 0).atZone(zone).toInstant()
    private val dayStart = LocalTime.of(9, 0)

    private fun routine(id: String, daysAgo: Long, tags: List<String> = emptyList(), status: Status = Status.ACTIVE, lastFiredAt: Instant? = null) = Reminder(
        id = id,
        text = "text $id",
        tags = tags,
        recurrence = Recurrence.Since(21, RecurrenceUnit.DAYS),
        status = status,
        createdAt = now.minus(Duration.ofDays(daysAgo)),
        updatedAt = now.minus(Duration.ofDays(daysAgo)),
        lastFiredAt = lastFiredAt,
    )

    private val plain = Reminder(id = "plain", text = "not a routine", createdAt = now, updatedAt = now)

    @Test
    fun `a row is the question with its answer, and the overdue ones come first`() {
        val fresh = routine("fresh", daysAgo = 1)
        val late = routine("late", daysAgo = 30)
        val state = buildRoutinesState(listOf(plain, fresh, late), RoutineFilter.All, now, zone, dayStart)
        assertTrue(state.loaded)
        assertEquals(listOf("late", "fresh"), state.rows.map { it.id })
        val freshRow = state.rows.single { it.id == "fresh" }
        assertTrue(freshRow.done, "Sí: the span has not run out")
        assertEquals(fresh.createdAt, freshRow.anchor)
        assertEquals(LocalDateTime.of(2026, 9, 16, 9, 0).atZone(zone).toInstant(), freshRow.deadline)
        assertEquals(Duration.between(freshRow.anchor, freshRow.deadline), freshRow.span)
        assertFalse(state.rows.single { it.id == "late" }.done, "No: the span ran out nine days ago")
        assertEquals(2, state.total)
        assertEquals(1, state.overdue)
        assertFalse(state.empty)
    }

    @Test
    fun `a routine told to start later is neither overdue nor counting`() {
        // "Empezando el 1 de octubre": the count runs from October, so the row says when it
        // starts rather than how long it has been, and nothing is owed meanwhile.
        val october = LocalDateTime.of(2026, 10, 1, 9, 0).atZone(zone).toInstant()
        val later = routine("later", daysAgo = 1).let { it.copy(recurrence = Recurrence.Since(3, RecurrenceUnit.MONTHS, startsAt = october)) }
        val row = buildRoutinesState(listOf(later), RoutineFilter.All, now, zone, dayStart).rows.single()
        assertTrue(row.startsLater)
        assertEquals(october, row.anchor)
        assertEquals(LocalDateTime.of(2027, 1, 1, 9, 0).atZone(zone).toInstant(), row.deadline)
        assertTrue(row.done, "nothing is owed before it starts")
        // Once it has been done the start is behind it: the count is about the last time.
        val done = buildRoutinesState(listOf(later.copy(lastDealtAt = now)), RoutineFilter.All, now, zone, dayStart).rows.single()
        assertFalse(done.startsLater)
        assertEquals(now, done.anchor)
    }

    @Test
    fun `a paused routine reads the clock it was paused at, and owes nothing`() {
        // Paused twenty days in, looked at fifteen days later: the span ran out on the wall
        // clock, but the row says "Sí" and carries the moment the count stopped.
        val pausedAt = now.minus(Duration.ofDays(15))
        val resting = routine("rest", daysAgo = 35, status = Status.PAUSED).copy(pausedAt = pausedAt)
        val row = buildRoutinesState(listOf(resting), RoutineFilter.All, now, zone, dayStart).rows.single()
        assertTrue(row.paused)
        assertEquals(pausedAt, row.pausedAt)
        assertTrue(row.done, "nothing is owed while it rests")
        assertEquals(0, buildRoutinesState(listOf(resting), RoutineFilter.All, now, zone, dayStart).overdue)
        // Not paused: the wall clock, and the same routine is overdue.
        assertFalse(buildRoutinesState(listOf(routine("late", daysAgo = 35)), RoutineFilter.All, now, zone, dayStart).rows.single().done)
    }

    @Test
    fun `the chips are vencidas while any is, and the routines' own tags`() {
        val tagged = routine("tagged", daysAgo = 1, tags = listOf("coche"))
        val untagged = routine("untagged", daysAgo = 1)
        assertEquals(listOf(RoutineFilter.Tag("coche")), buildRoutinesState(listOf(tagged, untagged), RoutineFilter.All, now, zone, dayStart).filters)
        val late = routine("late", daysAgo = 30, tags = listOf("casa"))
        val state = buildRoutinesState(listOf(tagged, late), RoutineFilter.Overdue, now, zone, dayStart)
        assertEquals(listOf(RoutineFilter.Overdue, RoutineFilter.Tag("casa"), RoutineFilter.Tag("coche")), state.filters, "tied on use, so alphabetical")
        assertEquals(listOf("late"), state.rows.map { it.id })
        assertEquals(RoutineFilter.Overdue, state.filter)
    }

    @Test
    fun `a filter on something no longer offered is no filter`() {
        val fresh = routine("fresh", daysAgo = 1, tags = listOf("Coche"))
        // Nothing overdue: "vencidas" is not a chip, so a filter on it clears.
        val overdue = buildRoutinesState(listOf(fresh), RoutineFilter.Overdue, now, zone, dayStart)
        assertEquals(RoutineFilter.All, overdue.filter)
        assertEquals(listOf("fresh"), overdue.rows.map { it.id })
        // A tag is matched whatever its case, and read back by the spelling on offer.
        val tag = buildRoutinesState(listOf(fresh), RoutineFilter.Tag("coche"), now, zone, dayStart)
        assertEquals(RoutineFilter.Tag("Coche"), tag.filter)
        assertEquals(listOf("fresh"), tag.rows.map { it.id })
        val gone = buildRoutinesState(listOf(fresh), RoutineFilter.Tag("casa"), now, zone, dayStart)
        assertEquals(RoutineFilter.All, gone.filter)
    }

    @Test
    fun `nothing at all is empty, and a filter that found nothing is not`() {
        assertTrue(buildRoutinesState(listOf(plain), RoutineFilter.All, now, zone, dayStart).empty)
        val fresh = routine("fresh", daysAgo = 1, tags = listOf("coche"))
        val paused = routine("paused", daysAgo = 40, status = Status.PAUSED)
        val state = buildRoutinesState(listOf(fresh, paused), RoutineFilter.All, now, zone, dayStart)
        assertFalse(state.empty)
        assertEquals(listOf("fresh", "paused"), state.rows.map { it.id }, "paused last, whatever its count says")
        assertTrue(state.rows.single { it.id == "paused" }.paused)
        assertEquals(0, state.overdue, "a paused routine is owed nothing")
    }

    @Test
    fun `posponer is offered where the deadline rang and is waiting for an answer`() {
        val rang = routine("rang", daysAgo = 30, lastFiredAt = now.minus(Duration.ofDays(9)))
        val quiet = routine("quiet", daysAgo = 1)
        val state = buildRoutinesState(listOf(rang, quiet), RoutineFilter.All, now, zone, dayStart)
        assertTrue(state.rows.single { it.id == "rang" }.snoozeOffered)
        assertFalse(state.rows.single { it.id == "quiet" }.snoozeOffered)
        assertFalse(state.rows.single { it.id == "rang" }.snoozed)
    }
}
