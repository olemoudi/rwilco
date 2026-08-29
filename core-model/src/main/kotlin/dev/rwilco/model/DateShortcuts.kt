package dev.rwilco.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * The four days people name without looking at a calendar. Each answers with a date and nothing
 * else — the hour is the sheet's other question, and a shortcut that answered both was the
 * reason the last set of chips was taken out.
 *
 * [WEEKEND] is the calendar's Saturday, and today when today is already the weekend. It is
 * deliberately not [Snooze.WEEKEND], which starts on Friday evening at an hour from the settings:
 * that one is an answer to an alarm, this one is a day on a grid.
 */
enum class DateShortcut {
    TODAY,
    TOMORROW,
    NEXT_MONDAY,
    WEEKEND,
    ;

    fun on(today: LocalDate): LocalDate = when (this) {
        TODAY -> today
        TOMORROW -> today.plusDays(1)
        // Strictly after today: on a Monday, "next Monday" is a week away, not now.
        NEXT_MONDAY -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        WEEKEND -> if (today.dayOfWeek == DayOfWeek.SUNDAY) today else today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
    }
}
