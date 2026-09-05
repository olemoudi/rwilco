package dev.rwilco.ui.format

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The instrument readings, as text. Pure (no resources) so it is JVM-tested; anything that
 * needs plurals ("in 2 hours") lives next to the composables instead.
 */
object TimeText {

    /**
     * The formatters, by pattern and language.
     *
     * `DateTimeFormatter.ofPattern` parses the pattern and builds a printer every time it is
     * called, and these are called from inside a scrolling list — a card with a date on it asks
     * for two or three. Measured on a desktop JVM: 2.55 us per `dayDate` built each time against
     * 0.25 us reused, and 0.80 against 0.42 for `time`. The key space is a handful of patterns
     * times the one or two locales a phone has, so there is nothing to evict; concurrent because
     * a notification says these words from a receiver's thread while a screen says them on the
     * main one.
     */
    private val formatters = ConcurrentHashMap<String, DateTimeFormatter>()

    private fun formatter(pattern: String, locale: Locale): DateTimeFormatter =
        formatters.getOrPut("$pattern|$locale") { DateTimeFormatter.ofPattern(pattern, locale) }

    fun time(time: LocalTime, is24h: Boolean, locale: Locale): String {
        val pattern = if (is24h) "HH:mm" else "h:mm a"
        return time.format(formatter(pattern, locale))
    }

    /**
     * "jue 27 ago" / "Thu 27 Aug": the abbreviation dots some locales add are noise at 14sp mono.
     *
     * With [today] given, a date outside this year says its year — "mar 3 sept 2030" (0.66.2).
     * Without it, "luego mar 3 sept · luego dom 3 sept" was a reminder four years apart reading
     * as the same day twice, the weekday its only tell.
     */
    fun dayDate(date: LocalDate, locale: Locale, today: LocalDate? = null): String =
        if (today != null && date.year != today.year) dayDateWithYear(date, locale)
        else date.format(formatter("EEE d MMM", locale)).replace(".", "")

    /** "jue 27 ago 2026", always: the head of an export, which is read in some other year. */
    fun dayDateWithYear(date: LocalDate, locale: Locale): String =
        date.format(formatter("EEE d MMM yyyy", locale)).replace(".", "")

    /** "jueves, 27 de agosto" / "Thursday, 27 August" — the Home header. */
    fun dateLong(date: LocalDate, locale: Locale): String {
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        val dayMonth = date.format(formatter(dayMonthPattern(locale), locale))
        return "$weekday, $dayMonth"
    }

    /** "Agosto 2026": capitalised, whatever the locale's own habit. */
    fun monthYear(month: YearMonth, locale: Locale): String =
        month.format(formatter("LLLL yyyy", locale)).replaceFirstChar { it.titlecase(locale) }

    /** "L" / "M" for the calendar header and the day toggles. */
    fun dayInitial(day: DayOfWeek, locale: Locale): String =
        day.getDisplayName(TextStyle.NARROW, locale).replaceFirstChar { it.titlecase(locale) }

    fun window(from: LocalTime, to: LocalTime, is24h: Boolean, locale: Locale): String =
        time(from, is24h, locale) + "–" + time(to, is24h, locale)

    private fun dayMonthPattern(locale: Locale): String =
        if (locale.language == "es") "d 'de' MMMM" else "d MMMM"
}
