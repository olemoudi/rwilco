package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * The days a recurrence lands on.
 *
 * August 2026 is the calendar all of this is read against: the 1st is a Saturday, so the
 * Wednesdays are the 5th, 12th, 19th and 26th and the Mondays are the 3rd, 10th, 17th, 24th and
 * 31st. That is where "the fourth Wednesday" and "the last Monday" come from below.
 */
class RepeatTriggerTest {

    private fun repeat(
        startsOn: LocalDate,
        every: Int = 1,
        unit: RepeatUnit = RepeatUnit.DAY,
        time: LocalTime? = LocalTime.of(19, 0),
        days: Set<DayOfWeek> = emptySet(),
        monthly: MonthlyOn? = null,
        ends: RepeatEnd = RepeatEnd.Never,
    ) = Trigger.Repeat(startsOn, every, unit, time, days, monthly, ends)

    private fun Trigger.Repeat.first(count: Int, from: LocalDate = startsOn): List<LocalDate> =
        occurrences(from).take(count).toList()

    private fun date(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day)

    // ---- days -------------------------------------------------------------------------------

    @Test
    fun `every day is every day`() {
        val every = repeat(date(2026, 8, 26))
        assertEquals(listOf(date(2026, 8, 26), date(2026, 8, 27), date(2026, 8, 28)), every.first(3))
    }

    @Test
    fun `every third day counts from the day it started`() {
        val every = repeat(date(2026, 8, 24), every = 3)
        assertEquals(listOf(date(2026, 8, 24), date(2026, 8, 27), date(2026, 8, 30), date(2026, 9, 2)), every.first(4))
    }

    @Test
    fun `asking from the middle of a series does not shift it`() {
        val every = repeat(date(2026, 8, 24), every = 3)
        assertEquals(listOf(date(2026, 9, 2), date(2026, 9, 5)), every.first(2, from = date(2026, 9, 1)))
    }

    // ---- weeks ------------------------------------------------------------------------------

    @Test
    fun `a week with two days ticked rings twice a week`() {
        val every = repeat(
            date(2026, 8, 24),
            unit = RepeatUnit.WEEK,
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
        )
        assertEquals(
            listOf(date(2026, 8, 24), date(2026, 8, 27), date(2026, 8, 31), date(2026, 9, 3)),
            every.first(4),
        )
    }

    @Test
    fun `every other week skips the week between, not the second day`() {
        val every = repeat(
            date(2026, 8, 24),
            every = 2,
            unit = RepeatUnit.WEEK,
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
        )
        // Both days of the fortnight's own week, then nothing until the week after next.
        assertEquals(
            listOf(date(2026, 8, 24), date(2026, 8, 27), date(2026, 9, 7), date(2026, 9, 10)),
            every.first(4),
        )
    }

    @Test
    fun `a week with nothing ticked keeps the day it started on`() {
        val every = repeat(date(2026, 8, 25), unit = RepeatUnit.WEEK)
        assertEquals(listOf(date(2026, 8, 25), date(2026, 9, 1)), every.first(2))
        assertEquals(setOf(DayOfWeek.TUESDAY), every.weekDays())
    }

    @Test
    fun `a fortnight asked about from far ahead lands on the same days it always would`() {
        val every = repeat(date(2026, 8, 25), every = 2, unit = RepeatUnit.WEEK, days = setOf(DayOfWeek.TUESDAY))
        assertEquals(listOf(date(2026, 9, 22), date(2026, 10, 6)), every.first(2, from = date(2026, 9, 20)))
    }

    @Test
    fun `a day of the week before the start day does not ring in the first week`() {
        // Started on a Thursday, rings Mondays and Thursdays: that week's Monday is behind it.
        val every = repeat(
            date(2026, 8, 27),
            unit = RepeatUnit.WEEK,
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
        )
        assertEquals(listOf(date(2026, 8, 27), date(2026, 8, 31), date(2026, 9, 3)), every.first(3))
    }

    // ---- months -----------------------------------------------------------------------------

    @Test
    fun `a day of the month keeps its number`() {
        val every = repeat(date(2026, 8, 26), unit = RepeatUnit.MONTH)
        assertEquals(listOf(date(2026, 8, 26), date(2026, 9, 26), date(2026, 10, 26)), every.first(3))
    }

    @Test
    fun `the thirty-first rings on the last day of a month that has no thirty-first`() {
        val every = repeat(date(2027, 1, 31), unit = RepeatUnit.MONTH, monthly = MonthlyOn.Day(31))
        assertEquals(
            listOf(date(2027, 1, 31), date(2027, 2, 28), date(2027, 3, 31), date(2027, 4, 30)),
            every.first(4),
        )
    }

    @Test
    fun `the fourth wednesday is the fourth wednesday`() {
        val every = repeat(
            date(2026, 8, 26),
            unit = RepeatUnit.MONTH,
            monthly = MonthlyOn.Nth(4, DayOfWeek.WEDNESDAY),
        )
        assertEquals(listOf(date(2026, 8, 26), date(2026, 9, 23), date(2026, 10, 28)), every.first(3))
    }

    @Test
    fun `the last monday is the last monday, whether there are four or five`() {
        val every = repeat(
            date(2026, 8, 31),
            unit = RepeatUnit.MONTH,
            monthly = MonthlyOn.Nth(-1, DayOfWeek.MONDAY),
        )
        // August has five Mondays and September four; both answer with the last of them.
        assertEquals(listOf(date(2026, 8, 31), date(2026, 9, 28), date(2026, 10, 26)), every.first(3))
    }

    @Test
    fun `every second month counts months, not occurrences`() {
        val every = repeat(date(2026, 8, 15), every = 2, unit = RepeatUnit.MONTH)
        assertEquals(listOf(date(2026, 8, 15), date(2026, 10, 15), date(2026, 12, 15)), every.first(3))
    }

    // ---- years ------------------------------------------------------------------------------

    @Test
    fun `a year is a year`() {
        val every = repeat(date(2026, 8, 26), unit = RepeatUnit.YEAR)
        assertEquals(listOf(date(2026, 8, 26), date(2027, 8, 26), date(2028, 8, 26)), every.first(3))
    }

    @Test
    fun `the twenty-ninth of february rings every year, not every fourth`() {
        val every = repeat(date(2028, 2, 29), unit = RepeatUnit.YEAR)
        assertEquals(
            listOf(date(2028, 2, 29), date(2029, 2, 28), date(2030, 2, 28), date(2031, 2, 28), date(2032, 2, 29)),
            every.first(5),
        )
    }

    @Test
    fun `a year names a month too, so it takes the same rule a month does`() {
        // "El primer miércoles de mayo": a yearly that is a weekday and not a date. Said any
        // other way it is arithmetic on a day that moves — 2027's first Wednesday is the 5th,
        // 2028's the 3rd — and a year of plusYears would have rung on the 6th every time.
        val mothersDay = repeat(date(2026, 5, 6), unit = RepeatUnit.YEAR, monthly = MonthlyOn.Nth(1, DayOfWeek.WEDNESDAY))
        assertEquals(
            listOf(date(2026, 5, 6), date(2027, 5, 5), date(2028, 5, 3), date(2029, 5, 2)),
            mothersDay.first(4),
        )
    }

    @Test
    fun `a yearly can also name the last weekday of its month`() {
        val lastSunday = repeat(date(2026, 3, 29), unit = RepeatUnit.YEAR, monthly = MonthlyOn.Nth(-1, DayOfWeek.SUNDAY))
        assertEquals(listOf(date(2026, 3, 29), date(2027, 3, 28), date(2028, 3, 26)), lastSunday.first(3))
    }

    @Test
    fun `a yearly every two years keeps its rule and its stride`() {
        val every = repeat(date(2026, 5, 6), every = 2, unit = RepeatUnit.YEAR, monthly = MonthlyOn.Nth(1, DayOfWeek.WEDNESDAY))
        assertEquals(listOf(date(2026, 5, 6), date(2028, 5, 3), date(2030, 5, 1)), every.first(3))
    }

    // ---- endings ----------------------------------------------------------------------------

    @Test
    fun `after so many times means exactly that many`() {
        val every = repeat(date(2026, 8, 24), ends = RepeatEnd.After(3))
        assertEquals(listOf(date(2026, 8, 24), date(2026, 8, 25), date(2026, 8, 26)), every.occurrences().toList())
    }

    @Test
    fun `a count is counted from the start even when the question is asked later`() {
        val every = repeat(date(2026, 8, 24), ends = RepeatEnd.After(3))
        assertEquals(listOf(date(2026, 8, 26)), every.occurrences(date(2026, 8, 26)).toList())
        assertTrue(every.occurrences(date(2026, 8, 27)).toList().isEmpty())
    }

    @Test
    fun `a count over a fortnightly two-a-week series counts the rings, not the weeks`() {
        val every = repeat(
            date(2026, 8, 24),
            every = 2,
            unit = RepeatUnit.WEEK,
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
            ends = RepeatEnd.After(3),
        )
        assertEquals(listOf(date(2026, 8, 24), date(2026, 8, 27), date(2026, 9, 7)), every.occurrences().toList())
    }

    @Test
    fun `ending on a date includes that date`() {
        val every = repeat(date(2026, 8, 26), ends = RepeatEnd.On(date(2026, 8, 28)))
        assertEquals(listOf(date(2026, 8, 26), date(2026, 8, 27), date(2026, 8, 28)), every.occurrences().toList())
    }

    @Test
    fun `a series told to end before it starts has nothing in it`() {
        val every = repeat(date(2026, 8, 26), ends = RepeatEnd.On(date(2026, 8, 20)))
        assertTrue(every.occurrences().toList().isEmpty())
        assertEquals(TriggerProblem.ENDS_BEFORE_START, problemOf(every))
    }

    @Test
    fun `an every of zero is refused rather than looped over`() {
        val broken = repeat(date(2026, 8, 26), every = 0)
        assertTrue(broken.occurrences().toList().isEmpty())
        assertEquals(TriggerProblem.EVERY_OUT_OF_RANGE, problemOf(broken))
    }

    @Test
    fun `a plain weekly is plain and a bounded one is not`() {
        assertTrue(repeat(date(2026, 8, 26), unit = RepeatUnit.WEEK).isPlain)
        assertTrue(!repeat(date(2026, 8, 26), every = 3).isPlain)
        assertTrue(!repeat(date(2026, 8, 26), ends = RepeatEnd.After(4)).isPlain)
    }


    // ---- the cap ----------------------------------------------------------------------------

    @Test
    fun `a count outlasts the block cap, so a daily series two years old still has its dates`() {
        // Six hundred blocks is the cap on a series with no end. A counted one is bounded by its
        // own count and must not be cut short by the cap: it used to be, and a daily series with
        // 999 rings in it went quiet on its six-hundredth day with four hundred still owed.
        val counted = repeat(date(2024, 12, 1), ends = RepeatEnd.After(999))
        val all = counted.occurrences().toList()
        assertEquals(999, all.size)
        assertEquals(date(2024, 12, 1).plusDays(998), all.last())
        // Asked from well past the old cap, the series is still there.
        assertEquals(date(2026, 8, 28), counted.first(1, from = date(2026, 8, 28)).single())
        // An endless one is still capped, which is what stops anybody walking it for ever.
        assertEquals(600, repeat(date(2024, 12, 1)).occurrences().take(700).count())
    }
}
