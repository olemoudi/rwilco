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
