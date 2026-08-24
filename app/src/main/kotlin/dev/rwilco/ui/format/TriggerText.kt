package dev.rwilco.ui.format

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.CountdownParts
import dev.rwilco.model.Period
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
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
        is Trigger.AtTime -> TriggerLine(
            primary = TimeText.time(trigger.time, is24h, locale),
            secondary = daysSummary(trigger.days, locale),
            primaryMono = true,
        )
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
