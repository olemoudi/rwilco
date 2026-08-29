package dev.rwilco.ui.format

import dev.rwilco.R
import dev.rwilco.model.LAST_ORDINAL
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceHour
import dev.rwilco.model.RecurrenceUnit
import java.time.LocalDate
import java.time.format.TextStyle

/*
 * A recurrence in words.
 *
 * It lived in `RecurrenceSection` while a screen was the only thing that read it, and moved here
 * when a notification had to say the same thing: the wording of what a rule asks for belongs
 * beside the wording of the rules themselves (`TriggerText`), not inside one of the forms that
 * happens to show it.
 */

/**
 * A recurrence in as few words as it can be said in.
 *
 * [today] is only ever used by a calendar, whose ending is read as a day word ("hasta el
 * martes") rather than a date nobody has to count from.
 */
fun recurrenceLabel(words: Words, recurrence: Recurrence, today: LocalDate): String {
    val locale = words.locale
    return when (recurrence) {
        Recurrence.None -> words.get(R.string.recur_none)
        Recurrence.ByTrigger -> words.get(R.string.recur_by_trigger)
        is Recurrence.Calendar -> repeatSummary(words, recurrence.repeat, today)
        is Recurrence.After -> recurrenceSpanLabel(words, recurrence) + hourSuffix(words, recurrence)
        // Nothing writes one any more; it is still what somebody's saved preset says.
        is Recurrence.MonthlyWeekday -> {
            val ordinals = words.ordinals(R.array.recur_ordinals)
            val ordinal = if (recurrence.ordinal >= LAST_ORDINAL) ordinals.last() else ordinals[(recurrence.ordinal - 1).coerceIn(ordinals.indices)]
            words.get(R.string.recur_monthly_weekday, ordinal, recurrence.day.getDisplayName(TextStyle.FULL, locale))
        }
    }
}

/** The span itself, without the hour it lands on. */
private fun recurrenceSpanLabel(words: Words, recurrence: Recurrence.After): String =
    when (recurrence.unit) {
            RecurrenceUnit.HOURS -> words.get(R.string.recur_hours, recurrence.amount)
            RecurrenceUnit.DAYS ->
                if (recurrence.amount == 1) words.get(R.string.recur_next_day)
                else words.get(R.string.recur_days, recurrence.amount)
            RecurrenceUnit.WEEKS ->
                if (recurrence.amount == 1) words.get(R.string.recur_week)
                else words.get(R.string.recur_weeks, recurrence.amount)
            RecurrenceUnit.MONTHS ->
                if (recurrence.amount == 1) words.get(R.string.recur_month)
                else words.get(R.string.recur_months, recurrence.amount)
            RecurrenceUnit.YEARS ->
                if (recurrence.amount == 1) words.get(R.string.recur_year)
                else words.get(R.string.recur_years, recurrence.amount)
    }

/**
 * The hour a span lands on, said only when it is not the one the app would have chosen anyway:
 * every reminder written before the question existed means [RecurrenceHour.DayStart], and a
 * line that suddenly grew three words would read as something having changed.
 */
private fun hourSuffix(words: Words, recurrence: Recurrence.After): String {
    if (recurrence.unit == RecurrenceUnit.HOURS) return ""
    return when (val hour = recurrence.hour) {
        RecurrenceHour.DayStart -> ""
        RecurrenceHour.Same -> " · " + words.get(R.string.recur_hour_same_short)
        is RecurrenceHour.At -> " · " + words.get(
            R.string.recur_hour_at,
            TimeText.time(hour.time, words.is24h, words.locale),
        )
    }
}
