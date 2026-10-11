package dev.rwilco.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

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
 *
 * A stretch of the calendar ("esta semana", "el finde") is read when no hour is said beside it:
 * a stretch with an hour in it is two answers to one question, and a false chip costs more than
 * none. A place is read last, and only one of [places] — the person's own names for where they
 * go — so "cuando llegue a casa" is the doorway into the place called Casa and "limpiar la casa"
 * is nothing at all. The clock outranks the place in the same sentence: one chip says one thing,
 * and it was the clock's before places were read.
 */
fun whenInText(
    text: String,
    now: Instant,
    zone: ZoneId,
    parts: DayParts = DayParts(),
    places: List<SavedPlace> = emptyList(),
): Understood? {
    val words = " " + normalized(text) + " "
    if (words.isBlank()) return null
    val here = now.atZone(zone)
    val today = here.toLocalDate()
    val nowTime = here.toLocalTime().withSecond(0).withNano(0)
    val hour = hourIn(words, parts)

    recurrenceIn(words, today, nowTime, hour.time)?.let { return Understood.Comes(it) }
    countdownIn(words)?.let { return Understood.Once(Trigger.Countdown(it)) }
    relativeDayIn(words)?.let { return Understood.Once(Trigger.RelativeDate(it, hour.time)) }
    dateIn(words, today)?.let { date ->
        val trigger = hour.time?.let { Trigger.AtDateTime(LocalDateTime.of(date, it)) } ?: Trigger.DayRandom(date)
        return Understood.Once(trigger)
    }
    stretchIn(words, today)?.let { return if (hour.time == null) Understood.Once(it) else null }
    val time = hour.time ?: return placeIn(words, places)?.let { Understood.Once(it) }
    // "Hoy" needs an hour still ahead: a moment already gone is not something to offer.
    if (hour.today) return if (time > nowTime) Understood.Once(Trigger.AtDateTime(LocalDateTime.of(today, time))) else null
    val date = if (time > nowTime) today else today.plusDays(1)
    return Understood.Once(Trigger.AtDateTime(LocalDateTime.of(date, time)))
}

// --- the hour ---

/**
 * An hour read from the words, and whether the words also said "today" — which "esta tarde",
 * "esta noche" and "esta mañana" all do.
 *
 * It used to carry a third thing, `refused`: a part of the day the app had no hour for ("por la
 * tarde"), which took the whole sentence with it. Every part has an hour now ([DayParts]).
 */
private class Hour(val time: LocalTime?, val today: Boolean)

private fun hourIn(words: String, parts: DayParts): Hour {
    val today = TODAY.containsMatchIn(words) || THIS_PART.containsMatchIn(words)
    // "Esta noche a las 9" is nine in the evening, "por la tarde a las 5" is five in the
    // afternoon: a part of the day reaches an hour said with no am/pm.
    val night = NIGHT.containsMatchIn(words)
    val afternoon = AFTERNOON.containsMatchIn(words)
    val late = night || afternoon
    ES_TIME.find(words)?.let { m ->
        return Hour(timeOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4], late), today)
    }
    EN_AT.find(words)?.let { m -> return Hour(timeOf(m.groupValues[1], m.groupValues[2], "", m.groupValues[3], late), today) }
    EN_PM.find(words)?.let { m -> return Hour(timeOf(m.groupValues[1], m.groupValues[2], "", m.groupValues[3], late), today) }
    if (NOON.containsMatchIn(words)) return Hour(LocalTime.NOON, today)
    CLOCK.find(words)?.let { m -> return Hour(timeOf(m.groupValues[1], m.groupValues[2], "", "", late), today) }
    // A part of the day with no number in it: the hour this person keeps for it.
    if (afternoon) return Hour(parts.afternoon, today)
    if (MORNING.containsMatchIn(words)) return Hour(parts.morning, today)
    if (night) return Hour(parts.evening, today)
    return Hour(null, today)
}

/** Null for an hour or a minute that does not exist: a typo is not an offer. */
private fun timeOf(hourRaw: String, minuteRaw: String, fraction: String, qualifier: String, late: Boolean): LocalTime? {
    var hour = hourRaw.toIntOrNull() ?: HOUR_WORDS[hourRaw] ?: return null
    val minute = when {
        minuteRaw.isNotEmpty() -> minuteRaw.toInt()
        fraction == "media" -> 30
        fraction == "cuarto" -> 15
        else -> 0
    }
    when (qualifier) {
        // "A la una" is lunchtime to anyone who says it, however it is spelled; one in the
        // morning gets said in full. And an hour said in the same breath as the afternoon or
        // the night is that half of the day's.
        "" -> when {
            hour == 1 -> hour = 13
            late && hour in 2..11 -> hour += 12
        }
        // "Las 12 de la noche" is midnight; the rest of the night is the afternoon's arithmetic.
        "noche" -> if (hour == 12) hour = 0 else if (hour in 1..11) hour += 12
        "tarde", "pm" -> if (hour in 1..11) hour += 12
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

/** A length the countdown sheet would accept; "en 200 horas" is not a chip worth offering. */
private fun countdownIn(words: String): Int? {
    if (HALF_HOUR.containsMatchIn(words)) return 30
    if (QUARTER_HOUR.containsMatchIn(words)) return 15
    val minutes = IN_MINUTES.find(words)?.let { m -> amountOf(m.groupValues[1]) }
        ?: IN_HOURS.find(words)?.let { m -> amountOf(m.groupValues[1])?.times(60) }
    return minutes?.takeIf { it in MIN_COUNTDOWN_MINUTES..MAX_COUNTDOWN_MINUTES }
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

// --- a stretch of the calendar ---

/**
 * "Esta semana", "el finde", "el mes que viene": the days somebody means when they mean no day in
 * particular, as the [Trigger.DateRange] the date-range tile writes — it rings at the default
 * time on its first day and on each later one until it is dealt with. "En algún momento de hoy"
 * is the one that is a single day, so it is that day left to the day ([Trigger.DayRandom]).
 *
 * A week ends on Sunday and a weekend is Saturday and Sunday, whatever the person's own weekend
 * hours say: those decide when a day starts, and "este finde" names days, not hours. Said on a
 * Saturday or a Sunday, "este finde" is what is left of the one under way.
 */
private fun stretchIn(words: String, today: LocalDate): Trigger? {
    if (SOMETIME_TODAY.containsMatchIn(words)) return Trigger.DayRandom(today)
    // The Saturday of "este finde": the coming one on a weekday, the one under way at the weekend.
    val thisSaturday = when (today.dayOfWeek) {
        DayOfWeek.SATURDAY -> today
        DayOfWeek.SUNDAY -> today.minusDays(1)
        else -> today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
    }
    if (NEXT_WEEKEND.containsMatchIn(words)) {
        // The one after "este finde", as "la semana que viene" is the one after this.
        val saturday = thisSaturday.plusWeeks(1)
        return Trigger.DateRange(saturday, saturday.plusDays(1))
    }
    if (THIS_WEEKEND.containsMatchIn(words)) return Trigger.DateRange(maxOf(today, thisSaturday), thisSaturday.plusDays(1))
    if (NEXT_WEEK.containsMatchIn(words)) {
        val monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        return Trigger.DateRange(monday, monday.plusDays(6))
    }
    if (THIS_WEEK.containsMatchIn(words)) return Trigger.DateRange(today, today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)))
    if (NEXT_MONTH.containsMatchIn(words)) {
        val first = today.withDayOfMonth(1).plusMonths(1)
        return Trigger.DateRange(first, first.with(TemporalAdjusters.lastDayOfMonth()))
    }
    if (THIS_MONTH.containsMatchIn(words)) return Trigger.DateRange(today, today.with(TemporalAdjusters.lastDayOfMonth()))
    return null
}

// --- a place ---

/** One way of saying where: which side of the line it is about, and whether it is the crossing. */
private class PlaceLead(val pattern: Regex, val presence: Presence, val onCrossing: Boolean)

/**
 * A saved place, named after one of the ways of saying where — "cuando llegue a", "al salir
 * de", "mientras esté en", "when I'm away from".
 *
 * The four readings are the four the place sheet offers ([Trigger.Location]): the doorway in,
 * the doorway out, and either side of the line as a state. "Cuando esté en casa" is the state,
 * as ARCHITECTURE.md says it nearly always is; "cuando llegue" is the doorway.
 *
 * The name is never turned into a pattern: a label is the person's own words, and a bracket or a
 * plus sign in it would be a pattern nobody meant (and on the phone's ICU engine, possibly one
 * that throws). So the lead is matched by a fixed pattern and the name after it by a plain
 * prefix, with or without an article in between, the longest name first — "Casa de mis padres"
 * is not "Casa".
 */
private fun placeIn(words: String, places: List<SavedPlace>): Trigger.Location? {
    val named = places.mapNotNull { place -> normalized(place.label).takeIf { it.isNotEmpty() }?.let { place to " $it " } }
        .sortedByDescending { it.second.length }
    if (named.isEmpty()) return null
    for (lead in PLACE_LEADS) {
        for (match in lead.pattern.findAll(words)) {
            // The space after the lead, and after an article when there is one.
            val after = match.range.last + 1
            val starts = listOf(after) + ARTICLES.filter { words.startsWith(" $it ", after) }.map { after + it.length + 1 }
            for (start in starts) {
                val place = named.firstOrNull { words.startsWith(it.second, start) }?.first ?: continue
                return Trigger.Location(
                    place.lat, place.lng, place.radiusM, lead.presence, place.label,
                    onCrossing = lead.onCrossing, placeId = place.id.ifBlank { null },
                )
            }
        }
    }
    return null
}

/** The words as every reader here sees them: folded, without the punctuation that is not a clock's, single-spaced. */
private fun normalized(text: String): String = fold(text).replace(PUNCTUATION, " ").replace(SPACES, " ").trim()

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

private val ES_HOUR_WORDS = setOf("una", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce")
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
// Each language's own words for the hours: "at once" is not eleven o'clock.
private val ES_HOUR_PATTERN = "(\\d{1,2}|" + HOUR_WORDS.keys.filter { it in ES_HOUR_WORDS }.joinToString("|") + ")"
private val EN_HOUR_PATTERN = "(\\d{1,2}|" + HOUR_WORDS.keys.filter { it !in ES_HOUR_WORDS }.joinToString("|") + ")"
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
/** "Esta tarde" is this afternoon: a part of *today*, whichever part it is. */
private val THIS_PART = Regex("\\besta (?:manana|tarde|noche)\\b|\\btonight\\b|\\bthis (?:morning|afternoon|evening)\\b")
private val ES_TIME = Regex("\\ba las? $ES_HOUR_PATTERN${MINUTES}h?(?: y (media|cuarto))?(?: de la (manana|tarde|noche))?\\b")
private val EN_AT = Regex("\\bat $EN_HOUR_PATTERN$MINUTES ?(am|pm)?\\b")
private val EN_PM = Regex("\\b(\\d{1,2})$MINUTES ?(am|pm)\\b")
/** A bare clock needs the colon (or the h): "12.50" is a price. The dot only counts after "a las"/"at". */
private val CLOCK = Regex("\\b(\\d{1,2})[:h](\\d{2})\\b")
private val NOON = Regex("\\bat (?:noon|midday)\\b|\\ba(?:l)? mediodia\\b")
private val MORNING = Regex("\\bpor la manana\\b|\\besta manana\\b|\\bmorning\\b")
private val AFTERNOON = Regex("\\bpor la tarde\\b|\\besta tarde\\b|\\bafternoon\\b")
private val NIGHT = Regex("\\bpor la noche\\b|\\besta noche\\b|\\btonight\\b|\\bnight\\b|\\bevening\\b")

private val EVERY_HOURS = Regex("\\b(?:cada|every) $AMOUNT_PATTERN ?(?:horas?|h|hours?|hrs?)\\b")
private val HOURLY = Regex("\\bcada hora\\b|\\bevery hour\\b|\\bhourly\\b")
private val EVERY_N = Regex("\\b(?:cada|every) $AMOUNT_PATTERN (dias?|days?|semanas?|weeks?|mes|meses|months?|anos?|years?)\\b")
private val EVERY_OTHER = Regex("\\bevery other (day|week|month)\\b")
private val EVERY_ONE = Regex("\\bcada (dia|semana|mes|ano)\\b|\\ba (diario)\\b|\\btodos los (dias)\\b|\\btodas las (semanas)\\b|\\bevery (day|week|month|year)\\b|\\b(daily|weekly|monthly|yearly)\\b")
private val WEEKLY_DAYS = Regex("\\b(?:cada|todos los|los|every) ${WEEKDAY_PATTERN}s?\\b|\\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)s\\b")
private val WEEKDAY = Regex("\\b${WEEKDAY_PATTERN}s?\\b")
private val WEEKDAY_ONCE = Regex("\\b$WEEKDAY_PATTERN\\b(?!s)")

private val HALF_HOUR = Regex("\\b(?:en|dentro de|in) (?:media hora|half an hour)\\b")
private val QUARTER_HOUR = Regex("\\b(?:en|dentro de|in) (?:un cuarto de hora|a quarter of an hour)\\b")
private val IN_MINUTES = Regex("\\b(?:en|dentro de|in) $AMOUNT_PATTERN ?(?:min|mins|minuto|minutos|minute|minutes)\\b")
private val IN_HOURS = Regex("\\b(?:en|dentro de|in) $AMOUNT_PATTERN ?(?:h|hora|horas|hour|hours|hr|hrs)\\b")
private val IN_DAYS = Regex("\\b(?:en|dentro de|in) $AMOUNT_PATTERN (dia|dias|day|days|semana|semanas|week|weeks|mes|meses|month|months)\\b")
private val DAY_AFTER_TOMORROW = Regex("\\bpasado manana\\b|\\bday after tomorrow\\b")
// The word for the morning is the word for the next day, so everything that makes it a morning
// keeps it from being one: "por la", "de la", "en la" — and "esta", which was missing, so "lo
// dejé hecho esta mañana" offered a chip for tomorrow (0.136.0).
// **And "esta" has to be the word** (0.139.0): written without a boundary it swallowed every word
// that merely ends in it — propuesta, respuesta, encuesta, fiesta, siesta — so "enviar la
// propuesta mañana" gave no chip at all, and "la fiesta mañana por la tarde" gave a wrong one,
// today at five, because the hour was read and the day was not.
private val TOMORROW = Regex("(?<!pasado )(?<!por la )(?<!de la )(?<!en la )(?<!\\besta )\\bmanana\\b|\\btomorrow\\b")

private val DATE_ES = Regex("\\b(\\d{1,2}) de $MONTH_ES\\b")
private val DATE_EN_DAY_FIRST = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?(?: of)? $MONTH_EN\\b")
private val DATE_EN_MONTH_FIRST = Regex("\\b$MONTH_EN (?:the )?(\\d{1,2})(?:st|nd|rd|th)?\\b")
private val DATE_NUMERIC = Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2}|\\d{4}))?\\b")
private val DAY_ES = Regex("\\bel (\\d{1,2})\\b(?![./]\\d)")
private val DAY_EN = Regex("\\bon the (\\d{1,2})(?:st|nd|rd|th)\\b")

// "En algún momento de hoy", and the other ways of saying today with no hour in it on purpose.
// Plain "hoy" is not one: it has always needed an hour still ahead (see the tests).
private val SOMETIME_TODAY = Regex("\\b(?:(?:en )?algun momento (?:de|del dia de) hoy|hoy en algun momento|a lo largo (?:del dia )?de hoy|some ?time today|at some point today|later today)\\b")
private val THIS_WEEK = Regex("\\besta semana\\b|\\bthis week\\b")
private val NEXT_WEEK = Regex("\\bla (?:semana que viene|proxima semana|semana proxima)\\b|\\bnext week\\b")
private val THIS_WEEKEND = Regex("\\b(?:este|el) (?:fin de semana|finde)\\b|\\b(?:this|the) weekend\\b")
private val NEXT_WEEKEND = Regex("\\b(?:fin de semana|finde) que viene\\b|\\bel proximo (?:fin de semana|finde)\\b|\\bnext weekend\\b")
private val THIS_MONTH = Regex("\\beste mes\\b|\\bthis month\\b")
private val NEXT_MONTH = Regex("\\bel (?:mes que viene|proximo mes)\\b|\\bnext month\\b")

/**
 * What may stand between the way of saying where and the name: "a la oficina", "al salir de mi
 * casa", "when I leave the gym". Tried with and without, so a place whose own name starts with
 * one ("La Oficina") is found either way.
 */
private val ARTICLES = listOf("la", "el", "los", "las", "mi", "mis", "the", "my")

/**
 * The ways of saying where, each ending on its preposition so the name follows. Folded words:
 * no accents, and "I'm" has lost its apostrophe ("i m"). "Al" before "a" in every alternation:
 * "a" followed by "l" is no word boundary, but the order keeps that from mattering.
 */
private val PLACE_LEADS = listOf(
    // The doorway in: "cuando llegue a", "al llegar a", "en cuanto vuelva a", "when I get to".
    PlaceLead(
        Regex("\\b(?:(?:cuando|en cuanto|nada mas|apenas) (?:llegue|llegues|vuelva|vuelvas|regrese|regreses)|(?:al|nada mas) (?:llegar|volver|regresar)) (?:al|a)\\b"),
        Presence.INSIDE, onCrossing = true,
    ),
    PlaceLead(
        Regex("\\b(?:when|once|as soon as) i (?:get(?: back)?|arrive|come back|go back|return|reach)(?: (?:to|at|in))?\\b"),
        Presence.INSIDE, onCrossing = true,
    ),
    // The doorway out: "cuando salga de", "al salir de", "cuando me vaya de", "when I leave".
    PlaceLead(
        Regex("\\b(?:(?:cuando|en cuanto|nada mas|apenas) (?:salga|salgas|me vaya|te vayas|me marche|te marches)|al (?:salir|irme|marcharme)) (?:del|de)\\b"),
        Presence.OUTSIDE, onCrossing = true,
    ),
    PlaceLead(Regex("\\b(?:when|once|as soon as|after) i (?:leave|get out of|go out of)\\b"), Presence.OUTSIDE, onCrossing = true),
    // Outside, as a state: "mientras no esté en", "cuando esté fuera de". Before the inside
    // reading, though neither can match the other's words.
    PlaceLead(Regex("\\b(?:cuando|mientras|si) no (?:este|estes|estoy|estas) en\\b"), Presence.OUTSIDE, onCrossing = false),
    PlaceLead(Regex("\\b(?:(?:cuando|mientras|si) (?:este|estes|estoy|estas)|estando) fuera (?:del|de)\\b"), Presence.OUTSIDE, onCrossing = false),
    PlaceLead(Regex("\\b(?:while|when|whenever|if) (?:i m|im|i am) (?:not (?:at|in)|away from|out of)\\b"), Presence.OUTSIDE, onCrossing = false),
    // Inside, as a state: "cuando esté en", "mientras estoy en", "estando en", "while I'm at".
    PlaceLead(Regex("\\b(?:(?:cuando|mientras|si) (?:este|estes|estoy|estas)|estando) en\\b"), Presence.INSIDE, onCrossing = false),
    PlaceLead(Regex("\\b(?:(?:while|when|whenever|if) (?:i m|im|i am)|while) (?:at|in)\\b"), Presence.INSIDE, onCrossing = false),
)
