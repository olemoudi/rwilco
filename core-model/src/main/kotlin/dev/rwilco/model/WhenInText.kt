package dev.rwilco.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * The "when" the words themselves say.
 *
 * "Sacar la basura mañana a las 9" carries its own trigger, and asking for it again through a
 * tile and a sheet is asking somebody to say the same thing twice. This reads it out — Spanish
 * and English, always both, because the words are the person's and not the phone's — and hands
 * back one of the shapes the editor already holds. It is **an offer and never an act**: the
 * editor shows it as the first quick chip, labelled with what was understood, and the words in
 * the text stay exactly as typed.
 *
 * What it will not do is guess from a bare number. "Las 3 bolsas" is not three in the morning;
 * an hour needs "a las", "at", a colon or an am/pm, a day needs a day word, and a length needs
 * "en"/"in" and a unit. A false silence costs one tap on a tile; a false chip costs trust in
 * every chip after it.
 */
sealed interface Understood {
    /** One moment: a length, a day counted or pointed at, an hour. */
    data class Once(val trigger: Trigger) : Understood

    /** Something that comes back: a calendar, or a span of hours from the "hecho". */
    data class Comes(val recurrence: Recurrence) : Understood
}

/**
 * Reads [text] for a "when", against the clock [now] in [zone]. Null when the words name none.
 *
 * A repeat outranks a moment in the same sentence ("a partir de mañana cada día a las nueve" is
 * a daily), a length outranks an hour beside it, and an hour alone is today while it is still
 * ahead and tomorrow once it is past — the same reading the suggestions give a past hour.
 */
fun whenInText(text: String, now: Instant, zone: ZoneId): Understood? {
    val words = " " + fold(text).replace(PUNCTUATION, " ").replace(SPACES, " ").trim() + " "
    if (words.isBlank()) return null
    val here = now.atZone(zone)
    val today = here.toLocalDate()
    val nowTime = here.toLocalTime().withSecond(0).withNano(0)
    val hour = hourIn(words)

    recurrenceIn(words, today, nowTime, hour.time)?.let { return Understood.Comes(it) }
    countdownIn(words)?.let { return Understood.Once(Trigger.Countdown(it)) }
    relativeDayIn(words)?.let { return Understood.Once(Trigger.RelativeDate(it, hour.time)) }
    dateIn(words, today)?.let { date ->
        val trigger = hour.time?.let { Trigger.AtDateTime(LocalDateTime.of(date, it)) } ?: Trigger.DayRandom(date)
        return Understood.Once(trigger)
    }
    val time = hour.time ?: return null
    // "Hoy" needs an hour still ahead: a moment already gone is not something to offer.
    if (hour.today) return if (time > nowTime) Understood.Once(Trigger.AtDateTime(LocalDateTime.of(today, time))) else null
    val date = if (time > nowTime) today else today.plusDays(1)
    return Understood.Once(Trigger.AtDateTime(LocalDateTime.of(date, time)))
}

// --- the hour ---

/** An hour read from the words, and whether the words also said "today". */
private class Hour(val time: LocalTime?, val today: Boolean)

private fun hourIn(words: String): Hour {
    val today = TODAY.containsMatchIn(words) || TONIGHT.containsMatchIn(words)
    ES_TIME.find(words)?.let { m ->
        return Hour(timeOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]), today)
    }
    EN_AT.find(words)?.let { m -> return Hour(timeOf(m.groupValues[1], m.groupValues[2], "", m.groupValues[3]), today) }
    EN_PM.find(words)?.let { m -> return Hour(timeOf(m.groupValues[1], m.groupValues[2], "", m.groupValues[3]), today) }
    if (NOON.containsMatchIn(words)) return Hour(LocalTime.NOON, today)
    CLOCK.find(words)?.let { m -> return Hour(timeOf(m.groupValues[1], m.groupValues[2], "", ""), today) }
    // A part of the day with no number in it: the two hours the quick chips already stand for.
    if (MORNING.containsMatchIn(words)) return Hour(MORNING_HOUR, today)
    if (NIGHT.containsMatchIn(words)) return Hour(NIGHT_HOUR, today)
    return Hour(null, today)
}

/** Null for an hour or a minute that does not exist: a typo is not an offer. */
private fun timeOf(hourRaw: String, minuteRaw: String, fraction: String, qualifier: String): LocalTime? {
    var hour = hourRaw.toIntOrNull() ?: HOUR_WORDS[hourRaw] ?: return null
    val minute = when {
        minuteRaw.isNotEmpty() -> minuteRaw.toInt()
        fraction == "media" -> 30
        fraction == "cuarto" -> 15
        else -> 0
    }
    when (qualifier) {
        // "A la una" is lunchtime to anyone who says it; one in the morning gets said in full.
        "" -> if (hourRaw == "una" && hour == 1) hour = 13
        "tarde", "noche", "pm" -> if (hour in 1..11) hour += 12
        "am" -> if (hour == 12) hour = 0
    }
    if (hour !in 0..23 || minute !in 0..59) return null
    return LocalTime.of(hour, minute)
}

// --- something that comes back ---

private fun recurrenceIn(words: String, today: LocalDate, nowTime: LocalTime, time: LocalTime?): Recurrence? {
    EVERY_HOURS.find(words)?.let { m ->
        val amount = amountOf(m.groupValues[1]) ?: return null
        return if (amount in RELATIVE_AMOUNT) Recurrence.After(amount, RecurrenceUnit.HOURS) else null
    }
    if (HOURLY.containsMatchIn(words)) return Recurrence.After(1, RecurrenceUnit.HOURS)
    val days = WEEKLY_DAYS.takeIf { it.containsMatchIn(words) }?.let { weekdaysIn(words) }.orEmpty()
    EVERY_N.find(words)?.let { m ->
        val amount = amountOf(m.groupValues[1]) ?: return null
        val unit = unitOf(m.groupValues[2]) ?: return null
        return if (amount in RELATIVE_AMOUNT) calendar(today, nowTime, time, amount, unit, days) else null
    }
    EVERY_OTHER.find(words)?.let { m -> return calendar(today, nowTime, time, 2, unitOf(m.groupValues[1]) ?: return null, days) }
    EVERY_ONE.find(words)?.let { m ->
        val unit = unitOf(m.groupValues.drop(1).first { it.isNotEmpty() }) ?: return null
        return calendar(today, nowTime, time, 1, unit, days)
    }
    if (days.isNotEmpty()) return calendar(today, nowTime, time, 1, RepeatUnit.WEEK, days)
    return null
}

/**
 * The series starts on the first day it names whose hour is still ahead: "todos los jueves a
 * las cuatro", said on a Thursday at three, starts today; said at five it starts next week.
 */
private fun calendar(today: LocalDate, nowTime: LocalTime, time: LocalTime?, every: Int, unit: RepeatUnit, days: Set<DayOfWeek>): Recurrence.Calendar {
    val ahead = time == null || time > nowTime
    val startsOn = if (days.isEmpty()) {
        if (ahead) today else today.plusDays(1)
    } else {
        (0L..7L).map { today.plusDays(it) }.first { it.dayOfWeek in days && (it != today || ahead) }
    }
    val weekDays = if (unit == RepeatUnit.WEEK) days else emptySet()
    return Recurrence.Calendar(Trigger.Repeat(startsOn = startsOn, every = every, unit = unit, time = time, days = weekDays))
}

private fun weekdaysIn(words: String): Set<DayOfWeek> =
    WEEKDAY.findAll(words).mapNotNull { WEEKDAYS[it.groupValues[1]] }.toSet()

// --- a length, a day counted, a day pointed at ---

private fun countdownIn(words: String): Int? {
    if (HALF_HOUR.containsMatchIn(words)) return 30
    if (QUARTER_HOUR.containsMatchIn(words)) return 15
    IN_MINUTES.find(words)?.let { m -> return amountOf(m.groupValues[1]) }
    IN_HOURS.find(words)?.let { m -> return amountOf(m.groupValues[1])?.times(60) }
    return null
}

private fun relativeDayIn(words: String): RelativeDay? {
    IN_DAYS.find(words)?.let { m ->
        val amount = amountOf(m.groupValues[1]) ?: return null
        val unit = when (unitOf(m.groupValues[2])) {
            RepeatUnit.DAY -> RelativeUnit.DAYS
            RepeatUnit.WEEK -> RelativeUnit.WEEKS
            RepeatUnit.MONTH -> RelativeUnit.MONTHS
            else -> return null
        }
        return if (amount in RELATIVE_AMOUNT) RelativeDay.In(amount, unit) else null
    }
    if (DAY_AFTER_TOMORROW.containsMatchIn(words)) return RelativeDay.In(2, RelativeUnit.DAYS)
    if (TOMORROW.containsMatchIn(words)) return RelativeDay.In(1, RelativeUnit.DAYS)
    WEEKDAY_ONCE.find(words)?.let { m -> return WEEKDAYS[m.groupValues[1]]?.let(RelativeDay::NextWeekday) }
    return null
}

private fun dateIn(words: String, today: LocalDate): LocalDate? {
    DATE_ES.find(words)?.let { m -> return nextDate(today, m.groupValues[1].toInt(), MONTHS[m.groupValues[2]] ?: return null) }
    DATE_EN_DAY_FIRST.find(words)?.let { m -> return nextDate(today, m.groupValues[1].toInt(), MONTHS[m.groupValues[2]] ?: return null) }
    DATE_EN_MONTH_FIRST.find(words)?.let { m -> return nextDate(today, m.groupValues[2].toInt(), MONTHS[m.groupValues[1]] ?: return null) }
    DATE_NUMERIC.find(words)?.let { m ->
        val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
        val day = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        if (month !in 1..12) return null
        return if (year == null) nextDate(today, day, month) else dateOrNull(year, month, day)
    }
    (DAY_ES.find(words) ?: DAY_EN.find(words))?.let { m -> return nextDayOfMonth(today, m.groupValues[1].toInt()) }
    return null
}

/** The next [day] of [month] from today on: this year while it is still ahead, else the next. */
private fun nextDate(today: LocalDate, day: Int, month: Int): LocalDate? {
    val thisYear = dateOrNull(today.year, month, day)
    if (thisYear != null && !thisYear.isBefore(today)) return thisYear
    return dateOrNull(today.year + 1, month, day)
}

/** The next [day] of a month from today on; a 31st skips the months that have none. */
private fun nextDayOfMonth(today: LocalDate, day: Int): LocalDate? {
    if (day !in 1..31) return null
    for (ahead in 0L..2L) {
        val month = YearMonth.from(today).plusMonths(ahead)
        if (!month.isValidDay(day)) continue
        val date = month.atDay(day)
        if (!date.isBefore(today)) return date
    }
    return null
}

private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? =
    runCatching { LocalDate.of(year, month, day) }.getOrNull()

// --- vocabulary ---

private fun amountOf(raw: String): Int? = raw.toIntOrNull() ?: AMOUNT_WORDS[raw]

private fun unitOf(raw: String): RepeatUnit? = when (raw) {
    "dia", "dias", "diario", "day", "days", "daily" -> RepeatUnit.DAY
    "semana", "semanas", "week", "weeks", "weekly" -> RepeatUnit.WEEK
    "mes", "meses", "month", "months", "monthly" -> RepeatUnit.MONTH
    "ano", "anos", "year", "years", "yearly" -> RepeatUnit.YEAR
    else -> null
}

/** The colon stays: it is what makes "17:30" a clock rather than two numbers. */
private val PUNCTUATION = Regex("[,;()¿?¡!\"']")
private val SPACES = Regex("\\s+")

private const val MINUTES = "(?:[:.h](\\d{2}))?"

private val HOUR_WORDS: Map<String, Int> = mapOf(
    "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6,
    "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12,
    "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
    "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
)
private val AMOUNT_WORDS: Map<String, Int> = HOUR_WORDS + mapOf(
    "un" to 1, "uno" to 1, "a" to 1, "an" to 1,
    "quince" to 15, "veinte" to 20, "treinta" to 30, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
)
private val HOUR_PATTERN = "(\\d{1,2}|" + HOUR_WORDS.keys.joinToString("|") + ")"
private val AMOUNT_PATTERN = "(\\d{1,3}|" + AMOUNT_WORDS.keys.joinToString("|") + ")"

private val WEEKDAYS: Map<String, DayOfWeek> = mapOf(
    "lunes" to DayOfWeek.MONDAY, "martes" to DayOfWeek.TUESDAY, "miercoles" to DayOfWeek.WEDNESDAY,
    "jueves" to DayOfWeek.THURSDAY, "viernes" to DayOfWeek.FRIDAY, "sabado" to DayOfWeek.SATURDAY, "domingo" to DayOfWeek.SUNDAY,
    "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
    "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY,
)
private val WEEKDAY_PATTERN = "(" + WEEKDAYS.keys.joinToString("|") + ")"

private val MONTHS_ES: Map<String, Int> = mapOf(
    "enero" to 1, "febrero" to 2, "marzo" to 3, "abril" to 4, "mayo" to 5, "junio" to 6, "julio" to 7,
    "agosto" to 8, "septiembre" to 9, "setiembre" to 9, "octubre" to 10, "noviembre" to 11, "diciembre" to 12,
)
private val MONTHS_EN: Map<String, Int> = mapOf(
    "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3, "april" to 4, "apr" to 4,
    "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7, "august" to 8, "aug" to 8,
    "september" to 9, "sept" to 9, "sep" to 9, "october" to 10, "oct" to 10, "november" to 11, "nov" to 11,
    "december" to 12, "dec" to 12,
)
private val MONTHS: Map<String, Int> = MONTHS_ES + MONTHS_EN
private val MONTH_ES = "(" + MONTHS_ES.keys.joinToString("|") + ")"
private val MONTH_EN = "(" + MONTHS_EN.keys.joinToString("|") + ")"

private val TODAY = Regex("\\bhoy\\b|\\btoday\\b")
private val TONIGHT = Regex("\\besta noche\\b|\\btonight\\b")
private val ES_TIME = Regex("\\ba las? $HOUR_PATTERN${MINUTES}h?(?: y (media|cuarto))?(?: de la (manana|tarde|noche))?\\b")
private val EN_AT = Regex("\\bat $HOUR_PATTERN$MINUTES ?(am|pm)?\\b")
private val EN_PM = Regex("\\b(\\d{1,2})$MINUTES ?(am|pm)\\b")
private val CLOCK = Regex("\\b(\\d{1,2})[:.h](\\d{2})\\b")
private val NOON = Regex("\\bat (?:noon|midday)\\b|\\ba(?:l)? mediodia\\b")
private val MORNING = Regex("\\bpor la manana\\b|\\bmorning\\b")
private val NIGHT = Regex("\\bpor la noche\\b|\\besta noche\\b|\\btonight\\b|\\bnight\\b|\\bevening\\b")
private val MORNING_HOUR: LocalTime = LocalTime.of(9, 0)
private val NIGHT_HOUR: LocalTime = LocalTime.of(20, 0)

private val EVERY_HOURS = Regex("\\b(?:cada|every) $AMOUNT_PATTERN (?:horas?|h|hours?|hrs?)\\b")
private val HOURLY = Regex("\\bcada hora\\b|\\bevery hour\\b|\\bhourly\\b")
private val EVERY_N = Regex("\\b(?:cada|every) $AMOUNT_PATTERN (dias?|days?|semanas?|weeks?|mes|meses|months?|anos?|years?)\\b")
private val EVERY_OTHER = Regex("\\bevery other (day|week|month)\\b")
private val EVERY_ONE = Regex("\\bcada (dia|semana|mes|ano)\\b|\\ba (diario)\\b|\\btodos los (dias)\\b|\\btodas las (semanas)\\b|\\bevery (day|week|month|year)\\b|\\b(daily|weekly|monthly|yearly)\\b")
private val WEEKLY_DAYS = Regex("\\b(?:cada|todos los|los|every) ${WEEKDAY_PATTERN}s?\\b|\\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)s\\b")
private val WEEKDAY = Regex("\\b${WEEKDAY_PATTERN}s?\\b")
private val WEEKDAY_ONCE = Regex("\\b$WEEKDAY_PATTERN\\b(?!s)")

private val HALF_HOUR = Regex("\\b(?:en|dentro de|in) (?:media hora|half an hour)\\b")
private val QUARTER_HOUR = Regex("\\b(?:en|dentro de|in) (?:un cuarto de hora|a quarter of an hour)\\b")
private val IN_MINUTES = Regex("\\b(?:en|dentro de|in) $AMOUNT_PATTERN (?:min|mins|minuto|minutos|minute|minutes)\\b")
private val IN_HOURS = Regex("\\b(?:en|dentro de|in) $AMOUNT_PATTERN (?:h|hora|horas|hour|hours|hr|hrs)\\b")
private val IN_DAYS = Regex("\\b(?:en|dentro de|in) $AMOUNT_PATTERN (dia|dias|day|days|semana|semanas|week|weeks|mes|meses|month|months)\\b")
private val DAY_AFTER_TOMORROW = Regex("\\bpasado manana\\b|\\bday after tomorrow\\b")
private val TOMORROW = Regex("(?<!pasado )(?<!por la )(?<!de la )(?<!en la )\\bmanana\\b|\\btomorrow\\b")

private val DATE_ES = Regex("\\b(\\d{1,2}) de $MONTH_ES\\b")
private val DATE_EN_DAY_FIRST = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?(?: of)? $MONTH_EN\\b")
private val DATE_EN_MONTH_FIRST = Regex("\\b$MONTH_EN (?:the )?(\\d{1,2})(?:st|nd|rd|th)?\\b")
private val DATE_NUMERIC = Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2}|\\d{4}))?\\b")
private val DAY_ES = Regex("\\bel (\\d{1,2})\\b(?![./])")
private val DAY_EN = Regex("\\bon the (\\d{1,2})(?:st|nd|rd|th)\\b")
