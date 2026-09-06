package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.reminder
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * "Y sólo el día 1": the fence a weekday cannot put ([Condition.OnMonthDays]).
 *
 * The same Thursday afternoon in Madrid as everything else here. The reason it exists is a
 * routine — "cambiar el filtro", asked about on the 1st — because a routine's "Vuelve" is its
 * span and it had no other way to ask its question on a date.
 */
class MonthDaysTest {

    private val first = Condition.OnMonthDays(setOf(1))
    private val nine = Trigger.TimeOfDay(LocalTime.of(9, 0))
    private val dayStart: LocalTime = LocalTime.of(9, 0)

    @Test
    fun `a day holds on its own day and on no other`() {
        assertTrue(first.holdsAt(local(2026, 9, 1, 9, 0), zone))
        assertFalse(first.holdsAt(local(2026, 8, 31, 9, 0), zone))
        assertFalse(first.holdsAt(local(2026, 9, 2, 9, 0), zone))
        // Every month's own first, not one month's.
        assertTrue(first.holdsAt(local(2026, 12, 1, 23, 59), zone))
    }

    @Test
    fun `a day past the end of the month is that month's last day`() {
        val last = Condition.OnMonthDays(setOf(31))
        assertTrue(last.holdsAt(local(2026, 5, 31, 9, 0), zone), "May has one")
        assertTrue(last.holdsAt(local(2026, 4, 30, 9, 0), zone), "April's 30th is its last")
        assertFalse(last.holdsAt(local(2026, 4, 29, 9, 0), zone))
        assertTrue(last.holdsAt(local(2026, 2, 28, 9, 0), zone), "February's 28th")
        assertTrue(last.holdsAt(local(2028, 2, 29, 9, 0), zone), "and its 29th in a leap year")
        assertFalse(last.holdsAt(local(2028, 2, 28, 9, 0), zone), "which is then not the last")
    }

    @Test
    fun `several days are several, and clamped ones do not multiply`() {
        val fortnight = Condition.OnMonthDays(setOf(1, 15))
        assertTrue(fortnight.holdsAt(local(2026, 9, 1, 9, 0), zone))
        assertTrue(fortnight.holdsAt(local(2026, 9, 15, 9, 0), zone))
        assertFalse(fortnight.holdsAt(local(2026, 9, 16, 9, 0), zone))
        // 29, 30 and 31 in February are one day, not three: they all clamp to the 28th.
        val ends = Condition.OnMonthDays(setOf(29, 30, 31))
        assertTrue(ends.holdsOn(LocalDate.of(2026, 2, 28)))
        assertFalse(ends.holdsOn(LocalDate.of(2026, 2, 27)))
        assertTrue(ends.holdsOn(LocalDate.of(2026, 1, 29)))
        assertTrue(ends.holdsOn(LocalDate.of(2026, 1, 31)))
        assertFalse(ends.holdsOn(LocalDate.of(2026, 1, 28)))
    }

    @Test
    fun `no days at all is every day, as it is for the weekdays`() {
        val nothing = Condition.OnMonthDays()
        assertTrue(nothing.holdsAt(local(2026, 9, 7, 9, 0), zone))
        assertTrue(nothing.holdsAt(local(2026, 9, 8, 9, 0), zone))
    }

    @Test
    fun `it says nothing about weekdays, and nothing can be asked of a place`() {
        assertEquals(emptySet<java.time.DayOfWeek>(), (first as Condition).namedDays)
        assertNull(first.place)
        assertTrue(first.knownInAdvance, "a date is knowable in advance; only a place is not")
    }

    @Test
    fun `a day nobody's calendar has is a problem, and an empty fence is not`() {
        assertEquals(TriggerProblem.MONTH_DAY_OUT_OF_RANGE, problemOf(Condition.OnMonthDays(setOf(32))))
        assertEquals(TriggerProblem.MONTH_DAY_OUT_OF_RANGE, problemOf(Condition.OnMonthDays(setOf(0))))
        assertNull(problemOf(Condition.OnMonthDays(setOf(1, 31))))
        assertNull(problemOf(Condition.OnMonthDays()))
    }

    @Test
    fun `a rule fenced to the first rings on the first, not tomorrow`() {
        val rent = reminder(nine, conditions = listOf(first))
        assertEquals(
            NextFire.Scheduled(local(2026, 9, 1, 9, 0), nine),
            nextFire(rent, now, zone, defaultTime, dayStart),
            "nine o'clock is tomorrow, but the fence says the 1st",
        )
    }

    @Test
    fun `the walk reaches a fence eleven months out`() {
        // "El 1 de agosto" written in late August: the next one is next year, and a walk that
        // gave up would call it never (see SEARCH_HORIZON).
        val august = Condition.OnMonthDays(setOf(1))
        val yearly = reminder(nine, conditions = listOf(august, Condition.DateRange(LocalDate.of(2027, 8, 1), LocalDate.of(2027, 8, 31))))
        assertEquals(
            NextFire.Scheduled(local(2027, 8, 1, 9, 0), nine),
            nextFire(yearly, now, zone, defaultTime, dayStart),
        )
    }

    @Test
    fun `a routine asks on the first, which is the whole point of it`() {
        val filter = Reminder(
            id = "filter",
            text = "Cambiar el filtro",
            rules = listOf(TriggerRule(nine, listOf(first))),
            recurrence = Recurrence.Since(3, RecurrenceUnit.MONTHS),
            createdAt = now.minusSeconds(40 * 86_400),
            updatedAt = now.minusSeconds(40 * 86_400),
        )
        assertEquals(
            Wake(local(2026, 9, 1, 9, 0), 0),
            filter.nextPrompt(now, zone, defaultTime, dayStart),
            "the question waits for the 1st",
        )
        // And the ring is still the span, which the question never touches.
        assertEquals(
            local(2026, 10, 18, 9, 0),
            filter.routineDeadline(zone, dayStart),
            "three months from the day it was written",
        )
    }

    @Test
    fun `the quiet after a hecho outranks the day, and the next first is the one asked`() {
        val done: Instant = local(2026, 9, 1, 9, 30)
        val filter = Reminder(
            id = "filter",
            text = "Cambiar el filtro",
            rules = listOf(TriggerRule(nine, listOf(first))),
            recurrence = Recurrence.Since(3, RecurrenceUnit.MONTHS),
            createdAt = now.minusSeconds(40 * 86_400),
            updatedAt = now.minusSeconds(40 * 86_400),
            lastDealtAt = done,
        )
        // Nine days of quiet on a three-month span, so the 1st just answered is not asked again.
        assertEquals(
            Wake(local(2026, 10, 1, 9, 0), 0),
            filter.nextPrompt(done.plusSeconds(60), zone, defaultTime, dayStart),
        )
    }
}
