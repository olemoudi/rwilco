package dev.rwilco.ui.format

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.Condition
import dev.rwilco.model.MonthlyOn
import dev.rwilco.model.RepeatEnd
import dev.rwilco.model.RepeatUnit
import dev.rwilco.model.monthlyRule
import dev.rwilco.model.weekDays
import dev.rwilco.model.CountdownParts
import dev.rwilco.model.Period
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
fun rememberIs24h(): Boolean {
    val context = LocalContext.current
    return remember(context) { DateFormat.is24HourFormat(context) }
}

/** "hoy", "mañana", "ayer", or the short date. */
@Composable
fun dayWord(date: LocalDate, today: LocalDate, locale: Locale): String = when (date) {
    today -> stringResource(R.string.relative_today)
    today.plusDays(1) -> stringResource(R.string.relative_tomorrow)
    today.minusDays(1) -> stringResource(R.string.relative_yesterday)
    else -> TimeText.dayDate(date, locale)
}

/** "en 3 d 4 h" · "en 2 h 14 min" · "en 14 min 05 s" (ticking, under an hour) · "hace 5 min". */
@Composable
fun countdownText(parts: CountdownParts): String {
    val days = stringResource(R.string.countdown_days, parts.days)
    val hours = stringResource(R.string.countdown_hours, parts.hours)
    val minutes = stringResource(R.string.countdown_minutes, parts.minutes)
    val seconds = stringResource(R.string.countdown_seconds, parts.seconds)
    val body = when {
        parts.days > 0 -> if (parts.hours > 0) "$days $hours" else days
        parts.hours > 0 -> if (parts.minutes > 0) "$hours $minutes" else hours
        parts.overdue -> minutes
        // Under an hour the seconds tick; padded so the text does not jitter as they count down.
        parts.minutes > 0 -> "$minutes " + stringResource(R.string.countdown_seconds_padded, parts.seconds)
        else -> seconds
    }
    return if (parts.overdue) stringResource(R.string.countdown_ago, body) else stringResource(R.string.countdown_in, body)
}

/** A condition in a few words: "18:00–22:00 · L M X J V". */
@Composable
fun conditionLabel(condition: Condition): String {
    val locale = currentLocale()
    val is24h = rememberIs24h()
    return when (condition) {
        is Condition.TimeWindow -> {
            val window = TimeText.window(condition.from, condition.to, is24h, locale)
            if (condition.days.isEmpty() || condition.days.size == 7) window else "$window · " + daysSummary(condition.days, locale)
        }
        is Condition.AtPlace -> stringResource(
            if (condition.inside) R.string.condition_at_place else R.string.condition_away_from_place,
            condition.label,
        )
    }
}

/** A trigger as two lines: the reading (mono when it is a time or date) and the words under it. */
data class TriggerLine(val primary: String, val secondary: String, val primaryMono: Boolean)

@Composable
fun triggerLine(trigger: Trigger, today: LocalDate, defaultTime: LocalTime): TriggerLine {
    val locale = currentLocale()
    val is24h = rememberIs24h()
    return when (trigger) {
        is Trigger.AtDateTime -> TriggerLine(
            primary = TimeText.time(trigger.at.toLocalTime(), is24h, locale),
            secondary = dayWord(trigger.at.toLocalDate(), today, locale),
            primaryMono = true,
        )
        is Trigger.OnDate -> TriggerLine(
            primary = dayWord(trigger.date, today, locale),
            secondary = stringResource(R.string.trigger_rings_at, TimeText.time(defaultTime, is24h, locale)),
            primaryMono = true,
        )
        is Trigger.Interval -> TriggerLine(
            primary = TimeText.window(trigger.from, trigger.to, is24h, locale),
            secondary = if (trigger.days.isEmpty() || trigger.days.size == 7) {
                stringResource(R.string.trigger_every_day)
            } else {
                daysSummary(trigger.days, locale)
            },
            primaryMono = true,
        )
        is Trigger.AtTime -> TriggerLine(
            primary = TimeText.time(trigger.time, is24h, locale),
            secondary = daysSummary(trigger.days, locale),
            primaryMono = true,
        )
        is Trigger.DayRandom -> TriggerLine(
            primary = dayWord(trigger.date, today, locale),
            secondary = stringResource(R.string.trigger_random_in_day),
            primaryMono = true,
        )
        is Trigger.Repeat -> TriggerLine(
            // The hour reads first, as it does on every other row that has one; a repeat with
            // no hour says so in its place, because the shape is what is left to say.
            primary = trigger.time?.let { TimeText.time(it, is24h, locale) }
                ?: stringResource(R.string.trigger_random_in_day),
            secondary = repeatSummary(trigger, today, locale),
            primaryMono = trigger.time != null,
        )
        is Trigger.Countdown -> {
            val startedAt = trigger.startedAt
            if (startedAt == null) {
                // A shape, not yet a reminder: it says how long, and when that will start.
                TriggerLine(
                    primary = durationText(trigger.minutes),
                    secondary = stringResource(R.string.trigger_countdown_from_start),
                    primaryMono = false,
                )
            } else {
                val at = startedAt.plusSeconds(trigger.minutes * 60L).atZone(java.time.ZoneId.systemDefault())
                TriggerLine(
                    primary = TimeText.time(at.toLocalTime(), is24h, locale),
                    secondary = dayWord(at.toLocalDate(), today, locale) + " · " + durationText(trigger.minutes),
                    primaryMono = true,
                )
            }
        }
        is Trigger.Location -> TriggerLine(
            primary = trigger.label,
            secondary = stringResource(if (trigger.transition == Transition.ENTER) R.string.trigger_arriving else R.string.trigger_leaving),
            primaryMono = false,
        )
        is Trigger.Random -> TriggerLine(
            primary = TimeText.window(trigger.from, trigger.to, is24h, locale),
            secondary = randomSummary(trigger, locale),
            primaryMono = true,
        )
    }
}

@Composable
private fun randomSummary(trigger: Trigger.Random, locale: Locale): String {
    val times = when (trigger.period) {
        Period.DAY -> pluralStringResource(R.plurals.trigger_times_a_day, trigger.timesPer, trigger.timesPer)
        Period.WEEK -> pluralStringResource(R.plurals.trigger_times_a_week, trigger.timesPer, trigger.timesPer)
    }
    val days = if (trigger.days.isEmpty() || trigger.days.size == 7) null else daysSummary(trigger.days, locale)
    return if (days == null) times else "$times · $days"
}

private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
private val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/** "cada día" · "laborables" · "fines de semana" · "L · X · V" (in the locale's week order). */
@Composable
fun daysSummary(days: Set<DayOfWeek>, locale: Locale): String = when (days) {
    DayOfWeek.entries.toSet() -> stringResource(R.string.trigger_every_day)
    weekdays -> stringResource(R.string.trigger_weekdays)
    weekend -> stringResource(R.string.trigger_weekends)
    else -> {
        val first = WeekFields.of(locale).firstDayOfWeek
        List(7) { first.plus(it.toLong()) }
            .filter { it in days }
            .joinToString(" · ") { TimeText.dayInitial(it, locale) }
    }
}

/**
 * A recurrence in a few words: "cada 2 semanas · L · J", "cada mes · el cuarto miércoles",
 * "cada día · 30 veces". The unit always, what it picks out of the unit where that is a
 * question, and where it stops when it stops.
 */
@Composable
fun repeatSummary(trigger: Trigger.Repeat, today: LocalDate, locale: Locale): String {
    val parts = ArrayList<String>(3)
    parts += when (trigger.unit) {
        RepeatUnit.DAY -> pluralStringResource(R.plurals.trigger_repeat_days, trigger.every, trigger.every)
        RepeatUnit.WEEK -> pluralStringResource(R.plurals.trigger_repeat_weeks, trigger.every, trigger.every)
        RepeatUnit.MONTH -> pluralStringResource(R.plurals.trigger_repeat_months, trigger.every, trigger.every)
        RepeatUnit.YEAR -> pluralStringResource(R.plurals.trigger_repeat_years, trigger.every, trigger.every)
    }
    when (trigger.unit) {
        // A week, a month and a year each have a choice inside them; a day does not. A year's
        // is a month's plus the month, because "el primer miércoles" alone names no date.
        RepeatUnit.WEEK -> parts += daysSummary(trigger.weekDays(), locale)
        RepeatUnit.MONTH -> parts += monthlyLabel(trigger.monthlyRule(), locale)
        RepeatUnit.YEAR -> parts += stringResource(
            R.string.trigger_yearly_of_month,
            monthlyLabel(trigger.monthlyRule(), locale),
            trigger.startsOn.month.getDisplayName(TextStyle.FULL, locale),
        )
        RepeatUnit.DAY -> Unit
    }
    when (val ends = trigger.ends) {
        is RepeatEnd.On -> parts += stringResource(R.string.trigger_repeat_until, dayWord(ends.date, today, locale))
        is RepeatEnd.After -> parts += pluralStringResource(R.plurals.trigger_repeat_times, ends.times, ends.times)
        RepeatEnd.Never -> Unit
    }
    return parts.joinToString(" · ")
}

/** "el día 26" · "el cuarto miércoles" · "el último lunes". */
@Composable
fun monthlyLabel(rule: MonthlyOn, locale: Locale): String = when (rule) {
    is MonthlyOn.Day -> stringResource(R.string.trigger_monthly_day, rule.day)
    is MonthlyOn.Nth -> stringResource(
        R.string.trigger_monthly_nth,
        stringResource(ordinalRes(rule.ordinal)),
        rule.day.getDisplayName(java.time.format.TextStyle.FULL, locale),
    )
}

/** First to fourth, and last. There is deliberately no fifth — see [MonthlyOn.Nth]. */
fun ordinalRes(ordinal: Int): Int = when (ordinal) {
    1 -> R.string.repeat_ordinal_first
    2 -> R.string.repeat_ordinal_second
    3 -> R.string.repeat_ordinal_third
    4 -> R.string.repeat_ordinal_fourth
    else -> R.string.repeat_ordinal_last
}

/** "45 min" · "2 h" · "2 h 30 min" · "3 d": the coarsest way to say a length that stays true. */
@Composable
fun durationText(minutes: Int): String {
    val days = minutes / (24 * 60)
    val hours = (minutes % (24 * 60)) / 60
    val rest = minutes % 60
    return when {
        days > 0 && hours > 0 -> stringResource(R.string.countdown_days, days) + " " + stringResource(R.string.countdown_hours, hours)
        days > 0 -> stringResource(R.string.countdown_days, days)
        hours > 0 && rest > 0 -> stringResource(R.string.countdown_hours, hours) + " " + stringResource(R.string.countdown_minutes, rest)
        hours > 0 -> stringResource(R.string.countdown_hours, hours)
        else -> stringResource(R.string.countdown_minutes, rest)
    }
}
