package dev.rwilco.model

import java.text.Normalizer
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
    /** The person's waking hours: which of "las 5" and "las 17" a bare "a las 5" is. */
    shape: DayShape = DayShape.DEFAULT,
): Understood? = readWhen(text, now, zone, parts, places, shape)?.understood

/**
 * What [readWhen] read, and the stretches of the text it read it from — offsets into the text as
 * it was given, so a sentence said out loud can be handed back without its "when" (`Spoken.kt`).
 * Only what the reading *used*: an hour beside a repeat of hours ("cada 8 horas a las 9") is not
 * one of them, and left in the words it is what tells the caller there was more than it read.
 */
internal class Reading(
    val understood: Understood,
    val spans: List<IntRange>,
    /**
     * The other reading of a bare hour, when the words left it open ("a las 9": nine in the
     * morning or in the evening) — the one not chosen, for a sentence that turns out to have
     * said the chosen one already ("a las 9 y a las 21").
     */
    val twin: Understood? = null,
)

/** A reader's answer and the stretches of the folded words it came from. */
private class Found<T>(val value: T, val spans: List<IntRange>)

private fun <T> found(value: T, vararg matches: MatchResult?): Found<T> = Found(value, matches.mapNotNull { it?.range })

/** [whenInText], with where in the text each part of the answer was said. */
internal fun readWhen(
    text: String,
    now: Instant,
    zone: ZoneId,
    parts: DayParts = DayParts(),
    places: List<SavedPlace> = emptyList(),
    shape: DayShape = DayShape.DEFAULT,
): Reading? {
    val folded = Words.of(text)
    val words = folded.text
    if (words.isBlank()) return null
    val here = now.atZone(zone)
    val today = here.toLocalDate()
    val nowTime = here.toLocalTime().withSecond(0).withNano(0)
    val hour = hourIn(words, parts)
    fun read(understood: Understood, spans: List<IntRange>, twin: Understood? = null) =
        Reading(understood, spans.map(folded::original).filterNot { it.isEmpty() }, twin)

    // **A bare hour is a waking one** (the owner, 2026-10-11: "favorece siempre horas despierto,
    // yo no pongo recordatorios cuando estoy durmiendo"). "A las 5" with no "de la tarde" is
    // whichever of five and seventeen falls in this person's waking hours on the day it lands
    // on; the hour as said when both do, or neither.
    fun onDay(date: LocalDate): LocalTime? = hour.time?.let { shape.wakingOf(it, hour.twin, date) }
    fun other(date: LocalDate): LocalTime? = hour.twin?.let { twin -> if (onDay(date) == twin) hour.time else twin }

    // A calendar's hour is judged on the first day it rings on: "cada sábado a las 9" is the
    // Saturday's nine, as "el sábado a las 9" is.
    val ringsOn = weekdaysIn(words).takeIf { WEEKLY_DAYS.containsMatchIn(words) }.orEmpty()
        .let { days -> (0L..6L).map { today.plusDays(it) }.firstOrNull { it.dayOfWeek in days } } ?: today
    recurrenceIn(words, today, nowTime, hour.at(onDay(ringsOn)))?.let { return read(Understood.Comes(it.value), it.spans) }
    countdownIn(words)?.let { return read(Understood.Once(Trigger.Countdown(it.value)), it.spans) }
    relativeDayIn(words)?.let { day ->
        val date = (Trigger.RelativeDate(day.value).on(today) as? Trigger.DayRandom)?.date ?: today
        val twin = other(date)?.let { Understood.Once(Trigger.RelativeDate(day.value, it)) }
        return read(Understood.Once(Trigger.RelativeDate(day.value, onDay(date))), day.spans + hour.spans, twin)
    }
    dateIn(words, today)?.let { date ->
        val time = onDay(date.value)
        val trigger = time?.let { Trigger.AtDateTime(LocalDateTime.of(date.value, it)) } ?: Trigger.DayRandom(date.value)
        val twin = other(date.value)?.let { Understood.Once(Trigger.AtDateTime(LocalDateTime.of(date.value, it))) }
        return read(Understood.Once(trigger), date.spans + hour.spans, twin)
    }
    stretchIn(words, today)?.let { return if (hour.time == null) read(Understood.Once(it.value), it.spans) else null }
    val time = hour.time ?: return placeIn(words, places)?.let { read(Understood.Once(it.value), it.spans) }
    // An hour alone is the next time it comes round, of the readings that fall in waking hours
    // (all of them, when none does) — and "hoy" needs it still ahead: a moment already gone is
    // not something to offer.
    val candidates = listOfNotNull(time, hour.twin)
        .map { if (it > nowTime) today.atTime(it) else today.plusDays(1).atTime(it) }
        .filter { !hour.today || it.toLocalDate() == today }
    if (candidates.isEmpty()) return null
    val chosen = candidates.filter { shape.awakeAt(it) }.ifEmpty { candidates }.min()
    val twin = candidates.firstOrNull { it != chosen }?.let { Understood.Once(Trigger.AtDateTime(it)) }
    return read(Understood.Once(Trigger.AtDateTime(chosen)), hour.spans, twin)
}


/**
 * The words every reader here sees — folded, the punctuation that is not a clock's turned into
 * spaces, single-spaced, with one space at either end — and, for each of their characters, where
 * it came from in the text as given.
 *
 * `fold` cannot say that: it trims, and a decomposed accent is two characters that come out as
 * one. A sentence said out loud is handed back without its "when", and that takes knowing where
 * the "when" was. Folded a character at a time, which is what `fold` does to any word a person
 * would say: lower case, the accent off the letter.
 */
internal class Words private constructor(val text: String, private val origin: IntArray) {

    /** A stretch of [text] as the stretch of the given text it came from; the padding maps to nothing. */
    fun original(range: IntRange): IntRange {
        var first = range.first
        var last = range.last
        while (first <= last && origin[first] < 0) first++
        while (last >= first && origin[last] < 0) last--
        return if (first > last) IntRange.EMPTY else origin[first]..origin[last]
    }

    companion object {
        fun of(raw: String): Words {
            val out = StringBuilder(" ")
            val origin = ArrayList<Int>(raw.length + 2).apply { add(-1) }
            raw.forEachIndexed { index, char ->
                val folded = if (char.isWhitespace() || char in PUNCTUATION_CHARS) " " else foldChar(char)
                for (piece in folded) {
                    if (piece == ' ' && out.last() == ' ') continue
                    out.append(piece)
                    origin += index
                }
            }
            if (out.last() == ' ') origin[origin.lastIndex] = -1 else {
                out.append(' ')
                origin += -1
            }
            return Words(out.toString(), origin.toIntArray())
        }

        private fun foldChar(char: Char): String {
            val lower = char.lowercaseChar()
            if (lower.code < 0x80) return lower.toString()
            return Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        }
    }
}

// --- the hour ---

/**
 * An hour read from the words, whether the words also said "today" — which "esta tarde",
 * "esta noche" and "esta mañana" all do — and where they said both.
 *
 * It used to carry a third thing, `refused`: a part of the day the app had no hour for ("por la
 * tarde"), which took the whole sentence with it. Every part has an hour now ([DayParts]).
 */
private class Hour(
    val time: LocalTime?,
    val today: Boolean,
    val spans: List<IntRange>,
    /** The same hour twelve hours on, when nothing said which half of the day it is. */
    val twin: LocalTime? = null,
) {
    /** This hour with the half of the day settled. */
    fun at(settled: LocalTime?): Hour = Hour(settled, today, spans)
}

private fun hourIn(words: String, parts: DayParts): Hour {
    val todayMatch = TODAY.find(words) ?: THIS_PART.find(words)
    val today = todayMatch != null
    // "Esta noche a las 9" is nine in the evening, "por la tarde a las 5" is five in the
    // afternoon: a part of the day reaches an hour said with no am/pm.
    val night = NIGHT.find(words)
    val afternoon = AFTERNOON.find(words)
    val morning = MORNING.find(words)
    val late = night != null || afternoon != null
    // Every part of the day said beside a numbered hour is part of how it was said, whether or
    // not it moved it: "a las 9 por la mañana" is one phrase.
    fun hour(time: LocalTime?, vararg said: MatchResult?, open: Boolean = false) =
        Hour(
            time, today,
            if (time == null) listOfNotNull(todayMatch?.range) else (said.toList() + todayMatch).mapNotNull { it?.range },
            twin = time?.takeIf { open }?.plusHours(12),
        )
    // Open: an hour from one to twelve with nothing beside it to say which half of the day —
    // no am/pm, no "de la tarde", no part of the day, no leading zero ("09:30" is a clock).
    fun open(raw: String, qualifier: String) = qualifier.isEmpty() && !late && morning == null && isOpenHour(raw)
    ES_TIME.find(words)?.let { m ->
        val qualifier = m.groupValues[4].ifEmpty { m.groupValues[5] }
        return hour(timeOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], qualifier, late), m, night, afternoon, morning, open = open(m.groupValues[1], qualifier))
    }
    EN_AT.find(words)?.let { m ->
        return hour(timeOf(m.groupValues[1], m.groupValues[2], "", m.groupValues[3], late), m, night, afternoon, morning, open = open(m.groupValues[1], m.groupValues[3]))
    }
    EN_PM.find(words)?.let { m -> return hour(timeOf(m.groupValues[1], m.groupValues[2], "", m.groupValues[3], late), m, night, afternoon, morning) }
    NOON.find(words)?.let { m -> return hour(LocalTime.NOON, m) }
    CLOCK.find(words)?.let { m -> return hour(timeOf(m.groupValues[1], m.groupValues[2], "", "", late), m, night, afternoon, morning, open = open(m.groupValues[1], "")) }
    // A part of the day with no number in it: the hour this person keeps for it.
    if (afternoon != null) return hour(parts.afternoon, afternoon)
    if (morning != null) return hour(parts.morning, morning)
    if (night != null) return hour(parts.evening, night)
    return hour(null)
}

/** Null for an hour or a minute that does not exist: a typo is not an offer. */
private fun timeOf(hourRaw: String, minuteRaw: String, fraction: String, qualifierRaw: String, late: Boolean): LocalTime? {
    var hour = hourRaw.toIntOrNull() ?: HOUR_WORDS[hourRaw] ?: return null
    // "p. m." is pm, and "in the evening" is the night's arithmetic.
    val qualifier = when (val said = qualifierRaw.filter(Char::isLetter)) {
        "morning" -> "manana"
        "afternoon" -> "tarde"
        "evening", "night" -> "noche"
        else -> said
    }
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

private fun recurrenceIn(words: String, today: LocalDate, nowTime: LocalTime, hour: Hour): Found<Recurrence>? {
    val time = hour.time
    EVERY_HOURS.find(words)?.let { m ->
        val amount = amountOf(m.groupValues[1]) ?: return null
        return if (amount in RELATIVE_AMOUNT) found(Recurrence.After(amount, RecurrenceUnit.HOURS), m) else null
    }
    HOURLY.find(words)?.let { m -> return found(Recurrence.After(1, RecurrenceUnit.HOURS), m) }
    val weekly = WEEKLY_DAYS.findAll(words).toList()
    val days = if (weekly.isEmpty()) emptySet() else weekdaysIn(words)
    // Every day it names and the hour it rings at are part of saying it.
    val alongside = (if (weekly.isEmpty()) emptyList() else weekly.map { it.range } + WEEKDAY.findAll(words).map { it.range }) +
        (if (time != null) hour.spans else emptyList())
    EVERY_N.find(words)?.let { m ->
        val amount = amountOf(m.groupValues[1]) ?: return null
        val unit = unitOf(m.groupValues[2]) ?: return null
        return if (amount in RELATIVE_AMOUNT) Found(calendar(today, nowTime, time, amount, unit, days), listOf(m.range) + alongside) else null
    }
    EVERY_OTHER.find(words)?.let { m ->
        return Found(calendar(today, nowTime, time, 2, unitOf(m.groupValues[1]) ?: return null, days), listOf(m.range) + alongside)
    }
    EVERY_ONE.find(words)?.let { m ->
        val unit = unitOf(m.groupValues.drop(1).first { it.isNotEmpty() }) ?: return null
        return Found(calendar(today, nowTime, time, 1, unit, days), listOf(m.range) + alongside)
    }
    if (days.isNotEmpty()) return Found(calendar(today, nowTime, time, 1, RepeatUnit.WEEK, days), alongside)
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
private fun countdownIn(words: String): Found<Int>? {
    HALF_HOUR.find(words)?.let { return found(30, it) }
    QUARTER_HOUR.find(words)?.let { return found(15, it) }
    val minutes = IN_MINUTES.find(words)?.let { m -> amountOf(m.groupValues[1])?.let { found(it, m) } }
        ?: IN_HOURS.find(words)?.let { m -> amountOf(m.groupValues[1])?.let { found(it * 60, m) } }
    return minutes?.takeIf { it.value in MIN_COUNTDOWN_MINUTES..MAX_COUNTDOWN_MINUTES }
}

private fun relativeDayIn(words: String): Found<RelativeDay>? {
    IN_DAYS.find(words)?.let { m ->
        val amount = amountOf(m.groupValues[1]) ?: return null
        val unit = when (unitOf(m.groupValues[2])) {
            RepeatUnit.DAY -> RelativeUnit.DAYS
            RepeatUnit.WEEK -> RelativeUnit.WEEKS
            RepeatUnit.MONTH -> RelativeUnit.MONTHS
            else -> return null
        }
        return if (amount in RELATIVE_AMOUNT) found(RelativeDay.In(amount, unit), m) else null
    }
    DAY_AFTER_TOMORROW.find(words)?.let { return found(RelativeDay.In(2, RelativeUnit.DAYS), it) }
    TOMORROW.find(words)?.let { return found(RelativeDay.In(1, RelativeUnit.DAYS), it) }
    WEEKDAY_ONCE.find(words)?.let { m -> return WEEKDAYS[m.groupValues[1]]?.let { found(RelativeDay.NextWeekday(it), m) } }
    return null
}

private fun dateIn(words: String, today: LocalDate): Found<LocalDate>? {
    DATE_ES.find(words)?.let { m -> return nextDate(today, m.groupValues[1].toInt(), MONTHS[m.groupValues[2]] ?: return null)?.let { found(it, m) } }
    DATE_EN_DAY_FIRST.find(words)?.let { m -> return nextDate(today, m.groupValues[1].toInt(), MONTHS[m.groupValues[2]] ?: return null)?.let { found(it, m) } }
    DATE_EN_MONTH_FIRST.find(words)?.let { m -> return nextDate(today, m.groupValues[2].toInt(), MONTHS[m.groupValues[1]] ?: return null)?.let { found(it, m) } }
    DATE_NUMERIC.find(words)?.let { m ->
        val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
        val day = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        if (month !in 1..12) return null
        return (if (year == null) nextDate(today, day, month) else dateOrNull(year, month, day))?.let { found(it, m) }
    }
    (DAY_ES.find(words) ?: DAY_EN.find(words))?.let { m -> return nextDayOfMonth(today, m.groupValues[1].toInt())?.let { found(it, m) } }
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
private fun stretchIn(words: String, today: LocalDate): Found<Trigger>? {
    SOMETIME_TODAY.find(words)?.let { return found(Trigger.DayRandom(today), it) }
    // The Saturday of "este finde": the coming one on a weekday, the one under way at the weekend.
    val thisSaturday = when (today.dayOfWeek) {
        DayOfWeek.SATURDAY -> today
        DayOfWeek.SUNDAY -> today.minusDays(1)
        else -> today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
    }
    NEXT_WEEKEND.find(words)?.let {
        // The one after "este finde", as "la semana que viene" is the one after this.
        val saturday = thisSaturday.plusWeeks(1)
        return found(Trigger.DateRange(saturday, saturday.plusDays(1)), it)
    }
    THIS_WEEKEND.find(words)?.let { return found(Trigger.DateRange(maxOf(today, thisSaturday), thisSaturday.plusDays(1)), it) }
    NEXT_WEEK.find(words)?.let {
        val monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        return found(Trigger.DateRange(monday, monday.plusDays(6)), it)
    }
    THIS_WEEK.find(words)?.let { return found(Trigger.DateRange(today, today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))), it) }
    NEXT_MONTH.find(words)?.let {
        val first = today.withDayOfMonth(1).plusMonths(1)
        return found(Trigger.DateRange(first, first.with(TemporalAdjusters.lastDayOfMonth())), it)
    }
    THIS_MONTH.find(words)?.let { return found(Trigger.DateRange(today, today.with(TemporalAdjusters.lastDayOfMonth())), it) }
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
private fun placeIn(words: String, places: List<SavedPlace>): Found<Trigger.Location>? {
    if (places.isEmpty()) return null
    for (lead in PLACE_LEADS) {
        for (match in lead.pattern.findAll(words)) {
            // The space after the lead is where the name starts, an article or not first.
            val (place, end) = savedPlaceAt(words, match.range.last + 1, places) ?: continue
            val rule = Trigger.Location(
                place.lat, place.lng, place.radiusM, lead.presence, place.label,
                onCrossing = lead.onCrossing, placeId = place.id.ifBlank { null },
            )
            // From the first word of the lead to the last letter of the name.
            return Found(rule, listOf(match.range.first..end))
        }
    }
    return null
}

/**
 * The saved place named right at [after] in folded [words] (the space before the name), with or
 * without an article first, the longest name first — "Casa de mis padres" is not "Casa". The
 * place, and the index of its name's last letter. The one place a name is matched, for the
 * editor's chip and for a sentence said out loud alike.
 */
internal fun savedPlaceAt(words: String, after: Int, places: List<SavedPlace>): Pair<SavedPlace, Int>? {
    val named = places.mapNotNull { place -> Words.of(place.label).text.trim().takeIf { it.isNotEmpty() }?.let { place to " $it " } }
        .sortedByDescending { it.second.length }
    val starts = listOf(after) + ARTICLES.filter { words.startsWith(" $it ", after) }.map { after + it.length + 1 }
    for (start in starts) {
        for ((place, name) in named) {
            if (!words.startsWith(name, start)) continue
            val end = start + name.length - 2
            // "Casa de Juan" is somebody else's house, not the place called Casa: a name followed
            // by "de" and a word that is not a time of day is the start of a longer one.
            val of = OF_SOMEONE.find(words, end + 1)?.takeIf { it.range.first == end + 1 }
            if (of != null && of.groupValues[1] !in OF_A_TIME) continue
            return place to end
        }
    }
    return null
}

/** " de juan", " del trabajo": what follows a name that may make it a longer one. */
private val OF_SOMEONE = Regex(" (?:de|del|of) (\\S+)")

/** The words after "de" that say when rather than whose: "llegar a casa de noche". */
private val OF_A_TIME = setOf("noche", "madrugada", "manana", "tarde", "dia", "vuelta", "nuevo", "paso", "camino", "night", "day")


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
private const val PUNCTUATION_CHARS = ",;()¿?¡!\"'"

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
/**
 * am/pm however a recogniser writes it: "pm", "p.m.", Spanish "p. m.". One group; the dots and
 * the space are dropped before it is read ([timeOf]). What follows it is a space or a full stop,
 * since the dot is part of it and `\\b` cannot follow a dot.
 */
private const val AM_PM = "(a\\.? ?m|p\\.? ?m)\\.?"
private const val END = "(?=[\\s.])"
private val ES_TIME = Regex("\\ba las? $ES_HOUR_PATTERN${MINUTES}h?(?: y (media|cuarto))?(?: de la (manana|tarde|noche)| ?$AM_PM)?$END")
private val EN_AT = Regex("\\bat $EN_HOUR_PATTERN$MINUTES(?: ?$AM_PM)?$END")
private val EN_PM = Regex("\\b(\\d{1,2})$MINUTES ?$AM_PM$END")
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
// "El lunes que viene", "el lunes próximo": the words after it are part of saying it.
private val WEEKDAY_ONCE = Regex("\\b$WEEKDAY_PATTERN\\b(?!s)(?: que viene\\b| proximo\\b)?")

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

// "El 5 de marzo", "el día 5 de marzo": the word for the day goes with the date.
private val DATE_ES = Regex("\\b(?:dia )?(\\d{1,2}) de $MONTH_ES\\b")
private val DATE_EN_DAY_FIRST = Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?(?: of)? $MONTH_EN\\b")
private val DATE_EN_MONTH_FIRST = Regex("\\b$MONTH_EN (?:the )?(\\d{1,2})(?:st|nd|rd|th)?\\b")
private val DATE_NUMERIC = Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2}|\\d{4}))?\\b")
private val DAY_ES = Regex("\\bel (?:dia )?(\\d{1,2})\\b(?![./]\\d)")
private val DAY_EN = Regex("\\bon the (\\d{1,2})(?:st|nd|rd|th)\\b")

// "En algún momento de hoy", and the other ways of saying today with no hour in it on purpose.
// Plain "hoy" is not one: it has always needed an hour still ahead (see the tests).
private val SOMETIME_TODAY = Regex("\\b(?:(?:en )?algun momento (?:de|del dia de) hoy|hoy en algun momento|a lo largo (?:del dia )?de hoy|some ?time today|at some point today|later today)\\b")
// What may lead a stretch in, so that it goes with it when the words are handed back: "en algún
// momento de esta semana", "a lo largo del mes que viene", "sometime this week".
private const val SOMETIME = "(?:(?:en )?algun momento (?:de |del )?|a lo largo (?:de |del )?|durante |some ?time |at some point |later |during )?"
private val THIS_WEEK = Regex("\\b${SOMETIME}(?:esta semana|this week)\\b")
private val NEXT_WEEK = Regex("\\b${SOMETIME}(?:la (?:semana que viene|proxima semana|semana proxima)|next week)\\b")
private val THIS_WEEKEND = Regex("\\b${SOMETIME}(?:(?:este|el|del) (?:fin de semana|finde)|(?:this|the) weekend)\\b")
private val NEXT_WEEKEND = Regex("\\b${SOMETIME}(?:(?:el |del )?(?:fin de semana|finde) que viene|(?:el|del) proximo (?:fin de semana|finde)|next weekend)\\b")
private val THIS_MONTH = Regex("\\b${SOMETIME}(?:este mes|this month)\\b")
private val NEXT_MONTH = Regex("\\b${SOMETIME}(?:(?:el|del) (?:mes que viene|proximo mes)|next month)\\b")

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
        Regex("\\b(?:when|once|as soon as) i (?:get(?: back)?|arrive|come back|go back|return|reach)(?: (?:to|at|in))?\\b|\\b(?:when|on|upon) arriving (?:at|in)\\b"),
        Presence.INSIDE, onCrossing = true,
    ),
    // The doorway out: "cuando salga de", "al salir de", "cuando me vaya de", "when I leave".
    PlaceLead(
        Regex("\\b(?:(?:cuando|en cuanto|nada mas|apenas) (?:salga|salgas|me vaya|te vayas|me marche|te marches)|al (?:salir|irme|marcharme)) (?:del|de)\\b"),
        Presence.OUTSIDE, onCrossing = true,
    ),
    PlaceLead(Regex("\\b(?:when|once|as soon as|after) i (?:leave|get out of|go out of)\\b|\\b(?:when|on|upon) leaving\\b"), Presence.OUTSIDE, onCrossing = true),
    // Outside, as a state: "mientras no esté en", "cuando esté fuera de". Before the inside
    // reading, though neither can match the other's words.
    PlaceLead(Regex("\\b(?:(?:cuando|mientras|si) )?no (?:este|estes|estoy|estas) en\\b"), Presence.OUTSIDE, onCrossing = false),
    PlaceLead(Regex("\\b(?:(?:(?:cuando|mientras|si) )?(?:este|estes|estoy|estas)|estando) fuera (?:del|de)\\b"), Presence.OUTSIDE, onCrossing = false),
    PlaceLead(Regex("\\b(?:(?:while|when|whenever|if) )?(?:i m|im|i am) (?:not (?:at|in)|away from|out of)\\b"), Presence.OUTSIDE, onCrossing = false),
    // Inside, as a state: "cuando esté en", "mientras estoy en", "estando en", "while I'm at".
    // "Cuando", "mientras" or "si" in front, or none: "y sólo si estoy en casa" reaches here
    // with its "si" already taken as the join.
    PlaceLead(Regex("\\b(?:(?:(?:cuando|mientras|si) )?(?:este|estes|estoy|estas)|estando) en\\b"), Presence.INSIDE, onCrossing = false),
    PlaceLead(Regex("\\b(?:(?:(?:while|when|whenever|if) )?(?:i m|im|i am)|while) (?:at|in)\\b"), Presence.INSIDE, onCrossing = false),
)

/**
 * Whether anything in [text] is a way of saying when that a reader here knows, read or not —
 * an hour, a day, a weekday, a repeat, a length, a stretch, a part of the day, a doorway. What a
 * sentence said out loud is checked against once its "when" is out of it (`Spoken.kt`): the
 * grammar's own vocabulary, so it can never disagree with what the readers would have read.
 * A doorway counts without a saved place after it — "cuando llegue al curro" with no "curro" is
 * exactly the "when" nobody understood.
 */
internal fun soundsLikeWhen(text: String): Boolean {
    val words = Words.of(text).text
    return WHEN_PATTERNS.any { it.containsMatchIn(words) }
}

// --- what the grammar of a spoken sentence borrows (Spoken.kt) ---

/** The pattern of an amount said in digits or words ("3", "tres", "veinte", "an"): one capturing group. */
internal val AMOUNT_WORDS_PATTERN: String get() = AMOUNT_PATTERN

/** An amount said in digits or words. */
internal fun amountIn(raw: String): Int? = amountOf(raw)

/** The pattern of an hour in either language, digits or words: one capturing group. */
internal val HOUR_WORDS_PATTERN: String get() = "(\\d{1,2}|" + HOUR_WORDS.keys.joinToString("|") + ")"

/**
 * An hour as [timeOf] reads one: [qualifier] is "manana", "tarde", "noche", "am", "pm" or empty,
 * and [late] says a part of the day later than the morning was said beside it.
 */
internal fun hourOf(raw: String, minutes: String, qualifier: String, late: Boolean): LocalTime? =
    timeOf(raw, minutes, "", qualifier, late)

/** Whether folded [words] say where by one of the ways the readers know, a saved place after it or not. */
internal fun mentionsPlace(words: String): Boolean = PLACE_LEADS.any { it.pattern.containsMatchIn(words) }

/**
 * Whether an hour said with nothing beside it leaves its half of the day open: two to eleven, in
 * digits without a leading zero ("09:00" is a clock) or in words. One is not — "a la una" is
 * lunchtime however it is spelled — and nor is twelve, which is noon.
 */
internal fun isOpenHour(raw: String): Boolean = !raw.startsWith("0") && (raw.toIntOrNull() ?: HOUR_WORDS[raw] ?: 0) in 2..11

/** Whether folded [words] name a stretch of the calendar ("esta semana", "el finde que viene"). */
internal fun mentionsStretch(words: String): Boolean =
    listOf(SOMETIME_TODAY, THIS_WEEK, NEXT_WEEK, THIS_WEEKEND, NEXT_WEEKEND, THIS_MONTH, NEXT_MONTH).any { it.containsMatchIn(words) }

/** The month a folded name stands for, in either language ("octubre", "oct", "october"). */
internal fun monthNamed(word: String): Int? = MONTHS[word]

/** The pattern of a month's name in either language: one capturing group. */
internal val MONTH_WORDS_PATTERN: String get() = "(" + MONTHS.keys.joinToString("|") + ")"

/** The weekday a folded name stands for, in either language. */
internal fun weekdayNamed(word: String): DayOfWeek? = WEEKDAYS[word]

/** The pattern of a weekday's name in either language: one capturing group. */
internal val WEEKDAY_WORDS_PATTERN: String get() = WEEKDAY_PATTERN

/** Whether folded [words] name an hour with a number in it, or noon: what a part of the day is then only the qualifier of. */
internal fun namesClockHour(words: String): Boolean =
    listOf(ES_TIME, EN_AT, EN_PM, CLOCK, NOON).any { it.containsMatchIn(words) }

/** "Que viene" with nothing read beside it is still somebody saying when. */
private val COMING = Regex("\\bque viene\\b")

// Last in the file: every pattern it lists is initialised above it.
private val WHEN_PATTERNS: List<Regex> = listOf(
    ES_TIME, EN_AT, EN_PM, CLOCK, NOON, TODAY, THIS_PART, MORNING, AFTERNOON, NIGHT,
    TOMORROW, DAY_AFTER_TOMORROW, WEEKDAY, IN_DAYS, DATE_ES, DATE_EN_DAY_FIRST, DATE_EN_MONTH_FIRST, DATE_NUMERIC, DAY_ES, DAY_EN,
    EVERY_HOURS, HOURLY, EVERY_N, EVERY_OTHER, EVERY_ONE, HALF_HOUR, QUARTER_HOUR, IN_MINUTES, IN_HOURS,
    SOMETIME_TODAY, THIS_WEEK, NEXT_WEEK, THIS_WEEKEND, NEXT_WEEKEND, THIS_MONTH, NEXT_MONTH, COMING,
) + PLACE_LEADS.map { it.pattern }

