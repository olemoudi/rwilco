package dev.rwilco.ui.components.calendar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class MonthGridTest {

    @Test
    fun `august 2026 starts on a Saturday and pads to it in a Monday-first grid`() {
        val cells = MonthGrid.cells(YearMonth.of(2026, 8), DayOfWeek.MONDAY)
        assertEquals(42, cells.size)
        assertNull(cells[4])
        assertEquals(LocalDate.of(2026, 8, 1), cells[5])
        assertEquals(LocalDate.of(2026, 8, 31), cells[35])
        assertNull(cells[36])
    }

    @Test
    fun `a Sunday-first grid pads one cell less for the same month`() {
        val cells = MonthGrid.cells(YearMonth.of(2026, 8), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 8, 1), cells[6])
    }

    @Test
    fun `a month starting on the first weekday has no padding and February can end on row five`() {
        val june = MonthGrid.cells(YearMonth.of(2026, 6), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 6, 1), june[0])
        val feb = MonthGrid.cells(YearMonth.of(2027, 2), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2027, 2, 28), feb[27])
        assertNull(feb[28])
    }

    @Test
    fun `weekday headers follow the first day and pages map to months both ways`() {
        assertEquals(DayOfWeek.SUNDAY, MonthGrid.weekdays(DayOfWeek.MONDAY).last())
        assertEquals(DayOfWeek.SATURDAY, MonthGrid.weekdays(DayOfWeek.SUNDAY).last())
        val base = YearMonth.of(2026, 8)
        assertEquals(base, MonthGrid.monthAt(MonthGrid.BASE_PAGE, base))
        assertEquals(YearMonth.of(2026, 10), MonthGrid.monthAt(MonthGrid.BASE_PAGE + 2, base))
        assertEquals(MonthGrid.BASE_PAGE - 3, MonthGrid.pageOf(YearMonth.of(2026, 5), base))
        assertEquals(YearMonth.of(2025, 12), MonthGrid.monthAt(MonthGrid.pageOf(YearMonth.of(2025, 12), base), base))
    }
}
