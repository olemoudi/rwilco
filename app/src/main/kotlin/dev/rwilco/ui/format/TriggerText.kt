package dev.rwilco.ui.format

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.Condition
import dev.rwilco.model.Presence
import dev.rwilco.model.MonthlyOn
import dev.rwilco.model.RepeatEnd
import dev.rwilco.model.RepeatUnit
import dev.rwilco.model.monthlyRule
import dev.rwilco.model.weekDays
import dev.rwilco.model.CountdownParts
import dev.rwilco.model.Period
import dev.rwilco.model.Trigger
import java.time.DayOfWeek
import java.time.LocalDate
import dev.rwilco.ui.localToday
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import dev.rwilco.model.RelativeDay
import dev.rwilco.model.RelativeUnit

@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
fun rememberIs24h(): Boolean {
    val context = LocalContext.current
    return remember(context) { DateFormat.is24HourFormat(context) }
}

/**
 * What it takes to write a phrase down: the strings, the language they are written in, and
 * whether this person's clock has twelve hours on it or twenty-four.
 *
 * It exists so that the wording is not a Compose thing. Everything below used to read its
 * strings out of the composition, which is fine while the only reader is a screen — and then a
 * notification has to say *why* it rang, from a receiver with no composition within a mile of
 * it, in the same words the form used when the rule was written. Two functions saying the same
 * sentence drift; one function does not. So the three things composition was being asked for
 * are carried here instead, and a screen fills them from composition ([rememberWords]) while
 * anything else fills them from a [Context] ([Context.words]).
 */
class Words(private val context: Context, val locale: Locale, val is24h: Boolean) {
    fun get(id: Int): String = context.getString(id)
    fun get(id: Int, vararg args: Any): String = context.getString(id, *args)
    fun plural(id: Int, count: Int): String = context.resources.getQuantityString(id, count, count)
    fun ordinals(id: Int): Array<String> = context.resources.getStringArray(id)
}

@Composable
fun rememberWords(): Words {
    val context = LocalContext.current
    val locale = currentLocale()
    val is24h = rememberIs24h()
    return remember(context, locale, is24h) { Words(context, locale, is24h) }
}

/** The same three, off a plain context: the configuration already holds all of them. */
fun Context.words(): Words = Words(this, resources.configuration.locales[0], DateFormat.is24HourFormat(this))

/** "hoy", "mañana", "ayer", or the short date. */
fun dayWord(words: Words, date: LocalDate, today: LocalDate): String = when (date) {
    today -> words.get(R.string.relative_today)
    today.plusDays(1) -> words.get(R.string.relative_tomorrow)
    today.minusDays(1) -> words.get(R.string.relative_yesterday)
    else -> TimeText.dayDate(date, words.locale, today)
}

/** "en 3 d 4 h" · "en 2 h 14 min" · "en 14 min 05 s" (ticking, under an hour) · "hace 5 min" · "ahora mismo". */
@Composable
fun countdownText(parts: CountdownParts): String {
    if (parts.justNow) return stringResource(R.string.countdown_just_now)
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

/**
 * The four ways of reading a circle, in the four words people use for them.
 *
 * Two questions and not four answers: which side of the line ([presence]), and whether the phone
 * has to be seen getting there ([onCrossing]). Every screen that says what a place rule means
 * comes through here, so the card, the row and the sheet cannot use three different words for
 * the same rule.
 */
fun placeReading(presence: Presence, onCrossing: Boolean): Int = when {
    presence == Presence.INSIDE && onCrossing -> R.string.place_on_arrival
    presence == Presence.INSIDE -> R.string.place_while_inside
    onCrossing -> R.string.place_on_leaving
    else -> R.string.place_while_outside
}

/**
 * A condition in a few words: "18:00–22:00 · L M X J V". [today] is what lets a stretch of
 * another year say so (0.67.0, the same fix `dayWord` had in 0.66.2).
 */
@Composable
fun conditionLabel(condition: Condition, today: LocalDate = localToday()): String {
    val words = rememberWords()
    val locale = words.locale
    val is24h = words.is24h
    return when (condition) {
        is Condition.TimeWindow -> {
            val window = TimeText.window(condition.from, condition.to, is24h, locale)
            if (condition.days.isEmpty() || condition.days.size == 7) window else "$window · " + daysSummary(words, condition.days)
        }
        is Condition.DateRange -> stringResource(
            R.string.condition_date_range,
            TimeText.dayDate(condition.from, locale, today),
            TimeText.dayDate(condition.to, locale, today),
        )
        is Condition.OnDays -> daysSummary(words, condition.days)
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
    val words = rememberWords()
    val locale = words.locale
    val is24h = words.is24h
    return when (trigger) {
        is Trigger.AtDateTime -> TriggerLine(
            primary = TimeText.time(trigger.at.toLocalTime(), is24h, locale),
            secondary = dayWord(words, trigger.at.toLocalDate(), today),
            primaryMono = true,
        )
        is Trigger.OnDate -> TriggerLine(
            primary = dayWord(words, trigger.date, today),
            secondary = stringResource(R.string.trigger_rings_at, TimeText.time(defaultTime, is24h, locale)),
            primaryMono = true,
        )
        is Trigger.Interval -> TriggerLine(
            primary = TimeText.window(trigger.from, trigger.to, is24h, locale),
            // A window with no days named is not a thing that happens every day — whether it
            // comes back at all is what "Vuelve" answers, one row further down the same card.
            // What an empty day set says is that no day is ruled out, which is a different
            // sentence: "cada día" beside "cada mes" read as two claims about repeating.
            secondary = if (trigger.days.isEmpty() || trigger.days.size == 7) {
                stringResource(R.string.trigger_any_day_of_week)
            } else {
                daysSummary(words, trigger.days)
            },
            primaryMono = true,
        )
        is Trigger.AtTime -> TriggerLine(
            primary = TimeText.time(trigger.time, is24h, locale),
            secondary = daysSummary(words, trigger.days),
            primaryMono = true,
        )
        // No days is every day here, as it is on a window: it says which days are allowed, never
        // how often the reminder comes back — that one is "Vuelve"'s answer and nobody else's.
        is Trigger.TimeOfDay -> TriggerLine(
            primary = TimeText.time(trigger.time, is24h, locale),
            secondary = if (trigger.days.isEmpty() || trigger.days.size == 7) {
                stringResource(R.string.trigger_any_day_of_week)
            } else {
                daysSummary(words, trigger.days)
            },
            primaryMono = true,
        )
        // The days read as the whole of it, so they take the line the hour usually has — and in
        // words, not mono: it is a name, not a number. What is left to say is the hour nobody
        // chose, which is the same sentence a date without one says.
        is Trigger.Weekday -> TriggerLine(
            primary = daysPhrase(words, trigger.days),
            secondary = stringResource(R.string.trigger_when_day_starts),
            primaryMono = false,
        )
        is Trigger.DayRandom -> TriggerLine(
            primary = dayWord(words, trigger.date, today),
            // Which stretch of the day, when it was given one: "al azar durante el día" and
            // "a la hora de comer" are the same shape and a very different arrangement.
            secondary = trigger.window
                ?.let { TimeText.window(it.from, it.to, is24h, locale) }
                ?: stringResource(R.string.trigger_when_day_starts),
            primaryMono = true,
        )
        is Trigger.Repeat -> TriggerLine(
            // The hour reads first, as it does on every other row that has one; a repeat with
            // no hour says so in its place, because the shape is what is left to say.
            primary = trigger.time?.let { TimeText.time(it, is24h, locale) }
                ?: stringResource(R.string.trigger_when_day_starts),
            secondary = repeatSummary(words, trigger, today),
            primaryMono = trigger.time != null,
        )
        // A day nobody has counted yet: the day it names, and the fact that it is counted
        // afresh every time — without which "Mañana" on a preset reads exactly like a fixed
        // date that happens to fall tomorrow, which is the thing it is not.
        is Trigger.RelativeDate -> TriggerLine(
            primary = relativeDayText(words, trigger.day).replaceFirstChar { it.titlecase(locale) },
            secondary = relativeHourText(words, trigger, defaultTime) + " · " + words.get(R.string.trigger_relative_from_use),
            primaryMono = false,
        )
        is Trigger.Countdown -> {
            val startedAt = trigger.startedAt
            if (startedAt == null) {
                // A shape, not yet a reminder: it says how long, and when that will start.
                TriggerLine(
                    primary = durationText(words, trigger.minutes),
                    secondary = stringResource(R.string.trigger_countdown_from_start),
                    primaryMono = false,
                )
            } else {
                val at = startedAt.plusSeconds(trigger.minutes * 60L).atZone(java.time.ZoneId.systemDefault())
                TriggerLine(
                    primary = TimeText.time(at.toLocalTime(), is24h, locale),
                    secondary = dayWord(words, at.toLocalDate(), today) + " · " + durationText(words, trigger.minutes),
                    primaryMono = true,
                )
            }
        }
        is Trigger.DateRange -> TriggerLine(
            primary = TimeText.dayDate(trigger.from, locale, today),
            secondary = stringResource(R.string.trigger_until, TimeText.dayDate(trigger.to, locale, today)),
            primaryMono = true,
        )
        is Trigger.Location -> TriggerLine(
            primary = trigger.label,
            secondary = stringResource(placeReading(trigger.presence, trigger.onCrossing)),
            primaryMono = false,
        )
        is Trigger.Random -> TriggerLine(
            primary = TimeText.window(trigger.from, trigger.to, is24h, locale),
            secondary = randomSummary(words, trigger),
            primaryMono = true,
        )
    }
}

/**
 * A trigger as a clause somebody could say out loud, for the sentence over the save button.
 *
 * Not the same job as [triggerLine], and that is why it is a second function rather than a
 * flag on the first. A row is two halves — the reading and the words under it — laid out one
 * over the other, and folding those two into a line gives "Casa mientras no estoy", which is
 * a label with a space in it and not a sentence. Prose puts the preposition where speech puts
 * it: *mientras no estoy en Casa*, *durante la franja 18:30–20:00 laborables*.
 *
 * Every phrase here starts lower case and carries its own preposition, so it drops into the
 * middle of a sentence after the words and after "o"/"y" without anything having to be
 * patched around it. The two crossing readings need no strings of their own: "al llegar a %s"
 * and "al salir de %s" were already written.
 */
fun triggerPhrase(words: Words, trigger: Trigger, today: LocalDate, defaultTime: LocalTime): String {
    val locale = words.locale
    val is24h = words.is24h
    return when (trigger) {
        is Trigger.AtDateTime -> atDayAndTime(words, trigger.at.toLocalDate(), trigger.at.toLocalTime(), today)
        // The hour it will actually ring at, which for a bare date is the one from the settings.
        is Trigger.OnDate -> atDayAndTime(words, trigger.date, defaultTime, today)
        is Trigger.DayRandom -> trigger.window?.let {
            words.get(
                R.string.editor_sentence_interval,
                TimeText.window(it.from, it.to, is24h, locale),
                dayWord(words, trigger.date, today),
            )
        } ?: (dayWord(words, trigger.date, today) + ", " + words.get(R.string.trigger_when_day_starts))
        // The same words as the fence it folds into siblings as, because it is the same stretch.
        is Trigger.DateRange -> words.get(
            R.string.editor_sentence_date_range,
            TimeText.dayDate(trigger.from, locale, today),
            TimeText.dayDate(trigger.to, locale, today),
        )
        is Trigger.AtTime -> words.get(
            R.string.editor_sentence_at_time,
            TimeText.time(trigger.time, is24h, locale),
            daysSummary(words, trigger.days),
        )
        is Trigger.TimeOfDay -> words.get(
            R.string.editor_sentence_at_time,
            TimeText.time(trigger.time, is24h, locale),
            everyDayOr(words, trigger.days),
        )
        is Trigger.Weekday -> words.get(R.string.editor_sentence_on_days, daysPhrase(words, trigger.days))
        is Trigger.Interval -> words.get(
            R.string.editor_sentence_interval,
            TimeText.window(trigger.from, trigger.to, is24h, locale),
            everyDayOr(words, trigger.days),
        )
        is Trigger.Repeat -> words.get(
            R.string.editor_sentence_repeat,
            trigger.time?.let { TimeText.time(it, is24h, locale) } ?: words.get(R.string.trigger_when_day_starts),
            repeatSummary(words, trigger, today),
        )
        is Trigger.RelativeDate -> words.get(
            R.string.editor_sentence_relative,
            relativeDayText(words, trigger.day) + " " + relativeHourText(words, trigger, defaultTime),
        )
        is Trigger.Countdown -> {
            val startedAt = trigger.startedAt
            // Not started: it is a length, and what it is counted from. Started: it is a moment
            // like any other, and the row above still says how long it was.
            if (startedAt == null) {
                durationText(words, trigger.minutes) + " " + words.get(R.string.trigger_countdown_from_start)
            } else {
                val fires = startedAt.plusSeconds(trigger.minutes * 60L).atZone(ZoneId.systemDefault())
                atDayAndTime(words, fires.toLocalDate(), fires.toLocalTime(), today)
            }
        }
        is Trigger.Location -> words.get(placePhrase(trigger.presence, trigger.onCrossing), trigger.label)
        is Trigger.Random -> words.get(
            R.string.editor_sentence_random,
            randomSummary(words, trigger),
            TimeText.window(trigger.from, trigger.to, is24h, locale),
        )
    }
}

/** "hoy a las 09:00": the one shape three of the triggers come down to. */
private fun atDayAndTime(words: Words, date: LocalDate, time: LocalTime, today: LocalDate): String =
    words.get(
        R.string.editor_sentence_at_datetime,
        dayWord(words, date, today),
        TimeText.time(time, words.is24h, words.locale),
    )

/** The four readings of a circle, said the way they are said in a sentence. */
fun placePhrase(presence: Presence, onCrossing: Boolean): Int = when {
    presence == Presence.INSIDE && onCrossing -> R.string.trigger_arrive_at
    presence == Presence.INSIDE -> R.string.editor_sentence_place_inside
    onCrossing -> R.string.trigger_leave_from
    else -> R.string.editor_sentence_place_outside
}

/**
 * A fence as the rest of the sentence "sólo …", so that two of them join with an "y" and
 * neither repeats the word.
 */
fun conditionPhrase(words: Words, condition: Condition, today: LocalDate): String = when (condition) {
    is Condition.TimeWindow -> words.get(
        R.string.editor_sentence_window_of,
        TimeText.window(condition.from, condition.to, words.is24h, words.locale) +
            everyDaySuffix(words, condition.days),
    )
    is Condition.DateRange -> words.get(
        R.string.editor_sentence_date_range,
        TimeText.dayDate(condition.from, words.locale, today),
        TimeText.dayDate(condition.to, words.locale, today),
    )
    is Condition.OnDays -> words.get(R.string.editor_sentence_on_days, daysPhrase(words, condition.days))
    is Condition.AtPlace -> words.get(
        if (condition.inside) R.string.editor_sentence_if_at else R.string.editor_sentence_if_away,
        condition.label,
    )
}

/**
 * No days, or all seven, is "cualquier día de la semana" rather than a list of every letter.
 *
 * Which is not the same as "cada día", and the difference is the whole of what a day set means
 * on a window: it says which days are *allowed*, never how often the reminder comes back. That
 * one is "Vuelve"'s answer and nobody else's.
 */
private fun everyDayOr(words: Words, days: Set<DayOfWeek>): String =
    if (days.isEmpty() || days.size == 7) words.get(R.string.trigger_any_day_of_week) else daysSummary(words, days)

/** The same, said only when it narrows anything: a fence on every day says nothing extra. */
private fun everyDaySuffix(words: Words, days: Set<DayOfWeek>): String =
    if (days.isEmpty() || days.size == 7) "" else " " + daysSummary(words, days)

private fun randomSummary(words: Words, trigger: Trigger.Random): String {
    val times = when (trigger.period) {
        Period.DAY -> words.plural(R.plurals.trigger_times_a_day, trigger.timesPer)
        Period.WEEK -> words.plural(R.plurals.trigger_times_a_week, trigger.timesPer)
    }
    val days = if (trigger.days.isEmpty() || trigger.days.size == 7) null else daysSummary(words, trigger.days)
    return if (days == null) times else "$times · $days"
}

private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
private val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/** "cada día" · "laborables" · "fines de semana" · "L · X · V" (in the locale's week order). */
fun daysSummary(words: Words, days: Set<DayOfWeek>): String = when (days) {
    DayOfWeek.entries.toSet() -> words.get(R.string.trigger_every_day)
    weekdays -> words.get(R.string.trigger_weekdays)
    weekend -> words.get(R.string.trigger_weekends)
    else -> {
        val first = WeekFields.of(words.locale).firstDayOfWeek
        List(7) { first.plus(it.toLong()) }
            .filter { it in days }
            .joinToString(" · ") { TimeText.dayInitial(it, words.locale) }
    }
}

/**
 * The days as they read inside a sentence: one day gets its own name, several get [daysSummary].
 *
 * "Los V" is a line on a card read as prose, and it is not prose. A single day is the case that
 * matters — it is what "un día de la semana" is for — and its name costs nothing to say in full.
 */
fun daysPhrase(words: Words, days: Set<DayOfWeek>): String =
    days.singleOrNull()?.getDisplayName(TextStyle.FULL, words.locale) ?: daysSummary(words, days)

/**
 * A recurrence in a few words: "cada 2 semanas · L · J", "cada mes · el cuarto miércoles",
 * "cada día · 30 veces". The unit always, what it picks out of the unit where that is a
 * question, and where it stops when it stops.
 */
fun repeatSummary(words: Words, trigger: Trigger.Repeat, today: LocalDate): String {
    val parts = ArrayList<String>(3)
    parts += when (trigger.unit) {
        RepeatUnit.DAY -> words.plural(R.plurals.trigger_repeat_days, trigger.every)
        RepeatUnit.WEEK -> words.plural(R.plurals.trigger_repeat_weeks, trigger.every)
        RepeatUnit.MONTH -> words.plural(R.plurals.trigger_repeat_months, trigger.every)
        RepeatUnit.YEAR -> words.plural(R.plurals.trigger_repeat_years, trigger.every)
    }
    when (trigger.unit) {
        // A week, a month and a year each have a choice inside them; a day does not. A year's
        // is a month's plus the month, because "el primer miércoles" alone names no date.
        RepeatUnit.WEEK -> parts += daysSummary(words, trigger.weekDays())
        RepeatUnit.MONTH -> parts += monthlyLabel(words, trigger.monthlyRule())
        RepeatUnit.YEAR -> parts += words.get(
            R.string.trigger_yearly_of_month,
            monthlyLabel(words, trigger.monthlyRule()),
            trigger.startsOn.month.getDisplayName(TextStyle.FULL, words.locale),
        )
        RepeatUnit.DAY -> Unit
    }
    when (val ends = trigger.ends) {
        is RepeatEnd.On -> parts += words.get(R.string.trigger_repeat_until, dayWord(words, ends.date, today))
        is RepeatEnd.After -> parts += words.plural(R.plurals.trigger_repeat_times, ends.times)
        RepeatEnd.Never -> Unit
    }
    return parts.joinToString(" · ")
}

/** "el día 26" · "el cuarto miércoles" · "el último lunes". */
fun monthlyLabel(words: Words, rule: MonthlyOn): String = when (rule) {
    is MonthlyOn.Day -> words.get(R.string.trigger_monthly_day, rule.day)
    is MonthlyOn.Nth -> words.get(
        R.string.trigger_monthly_nth,
        words.get(ordinalRes(rule.ordinal)),
        rule.day.getDisplayName(java.time.format.TextStyle.FULL, words.locale),
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

/**
 * The day a relative trigger names, in the words somebody would use: "mañana", "pasado mañana",
 * "la semana que viene", "dentro de 3 días", "el próximo lunes". The near ones have names of
 * their own and the rest count, which is how people talk about days.
 */
fun relativeDayText(words: Words, day: RelativeDay): String = when (day) {
    is RelativeDay.NextWeekday -> words.get(
        R.string.relative_next_weekday,
        day.day.getDisplayName(TextStyle.FULL, words.locale),
    )
    is RelativeDay.In -> when {
        day.unit == RelativeUnit.DAYS && day.amount == 1 -> words.get(R.string.relative_tomorrow)
        day.unit == RelativeUnit.DAYS && day.amount == 2 -> words.get(R.string.relative_day_after_tomorrow)
        day.unit == RelativeUnit.WEEKS && day.amount == 1 -> words.get(R.string.relative_next_week)
        day.unit == RelativeUnit.MONTHS && day.amount == 1 -> words.get(R.string.relative_next_month)
        day.unit == RelativeUnit.DAYS -> words.plural(R.plurals.relative_in_days, day.amount)
        day.unit == RelativeUnit.WEEKS -> words.plural(R.plurals.relative_in_weeks, day.amount)
        else -> words.plural(R.plurals.relative_in_months, day.amount)
    }
}

/** And when in that day: an hour somebody chose, a stretch to draw from, or the day itself. */
fun relativeHourText(words: Words, trigger: Trigger.RelativeDate, defaultTime: LocalTime): String {
    val time = trigger.time
    val window = trigger.window
    return when {
        time != null -> words.get(R.string.trigger_rings_at, TimeText.time(time, words.is24h, words.locale))
        window != null -> words.get(R.string.editor_sentence_window_of, TimeText.window(window.from, window.to, words.is24h, words.locale))
        else -> words.get(R.string.trigger_when_day_starts)
    }
}

/** "45 min" · "2 h" · "2 h 30 min" · "3 d": the coarsest way to say a length that stays true. */
fun durationText(words: Words, minutes: Int): String {
    val days = minutes / (24 * 60)
    val hours = (minutes % (24 * 60)) / 60
    val rest = minutes % 60
    return when {
        days > 0 && hours > 0 -> words.get(R.string.countdown_days, days) + " " + words.get(R.string.countdown_hours, hours)
        days > 0 -> words.get(R.string.countdown_days, days)
        hours > 0 && rest > 0 -> words.get(R.string.countdown_hours, hours) + " " + words.get(R.string.countdown_minutes, rest)
        hours > 0 -> words.get(R.string.countdown_hours, hours)
        else -> words.get(R.string.countdown_minutes, rest)
    }
}
