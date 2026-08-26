package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The shape of a day, which is what "at random during the day" is drawn from.
 *
 * The cases that matter are the edges of the weekend, because that is where the two pairs of
 * hours meet: a Friday is a working day that ends late, a Sunday is a lie-in that ends early,
 * and both of those are one setting away from being wrong in a way nobody would notice for a
 * week.
 */
class AwakeHoursTest {

    /** Up at 8, bed at 23:30; at the weekend up at 10 and bed at half one in the morning. */
    private val shape = DayShape(
        hours = AwakeHours(
            wake = LocalTime.of(8, 0),
            sleep = LocalTime.of(23, 30),
            weekendWake = LocalTime.of(10, 0),
            weekendSleep = LocalTime.of(1, 30),
        ),
        weekendFrom = DayOfWeek.FRIDAY,
        weekendFromTime = LocalTime.of(20, 30),
        weekendTo = DayOfWeek.SUNDAY,
        weekendToTime = LocalTime.of(22, 0),
    )

    // The week of Monday 2026-08-24: Friday is the 28th, Saturday the 29th, Sunday the 30th.
    private fun day(dayOfMonth: Int) = LocalDate.of(2026, 8, dayOfMonth)

    private fun window(dayOfMonth: Int) = shape.awakeOn(day(dayOfMonth))

    @Test
    fun `a weekday runs from the weekday wake to the weekday bedtime`() {
        val tuesday = window(25)
        assertEquals(LocalDateTime.of(2026, 8, 25, 8, 0), tuesday.from)
        assertEquals(LocalDateTime.of(2026, 8, 25, 23, 30), tuesday.to)
    }

    @Test
    fun `friday gets up for work and goes to bed at the weekend`() {
        val friday = window(28)
        assertEquals(LocalDateTime.of(2026, 8, 28, 8, 0), friday.from)
        // Past midnight: the weekend started at half eight on Friday evening.
        assertEquals(LocalDateTime.of(2026, 8, 29, 1, 30), friday.to)
    }

    @Test
    fun `saturday is the weekend at both ends`() {
        val saturday = window(29)
        assertEquals(LocalDateTime.of(2026, 8, 29, 10, 0), saturday.from)
        assertEquals(LocalDateTime.of(2026, 8, 30, 1, 30), saturday.to)
    }

    @Test
    fun `sunday keeps the lie-in and loses the late night`() {
        val sunday = window(30)
        assertEquals(LocalDateTime.of(2026, 8, 30, 10, 0), sunday.from)
        // The weekend ended at ten on Sunday evening, so bedtime is the week's again.
        assertEquals(LocalDateTime.of(2026, 8, 30, 23, 30), sunday.to)
    }

    @Test
    fun `monday is a weekday again`() {
        val monday = window(31)
        assertEquals(LocalDateTime.of(2026, 8, 31, 8, 0), monday.from)
        assertEquals(LocalDateTime.of(2026, 8, 31, 23, 30), monday.to)
    }

    @Test
    fun `the weekend starts and ends at the minute it was set to`() {
        assertFalse(shape.inWeekend(LocalDateTime.of(2026, 8, 28, 20, 29)))
        assertTrue(shape.inWeekend(LocalDateTime.of(2026, 8, 28, 20, 30)))
        assertTrue(shape.inWeekend(LocalDateTime.of(2026, 8, 30, 21, 59)))
        assertFalse(shape.inWeekend(LocalDateTime.of(2026, 8, 30, 22, 0)))
    }

    @Test
    fun `moving the start of the weekend moves fridays bedtime`() {
        // The weekend now starts on Saturday morning, so Friday night is a school night.
        val later = shape.copy(weekendFrom = DayOfWeek.SATURDAY, weekendFromTime = LocalTime.of(0, 0))
        assertEquals(LocalDateTime.of(2026, 8, 28, 23, 30), later.awakeOn(day(28)).to)
        assertEquals(LocalDateTime.of(2026, 8, 28, 8, 0), later.awakeOn(day(28)).from)
    }

    @Test
    fun `moving the end of the weekend moves sundays bedtime`() {
        // Nothing ends the weekend before Monday: Sunday keeps the late night too.
        val longer = shape.copy(weekendTo = DayOfWeek.MONDAY, weekendToTime = LocalTime.of(0, 0))
        assertEquals(LocalDateTime.of(2026, 8, 31, 1, 30), longer.awakeOn(day(30)).to)
    }

    @Test
    fun `a weekend that wraps the week is still a span`() {
        // Somebody whose days off are Sunday to Tuesday: the span crosses Monday midnight.
        val shifted = shape.copy(
            weekendFrom = DayOfWeek.SATURDAY,
            weekendFromTime = LocalTime.of(20, 0),
            weekendTo = DayOfWeek.TUESDAY,
            weekendToTime = LocalTime.of(22, 0),
        )
        assertTrue(shifted.inWeekend(LocalDateTime.of(2026, 8, 30, 12, 0)))
        assertTrue(shifted.inWeekend(LocalDateTime.of(2026, 8, 31, 12, 0)))
        assertFalse(shifted.inWeekend(LocalDateTime.of(2026, 8, 26, 12, 0)))
    }

    @Test
    fun `a weekend of no width is no weekend at all`() {
        val none = shape.copy(
            weekendFrom = DayOfWeek.FRIDAY,
            weekendFromTime = LocalTime.of(20, 30),
            weekendTo = DayOfWeek.FRIDAY,
            weekendToTime = LocalTime.of(20, 30),
        )
        assertFalse(none.inWeekend(LocalDateTime.of(2026, 8, 29, 12, 0)))
        assertEquals(LocalDateTime.of(2026, 8, 29, 23, 30), none.awakeOn(day(29)).to)
    }

    @Test
    fun `the settings are what the shape is built from`() {
        val settings = AppSettings(
            awake = AwakeHours(weekendSleep = LocalTime.of(3, 0)),
            weekendDay = DayOfWeek.THURSDAY,
            weekendTime = LocalTime.of(18, 0),
            weekendEndDay = DayOfWeek.SUNDAY,
            weekendEndTime = LocalTime.of(20, 0),
        )
        assertEquals(DayOfWeek.THURSDAY, settings.dayShape.weekendFrom)
        assertEquals(LocalTime.of(20, 0), settings.dayShape.weekendToTime)
        assertEquals(LocalTime.of(3, 0), settings.dayShape.hours.weekendSleep)
    }
}
