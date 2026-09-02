package dev.rwilco.ui.format

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.Locale

class TimeTextTest {

    private val es = Locale.forLanguageTag("es-ES")
    private val en = Locale.forLanguageTag("en-GB")
    private val date = LocalDate.of(2026, 8, 27)

    @Test
    fun `times honour the 24h switch`() {
        assertEquals("21:30", TimeText.time(LocalTime.of(21, 30), is24h = true, locale = en))
        assertEquals("9:30 pm", TimeText.time(LocalTime.of(21, 30), is24h = false, locale = en).lowercase())
        assertEquals("09:05", TimeText.time(LocalTime.of(9, 5), is24h = true, locale = es))
    }

    @Test
    fun `day-date is short and without abbreviation dots`() {
        assertEquals("jue 27 ago", TimeText.dayDate(date, es))
        assertEquals("Thu 27 Aug", TimeText.dayDate(date, en))
    }

    @Test
    fun `a day-date says its year only when it is not this one`() {
        val today = LocalDate.of(2026, 9, 2)
        assertEquals("jue 3 sept", TimeText.dayDate(LocalDate.of(2026, 9, 3), es, today))
        // "Cada 4 años" from tomorrow: without the year these two read as one day twice.
        assertEquals("mar 3 sept 2030", TimeText.dayDate(LocalDate.of(2030, 9, 3), es, today))
        assertEquals("dom 3 sept 2034", TimeText.dayDate(LocalDate.of(2034, 9, 3), es, today))
        assertEquals("Thu 10 Jan 2030", TimeText.dayDate(LocalDate.of(2030, 1, 10), en, today))
        // The first day of next year is next year, however close it is.
        assertEquals("vie 1 ene 2027", TimeText.dayDate(LocalDate.of(2027, 1, 1), es, LocalDate.of(2026, 12, 31)))
        // And the head of an export says its year whatever year it is: it is read later.
        assertEquals("jue 27 ago 2026", TimeText.dayDateWithYear(date, es))
    }

    @Test
    fun `the long date reads naturally in both languages`() {
        assertEquals("jueves, 27 de agosto", TimeText.dateLong(date, es))
        assertEquals("Thursday, 27 August", TimeText.dateLong(date, en))
    }

    @Test
    fun `month headers are capitalised and day initials are single letters`() {
        assertEquals("Agosto 2026", TimeText.monthYear(YearMonth.of(2026, 8), es))
        assertEquals("August 2026", TimeText.monthYear(YearMonth.of(2026, 8), en))
        assertEquals("W", TimeText.dayInitial(DayOfWeek.WEDNESDAY, en))
        assertEquals("X", TimeText.dayInitial(DayOfWeek.WEDNESDAY, es))
        assertEquals("L", TimeText.dayInitial(DayOfWeek.MONDAY, es))
    }

    @Test
    fun `a window joins two times with an en dash`() {
        assertEquals("10:00–20:00", TimeText.window(LocalTime.of(10, 0), LocalTime.of(20, 0), is24h = true, locale = es))
    }
}
