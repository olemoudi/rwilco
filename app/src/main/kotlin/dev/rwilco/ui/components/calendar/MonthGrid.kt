package dev.rwilco.ui.components.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** The arithmetic behind the month calendar, kept out of the composable so it is JVM-tested. */
object MonthGrid {

    /** Always six rows of seven: a fixed height keeps the sheet from jumping between months. */
    const val CELLS = 42
    const val COLUMNS = 7

    /** The pager's middle page; months are addressed relative to it so both directions scroll. */
    const val BASE_PAGE = 600
    const val PAGE_COUNT = 1200

    /** Null cells pad the first day to its weekday column and fill the last row. */
    fun cells(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate?> {
        val first = month.atDay(1)
        val leading = Math.floorMod(first.dayOfWeek.value - firstDayOfWeek.value, COLUMNS)
        return List(CELLS) { index ->
            val day = index - leading + 1
            if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
        }
    }

    /** The seven weekdays in the locale's order, for the header row. */
    fun weekdays(firstDayOfWeek: DayOfWeek): List<DayOfWeek> = List(COLUMNS) { firstDayOfWeek.plus(it.toLong()) }

    fun monthAt(page: Int, base: YearMonth): YearMonth = base.plusMonths((page - BASE_PAGE).toLong())

    fun pageOf(month: YearMonth, base: YearMonth): Int =
        (BASE_PAGE + ChronoUnit.MONTHS.between(base, month)).toInt().coerceIn(0, PAGE_COUNT - 1)
}
