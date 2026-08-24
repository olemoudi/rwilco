package dev.rwilco.ui.format

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The instrument readings, as text. Pure (no resources) so it is JVM-tested; anything that
 * needs plurals ("in 2 hours") lives next to the composables instead.
 */
object TimeText {

    fun time(time: LocalTime, is24h: Boolean, locale: Locale): String {
        val pattern = if (is24h) "HH:mm" else "h:mm a"
        return time.format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    /** "jue 27 ago" / "Thu 27 Aug": the abbreviation dots some locales add are noise at 14sp mono. */
    fun dayDate(date: LocalDate, locale: Locale): String =
        date.format(DateTimeFormatter.ofPattern("EEE d MMM", locale)).replace(".", "")

    /** "jueves, 27 de agosto" / "Thursday, 27 August" — the Home header. */
    fun dateLong(date: LocalDate, locale: Locale): String {
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        val dayMonth = date.format(DateTimeFormatter.ofPattern(dayMonthPattern(locale), locale))
        return "$weekday, $dayMonth"
    }

    /** "Agosto 2026": capitalised, whatever the locale's own habit. */
    fun monthYear(month: YearMonth, locale: Locale): String =
        month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.titlecase(locale) }

    /** "L" / "M" for the calendar header and the day toggles. */
    fun dayInitial(day: DayOfWeek, locale: Locale): String =
        day.getDisplayName(TextStyle.NARROW, locale).replaceFirstChar { it.titlecase(locale) }

    fun window(from: LocalTime, to: LocalTime, is24h: Boolean, locale: Locale): String =
        time(from, is24h, locale) + "–" + time(to, is24h, locale)

    private fun dayMonthPattern(locale: Locale): String =
        if (locale.language == "es") "d 'de' MMMM" else "d MMMM"
}
