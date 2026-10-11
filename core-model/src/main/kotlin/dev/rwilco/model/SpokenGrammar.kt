package dev.rwilco.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/*
 * The grammar of a sentence said out loud (0.177.0): where its "when" splits into clauses, what
 * each clause says, and how the clauses come together into rules — "al llegar a casa, y sólo si
 * es por la tarde", "a las nueve o al salir de aquí", "cuando esté en casa y sea fin de semana".
 *
 * Three steps. **Joins**: the sentence is cut at the words that join clauses ("o", "y", "y sólo
 * si", "a la vez", "y luego"), except inside a phrase that carries its own "y" ("entre las 5 y
 * las 7", "a las 9 y media", "los lunes y jueves"); a join only counts as one when there is a
 * "when" on both sides of it — "comprar pan y leche cuando llegue a casa" keeps its "y" in the
 * words. **Clauses**: each stretch between joins is read for every "when" it holds, one after
 * another (a clause can say two things with no join between them: "al llegar a casa por la
 * tarde"). **Rules**: the owner's readings (2026-10-11) — "o" is either; "y" between a moment and
 * states is one rule fenced by the states, between states is all of them at once, and between
 * moments is *either* ("la pastilla a las 9 y a las 21" rings twice: "todos" needs saying);
 * "y sólo si" fences the rule before it; "a la vez" and "las dos cosas" say which reading in so
 * many words. Anything else — "o" and "y" in one sentence, a fence with nothing to fence — is
 * more than one reading, and a person decides.
 */

/** How two clauses are joined. */
internal enum class Join { AND, OR, ONLY_IF, AT_ONCE, ALL }

/** One thing a clause says. */
internal sealed interface Piece {
    /**
     * A trigger: a moment, or a state ([Trigger.asState] says which). [twin] is the other
     * reading of a bare hour in it, when the words left the half of the day open.
     */
    data class When(val trigger: Trigger, val twin: Trigger? = null) : Piece

    /** A condition that is never a trigger of its own: a speed, days of the month, days of the week after "sólo si". */
    data class Fence(val condition: Condition) : Piece

    /** Something that comes back; [twin] is the same calendar at the other half of an open hour. */
    data class Comes(val recurrence: Recurrence, val twin: Recurrence? = null) : Piece

    data class Bound(val deadline: Deadline) : Piece

    /** "Y cuando lleve diez minutos allí": a rate for the doorway before it. */
    data class Stay(val minutes: Int) : Piece

    /** "Cuando me vaya de aquí" with no position yet to draw it around. */
    data object Here : Piece
}

/** A piece and where in the text (as given) it was said. */
internal class Said(val piece: Piece, val spans: List<IntRange>)

/** What the grammar made of a sentence's "when". */
internal sealed interface Composed {
    class Rules(
        val rules: List<TriggerRule>,
        val match: RuleMatch,
        val recurrence: Recurrence,
        val deadline: Deadline?,
        /** Everything that was the "when" — clauses, joins, markers — to take out of the words. */
        val spans: List<IntRange>,
        /** The same stretches, each with the family of "when" it was read as (null for a join or a marker). */
        val parts: List<HeardSpan> = emptyList(),
        /** The other reading of an open hour, as rules and a recurrence, when it is a waking one too. */
        val alternatives: List<Pair<List<TriggerRule>, Recurrence>> = emptyList(),
    ) : Composed

    data object NeedsHere : Composed

    /** Understood in parts, but the parts do not come together as one reading. */
    data object Ambiguous : Composed

    /** No "when" at all. */
    data object Nothing : Composed
}

/** What reading needs besides the words. */
internal class Context(
    val now: Instant,
    val zone: ZoneId,
    val settings: AppSettings,
    val here: Fix?,
    val hereLabel: String,
)

/**
 * The "when" of [text], composed. [skip] are stretches already accounted for (the lead-in, the
 * actions) that no reader may claim.
 */
internal fun composeWhen(text: String, skip: List<IntRange>, context: Context): Composed {
    val blank = mask(text, skip)
    val folded = Words.of(blank)
    val markers = MARKERS.mapNotNull { (pattern, match) -> pattern.find(folded.text)?.let { match to folded.original(it.range) } }
    val unmarked = mask(blank, markers.map { it.second })
    val joins = joinsIn(Words.of(unmarked))
    // Every stretch between two joins, read as a clause.
    val bounds = listOf(-1) + joins.flatMap { listOf(it.range.first, it.range.last) } + listOf(unmarked.length)
    val clauses = bounds.chunked(2).map { (end, next) -> (end + 1) until next }
    val readings = mutableListOf<List<Said>>()
    clauses.forEach { clause ->
        readings += readClause(unmarked, clause, context, fence = false).ifEmpty { elliptic(unmarked, clause, readings.lastOrNull(), context) }
    }
    // A join with no "when" on one side of it joins words, not clauses: those clauses are one.
    val blocks = mutableListOf(Block(clauses.first(), null, readings.first()))
    joins.forEachIndexed { index, join ->
        val counts = touchesEnd(unmarked, clauses[index], readings[index]) && touchesStart(unmarked, clauses[index + 1], readings[index + 1])
        if (counts) {
            blocks += Block(clauses[index + 1], join, readings[index + 1])
        } else {
            val last = blocks.removeAt(blocks.lastIndex)
            blocks += Block(last.range.first until clauses[index + 1].last + 1, last.join, said = null)
        }
    }
    // Read again only what the first pass could not have read right: a merged block, and one
    // after "sólo si", where weekdays and parts of the day are fences.
    val read = mutableListOf<Block>()
    blocks.forEach { block ->
        val said = block.said?.takeIf { block.join?.join != Join.ONLY_IF }
            ?: readClause(unmarked, block.range, context, fence = block.join?.join == Join.ONLY_IF)
                .ifEmpty { elliptic(unmarked, block.range, read.lastOrNull()?.said, context) }
        read += block.copy(said = said)
    }
    if (read.all { it.said.isNullOrEmpty() }) return Composed.Nothing
    // A block that read nothing between two that did is words in the middle of the "when".
    if (read.any { it.said.isNullOrEmpty() && it.join != null }) return Composed.Ambiguous
    val spans = read.flatMap { block -> block.said.orEmpty().flatMap { it.spans } + listOfNotNull(block.join?.range) } + markers.map { it.second }
    val parts = read.flatMap { block ->
        block.said.orEmpty().flatMap { said -> said.spans.map { HeardSpan(it, said.piece.family) } } + listOfNotNull(block.join?.range?.let { HeardSpan(it, null) })
    } + markers.map { HeardSpan(it.second, null) }
    val override = markers.map { it.first }.distinct().singleOrNull()
    if (markers.map { it.first }.distinct().size > 1) return Composed.Ambiguous
    return when (val composed = rulesOf(read.filter { !it.said.isNullOrEmpty() }, override, context, spans)) {
        is Composed.Rules -> Composed.Rules(composed.rules, composed.match, composed.recurrence, composed.deadline, composed.spans, parts, composed.alternatives)
        else -> composed
    }
}

/** Which family of "when" a piece was, for the sheet to colour where it was said. */
private val Piece.family: TriggerFamily
    get() = when (this) {
        is Piece.When -> trigger.family
        is Piece.Fence -> if (condition is Condition.AtPlace || condition is Condition.Moving) TriggerFamily.PLACE else TriggerFamily.TIME
        is Piece.Stay, Piece.Here -> TriggerFamily.PLACE
        is Piece.Comes, is Piece.Bound -> TriggerFamily.TIME
    }

/** A trigger said beside a rule's event: whether a join brought it, and the other reading of an open hour in it. */
private data class Side(val trigger: Trigger, val joined: Boolean, val twin: Trigger?)

/** A stretch read as one clause, the join before it, and what it said (null until read). */
private data class Block(val range: IntRange, val join: Joined?, val said: List<Said>?)

/** A join as found: what it does and where it was said (in the text as given). */
private class Joined(val join: Join, val range: IntRange)

// --- the joins ---

private fun joinsIn(folded: Words): List<Joined> {
    val words = folded.text
    val held = HOLDS_A_Y.flatMap { pattern -> pattern.findAll(words).map { it.range } }
    val taken = mutableListOf<IntRange>()
    val found = mutableListOf<Pair<Join, IntRange>>()
    for ((pattern, join) in JOINS) {
        for (match in pattern.findAll(words)) {
            // The pattern carries a space either side; the join is the words between.
            val range = (match.range.first + 1)..(match.range.last - 1)
            if (taken.any { it.overlaps(range) } || held.any { it.overlaps(range) }) continue
            taken += range
            found += join to range
        }
    }
    return found.sortedBy { it.second.first }
        .map { (join, range) -> Joined(join, folded.original(range)) }
        .filterNot { it.range.isEmpty() }
}

private fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last

/** Longest first: "y sólo si" before "y", "o bien" before "o". */
private val JOINS: List<Pair<Regex, Join>> = listOf(
    "y solo si", "pero solo si", "solo si", "y siempre que", "siempre que", "but only if", "and only if", "only if", "as long as",
).map { Regex(" $it ") to Join.ONLY_IF } + listOf(
    "y a la vez", "a la vez que", "a la vez", "al mismo tiempo que", "y al mismo tiempo", "al mismo tiempo", "at the same time as", "at the same time", "and at once",
).map { Regex(" $it ") to Join.AT_ONCE } + listOf(
    "y luego", "y despues", "y mas tarde", "and then", "and after that",
).map { Regex(" $it ") to Join.ALL } + listOf(
    "o bien", "o si no", "or else", "o", "u", "or",
).map { Regex(" $it ") to Join.OR } + listOf(
    "y tambien", "y", "e", "and",
).map { Regex(" $it ") to Join.AND }

/** The phrases with a "y" of their own, which is never a join. */
private val HOLDS_A_Y: List<Regex> = listOf(
    Regex("\\bentre (?:las? |el )?\\S+ y (?:las? |el )?\\S+"),
    Regex("\\bbetween \\S+ and \\S+"),
    Regex("\\by (?:media|cuarto)\\b"),
    Regex("\\b(?:lunes|martes|miercoles|jueves|viernes|sabado|domingo)s? (?:y|e) (?:los |el )?(?:lunes|martes|miercoles|jueves|viernes|sabado|domingo)s?\\b"),
    Regex("\\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)s? and (?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)s?\\b"),
    Regex("\\bdias? \\d{1,2}(?:, \\d{1,2})* y \\d{1,2}\\b"),
)

/**
 * The whole set said in so many words, wherever it is said: "…, las dos cosas", "lo que pase
 * antes", "a la vez" at the very end. One of them at most.
 */
private val MARKERS: List<Pair<Regex, RuleMatch>> = listOf(
    Regex("\\b(?:cuando (?:hayan pasado|pasen|ocurran) )?(?:las dos cosas|ambas cosas|todas ellas)\\b|\\bonce both(?: have happened)?\\b|\\bafter both\\b") to RuleMatch.ALL,
    Regex("\\blo que (?:pase|ocurra|llegue) (?:antes|primero)\\b|\\blo primero que (?:pase|ocurra)\\b|\\bcualquiera de (?:las dos|los dos|ellas)\\b|\\bwhichever (?:comes|happens) first\\b") to RuleMatch.ANY,
    Regex("\\b(?:las dos |todo )?a la vez $|\\b(?:las dos |todo )?al mismo tiempo $|\\b(?:both )?at the same time $") to RuleMatch.TOGETHER,
)

/** Whether a "when" in [said] reaches the clause's last word, small words aside. */
private fun touchesEnd(text: String, clause: IntRange, said: List<Said>): Boolean =
    edgeToken(text, clause, last = true)?.let { token -> said.any { piece -> piece.spans.any { it.overlaps(token) } } } ?: false

/** Whether a "when" in [said] starts at the clause's first word, small words aside. */
private fun touchesStart(text: String, clause: IntRange, said: List<Said>): Boolean =
    edgeToken(text, clause, last = false)?.let { token -> said.any { piece -> piece.spans.any { it.overlaps(token) } } } ?: false

private fun edgeToken(text: String, clause: IntRange, last: Boolean): IntRange? {
    if (clause.isEmpty()) return null
    val tokens = WORD.findAll(text.substring(clause.first, clause.last + 1)).map { (it.range.first + clause.first)..(it.range.last + clause.first) }
        // Small words and bare punctuation ("y, a la vez, mientras…") are not the clause's edge.
        .filterNot { Words.of(text.substring(it.first, it.last + 1)).text.trim().let { word -> word.isEmpty() || word in FILLERS } }
        .toList()
    return if (last) tokens.lastOrNull() else tokens.firstOrNull()
}

private val WORD = Regex("\\S+")

/** What may stand between a join and the "when" it joins: "y sólo si **es** por la tarde". */
private val FILLERS = setOf(
    "el", "la", "los", "las", "lo", "de", "del", "a", "al", "en", "que", "es", "sea", "si", "este", "esta", "estes", "estoy", "ya",
    "the", "on", "at", "in", "it", "is", "s", "i", "m", "am", "if", "when",
)

/**
 * "Cuando esté en el gimnasio **o en la oficina**": a place said with only its preposition, after
 * a clause that said one in full, is read the way that one was — the same side of the line, the
 * same doorway or state. Only a saved place, as always.
 */
private fun elliptic(text: String, clause: IntRange, before: List<Said>?, context: Context): List<Said> {
    val previous = before?.lastOrNull { (it.piece as? Piece.When)?.trigger is Trigger.Location }?.let { (it.piece as Piece.When).trigger as Trigger.Location }
        ?: return emptyList()
    if (clause.isEmpty()) return emptyList()
    val chars = CharArray(text.length) { ' ' }
    for (i in clause) chars[i] = text[i]
    val folded = Words.of(String(chars))
    val words = folded.text
    val lead = ELLIPSIS.find(words) ?: return emptyList()
    val (place, end) = savedPlaceAt(words, lead.range.last + 1, context.settings.savedPlaces) ?: return emptyList()
    val rule = previous.copy(lat = place.lat, lng = place.lng, radiusM = place.radiusM, label = place.label, dwellMinutes = null, placeId = place.id.ifBlank { null })
    return listOf(Said(Piece.When(rule), listOf(folded.original(lead.range.first..end))))
}


/**
 * Two days of the calendar said as a stretch: each with its month or one month for both, and
 * none at all meaning this month (or the next, once the first day has gone). The year is the one
 * that keeps the stretch from being behind us.
 */
private fun dateSpanOf(today: java.time.LocalDate, fromDay: String, fromMonth: String, toDay: String, toMonth: String): Pair<java.time.LocalDate, java.time.LocalDate>? {
    val first = fromDay.toIntOrNull() ?: return null
    val last = toDay.toIntOrNull() ?: return null
    val month1 = monthNamed(fromMonth)
    val month2 = monthNamed(toMonth)
    fun date(year: Int, month: Int, day: Int) = runCatching { java.time.LocalDate.of(year, month, day) }.getOrNull()
    /** The next [day] of [month] from today on, this year or the next. */
    fun next(month: Int, day: Int) = date(today.year, month, day)?.takeIf { !it.isBefore(today) } ?: date(today.year + 1, month, day)
    // The start: its own month; else the end's (the month before when the start's day is
    // after the end's — "del 20 al 5 de noviembre" starts in October); else this month while the
    // day is still ahead, the next once it has gone.
    val from = when {
        month1 != null -> next(month1, first)
        month2 != null -> next(if (first <= last) month2 else (month2 + 10) % 12 + 1, first)
        else -> date(today.year, today.monthValue, first)?.takeIf { !it.isBefore(today) } ?: today.plusMonths(1).let { date(it.year, it.monthValue, first) }
    } ?: return null
    // The end: its own month in the start's year (or the next); else the start's month while its
    // day is not before the start's, the month after once it is — "del 20 de octubre al 5" ends
    // in November, not a year on.
    val to = if (month2 != null) {
        date(from.year, month2, last)?.let { if (it.isBefore(from)) date(from.year + 1, month2, last) else it }
    } else {
        date(from.year, from.monthValue, last)?.takeIf { !it.isBefore(from) } ?: from.plusMonths(1).let { date(it.year, it.monthValue, last) }
    } ?: return null
    return if (to.isBefore(from)) null else from to to
}

/** A clause that starts with nothing but a preposition: the place it names is said the way the one before was. */
private val ELLIPSIS = Regex("^ (?:en|a|al|de|del|at|to|from|in)\\b")

// --- a clause ---

/**
 * Every "when" in [range] of [text], read one after another until nothing is left to read: the
 * readers that only make sense in a sentence said aloud first, then the ones the editor's chip
 * uses ([readWhen]). [fence] says the clause came after "sólo si": weekdays there are a fence,
 * not a series.
 */
private fun readClause(text: String, range: IntRange, context: Context, fence: Boolean): List<Said> {
    if (range.isEmpty()) return emptyList()
    val chars = CharArray(text.length) { ' ' }
    for (i in range) chars[i] = text[i]
    val words = Words.of(String(chars)).text
    // A part of the day beside a place, or after "sólo si", is a stretch of hours and not an
    // hour ("al llegar a casa por la tarde"); alone or with a day it is the hour the person
    // keeps for it, as the chip reads it ("mañana por la tarde").
    val partsAreWindows = fence || ((mentionsPlace(words) || HERE.containsMatchIn(words) || mentionsStretch(words)) && !namesClockHour(words))
    // A kind of day ("entre semana", "los días 1 y 15", "de lunes a viernes") is a fence where a
    // fence is being said: after "sólo si", or beside a place. Anywhere else it is a series the
    // chip's own readers know ("fichar entre semana a las 8" is every working day at eight), or
    // part of the thing ("planes para los fines de semana") — never a one-off fenced by it (0.181.0).
    val kindsOfDay = fence || mentionsPlace(words) || HERE.containsMatchIn(words)
    val said = mutableListOf<Said>()
    repeat(MAX_PIECES) {
        // Beside a "when" already read, a kind of day is part of saying when: "entre las 18 y las
        // 20 entre semana".
        val next = readOne(String(chars), context, fence, partsAreWindows, kindsOfDay || said.isNotEmpty()) ?: return said
        said += next
        next.spans.forEach { span -> for (i in span) chars[i] = ' ' }
    }
    return said
}

private const val MAX_PIECES = 6

private fun readOne(text: String, context: Context, fence: Boolean, partsAreWindows: Boolean, kindsOfDay: Boolean): Said? {
    val folded = Words.of(text)
    val words = folded.text
    if (words.isBlank()) return null
    fun said(piece: Piece, match: MatchResult) = Said(piece, listOf(folded.original(match.range)))
    val settings = context.settings
    // "Al llevar 10 minutos en casa" — the doorway and its rate in one breath, which is how the
    // app itself says it back ("al llevar 10 min en Casa").
    STAY_AT.find(words)?.let { m ->
        val amount = amountIn(m.groupValues[1]) ?: return@let
        val minutes = if (m.groupValues[2].startsWith("h")) amount * 60 else amount
        val (place, end) = savedPlaceAt(words, m.range.last + 1, settings.savedPlaces) ?: return@let
        val outside = m.groupValues[3].startsWith("fuera") || m.groupValues[3] == "away from"
        val rule = Trigger.Location(
            place.lat, place.lng, place.radiusM, if (outside) Presence.OUTSIDE else Presence.INSIDE, place.label,
            onCrossing = true, dwellMinutes = minutes, placeId = place.id.ifBlank { null },
        )
        return Said(Piece.When(rule), listOf(folded.original(m.range.first..end)))
    }
    // "When I have been at Home for 10 min": the English the app says it back in, the place first.
    STAY_AT_EN.find(words)?.let { m ->
        val (place, end) = savedPlaceAt(words, m.range.last + 1, settings.savedPlaces) ?: return@let
        val rate = STAY_FOR.find(words.substring(end + 1))?.takeIf { it.range.first == 0 } ?: return@let
        val amount = amountIn(rate.groupValues[1]) ?: return@let
        val minutes = if (rate.groupValues[2].startsWith("h")) amount * 60 else amount
        val rule = Trigger.Location(
            place.lat, place.lng, place.radiusM, if (m.groupValues[1] == "away from") Presence.OUTSIDE else Presence.INSIDE, place.label,
            onCrossing = true, dwellMinutes = minutes, placeId = place.id.ifBlank { null },
        )
        return Said(Piece.When(rule), listOf(folded.original(m.range.first..end + rate.range.last + 1)))
    }
    STAY.find(words)?.let { m ->
        val amount = amountIn(m.groupValues[1]) ?: return@let
        val minutes = if (m.groupValues[2].startsWith("h")) amount * 60 else amount
        return said(Piece.Stay(minutes), m)
    }
    for ((pattern, kind) in if (fence) MOVING + MOVING_BARE else MOVING) pattern.find(words)?.let { return said(Piece.Fence(movingOf(kind)), it) }
    TIMER.find(words)?.let { m ->
        // Three ways of saying it, each with its own amount and unit: the pair that was said.
        val (amountRaw, unit) = m.groupValues.drop(1).chunked(2).first { it[0].isNotEmpty() }
        val amount = amountIn(amountRaw) ?: return@let
        return said(Piece.Bound(Deadline.Timer(if (unit.startsWith("h")) amount * 60 else amount)), m)
    }
    val today = context.now.atZone(context.zone).toLocalDate()
    val shape = settings.dayShape
    BEFORE.find(words)?.let { m ->
        val hour = awakeHour(m.groupValues[1], m.groupValues[2], m.qualifier(3), shape, today) ?: return@let
        return said(Piece.Bound(Deadline.Window(LocalTime.MIDNIGHT, hour)), m)
    }
    RANDOM.find(words)?.let { m ->
        val times = (if (m.groupValues[1].isEmpty()) ONCE_TWICE[m.groupValues[2]] else amountIn(m.groupValues[1])) ?: return@let
        val period = if (m.groupValues[3].startsWith("sem") || m.groupValues[3] == "week") Period.WEEK else Period.DAY
        // A window said and not readable is no reading at all, never the default hours instead.
        val window = if (m.groupValues[4].isEmpty()) null else windowOf(m.groupValues[4], m.groupValues[5], m.qualifier(6), m.groupValues[8], m.groupValues[9], m.qualifier(10), shape, today) ?: return@let
        return said(Piece.When(Trigger.Random(times, period, window?.from ?: RANDOM_FROM, window?.to ?: RANDOM_TO)), m)
    }
    // "Del 1 al 15 de octubre", "entre el 1 y el 15": a stretch of the calendar, both ends in it.
    for (pattern in DATE_SPANS) {
        pattern.find(words)?.let { m ->
            val today = context.now.atZone(context.zone).toLocalDate()
            val span = dateSpanOf(today, m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]) ?: return@let
            return said(Piece.When(Trigger.DateRange(span.first, span.second)), m)
        }
    }
    // "Antes del viernes", "hasta el 15": the days from today to it — before it, or up to it.
    UNTIL_DAY.find(words)?.let { m ->
        val today = context.now.atZone(context.zone).toLocalDate()
        val before = m.groupValues[1].startsWith("antes") || m.groupValues[1] == "before"
        // The first such day that leaves at least today in the stretch: "antes del 15" said on
        // the 15th is next month's.
        fun fits(day: java.time.LocalDate) = if (before) day.isAfter(today) else !day.isBefore(today)
        val month = monthNamed(m.groupValues[4])
        val target = weekdayNamed(m.groupValues[2])?.let { today.with(java.time.temporal.TemporalAdjusters.next(it)) }
            ?: m.groupValues[3].toIntOrNull()?.let { day ->
                if (month != null) {
                    (0..1).firstNotNullOfOrNull { year -> runCatching { java.time.LocalDate.of(today.year + year, month, day) }.getOrNull()?.takeIf(::fits) }
                } else {
                    (0L..2L).firstNotNullOfOrNull { ahead -> runCatching { today.plusMonths(ahead).withDayOfMonth(day) }.getOrNull()?.takeIf(::fits) }
                }
            }
            ?: return@let
        return said(Piece.When(Trigger.DateRange(today, if (before) target.minusDays(1) else target)), m)
    }
    for (pattern in WINDOWS) {
        pattern.find(words)?.let { m ->
            if (countsSomething(words, m)) return@let
            val window = windowOf(m.groupValues[1], m.groupValues[2], m.qualifier(3), m.groupValues[5], m.groupValues[6], m.qualifier(7), shape, today) ?: return@let
            return said(Piece.When(Trigger.Interval(window.from, window.to)), m)
        }
    }
    // Kinds of day and days of the month are fences, read where a fence is being said: after
    // "sólo si", or beside a place. Elsewhere "los fines de semana" is as likely to be part of
    // the thing ("planes para los fines de semana") as a "when".
    if (kindsOfDay) {
        DAYS_OF_MONTH.find(words)?.let { m ->
            val days = MONTH_DAY_NUMBER.findAll(m.value).map { it.value.toInt() }.toSet()
            return said(Piece.Fence(Condition.OnMonthDays(days)), m)
        }
        for ((pattern, days) in DAY_KINDS) pattern.find(words)?.let { return said(Piece.When(Trigger.Weekday(days)), it) }
        weekdayRunIn(words)?.let { (m, days) -> return said(Piece.Fence(Condition.OnDays(days)), m) }
    }
    if (fence) {
        WEEKDAY_NAMES.find(words)?.let { m ->
            val days = WEEKDAY_NAME.findAll(m.value).mapNotNull { weekdayNamed(it.groupValues[1]) }.toSet()
            return said(Piece.Fence(Condition.OnDays(days)), m)
        }
    }
    // A stretch of the day the person keeps under a name: "a la hora de comer".
    settings.savedWindows.sortedByDescending { it.label.length }.forEach { window ->
        val name = Words.of(window.label).text.trim()
        if (name.isEmpty()) return@forEach
        val at = words.indexOf(" $name ")
        if (at >= 0) return Said(Piece.When(Trigger.Interval(window.from, window.to)), listOf(folded.original((at + 1)..(at + name.length))))
    }
    if (partsAreWindows) {
        PART.find(words)?.let { m ->
            val window = partWindow(m.groupValues[2], settings.dayParts)
            val today = m.groupValues[1] in TODAY_WORDS || m.value == "tonight"
            val trigger = if (today) Trigger.DayRandom(context.now.atZone(context.zone).toLocalDate(), window) else Trigger.Interval(window.from, window.to)
            return said(Piece.When(trigger), m)
        }
    }
    HERE.find(words)?.let { m ->
        val fix = context.here ?: return said(Piece.Here, m)
        return said(Piece.When(hereCircle(fix, context.hereLabel)), m)
    }
    // "Hoy" alone, said to a phone, is some time today: what is left of it (the chip, reading
    // typed words, still wants an hour — "hoy he comprado pan" is not a reminder).
    val reading = readWhen(text, context.now, context.zone, settings.dayParts, settings.savedPlaces, settings.dayShape)
        ?: return TODAY_ALONE.find(words)?.let { said(Piece.When(Trigger.DayRandom(today)), it) }
    val piece = when (val understood = reading.understood) {
        is Understood.Once -> Piece.When(understood.trigger, (reading.twin as? Understood.Once)?.trigger)
        is Understood.Comes -> Piece.Comes(understood.recurrence, (reading.twin as? Understood.Comes)?.recurrence)
    }
    return Said(piece, reading.spans)
}

/**
 * Two hours said as a stretch, each with its own qualifier or one for both: "de 5 a 7 de la
 * tarde". With no qualifier at all the stretch is read **as a whole, and awake** (the owner's
 * rule for a bare hour): of "as said", "the end twelve hours on" ("de 10 a 2" is ten till two) and
 * "both twelve hours on" ("de 5 a 7" is the afternoon's), the first that runs forwards inside the
 * person's waking hours; failing that, the first that runs forwards at all.
 */
private fun windowOf(
    fromRaw: String, fromMinutes: String, fromQualifier: String,
    toRaw: String, toMinutes: String, toQualifier: String,
    shape: DayShape, today: java.time.LocalDate,
): DayWindow? {
    if (fromRaw.isEmpty() || toRaw.isEmpty()) return null
    val qualifier = toQualifier.ifEmpty { fromQualifier }
    val from = hourOf(fromRaw, fromMinutes, fromQualifier.ifEmpty { qualifier }, late = false) ?: return null
    val to = hourOf(toRaw, toMinutes, toQualifier.ifEmpty { qualifier }, late = false) ?: return null
    if (from == to) return null
    if (qualifier.isNotEmpty()) return DayWindow(from, to)
    // Forwards, or up to midnight: a stretch that ends at twelve at night ends with the day.
    fun forwards(start: LocalTime, end: LocalTime) = end > start || end == LocalTime.MIDNIGHT
    val readings = listOfNotNull(
        from to to,
        (from to to.plusHours(12)).takeIf { to < from && to.plusHours(12) > from },
        (from.plusHours(12) to to.plusHours(12)).takeIf { isOpenHour(fromRaw) && isOpenHour(toRaw) },
    )
    fun awake(start: LocalTime, end: LocalTime) =
        shape.awakeAt(today.atTime(start)) && shape.awakeAt((if (end > start) today else today.plusDays(1)).atTime(end).minusMinutes(1))
    // The first that runs forwards inside waking hours; then the first that runs forwards; and
    // failing both, the stretch as said, overnight ("de 22 a 2").
    val (start, end) = readings.firstOrNull { (a, b) -> forwards(a, b) && awake(a, b) }
        ?: readings.firstOrNull { (a, b) -> forwards(a, b) }
        ?: readings.first()
    return DayWindow(start, end)
}

/** A bare hour of a deadline ("antes de las 5"): the waking one of five and seventeen, as everywhere. */
private fun awakeHour(raw: String, minutes: String, qualifier: String, shape: DayShape, today: java.time.LocalDate): LocalTime? {
    val time = hourOf(raw, minutes, qualifier, late = false) ?: return null
    return if (qualifier.isEmpty() && isOpenHour(raw)) shape.wakingOf(time, time.plusHours(12), today) else time
}

/**
 * Whether the words right after a bare range ("de 10 a 15 …") count something — "flexiones",
 * "kilos", "días" — which makes the numbers that thing's amount and not hours. A range with an
 * article ("entre las 5 y las 7"), minutes or a qualifier is hours whatever follows.
 */
private fun countsSomething(words: String, match: MatchResult): Boolean {
    if (HOURS_SAID.containsMatchIn(match.value)) return false
    val next = WORD.find(words, match.range.last + 1)?.value ?: return false
    return next !in FILLERS && next !in ALONG && !next.first().isDigit()
}

/** What makes a range hours beyond doubt: "las", a clock, a qualifier. */
private val HOURS_SAID = Regex("\\blas? |[:h]\\d{2}|\\bde la |\\bin the |\\b[ap]\\.? ?m\\b")

/** The words that may follow a stretch of hours and are part of saying when: "de 5 a 7 los lunes", "… cuando llegue". */
private val ALONG = setOf("los", "y", "o", "cuando", "mientras", "al", "si", "every", "on", "and", "or", "when", "if", "while", "cada", "todos", "todas", "solo")

/** The stretch a part of the day stands for: from its hour to the next part's ("la tarde" is from the afternoon to the evening). */
private fun partWindow(part: String, parts: DayParts): DayWindow = when (part) {
    "manana", "morning" -> DayWindow(parts.morning, LocalTime.NOON)
    "tarde", "afternoon" -> DayWindow(parts.afternoon, parts.evening)
    else -> DayWindow(parts.evening, LocalTime.MIDNIGHT)
}

private val TODAY_WORDS = setOf("esta", "this")

private val TODAY_ALONE = Regex("\\b(?:hoy|today)\\b")

/** The hours a random draw opens on when none are said: what the random sheet opens on. */
private val ONCE_TWICE = mapOf("once" to 1, "twice" to 2)

private val RANDOM_FROM: LocalTime = LocalTime.of(10, 0)
private val RANDOM_TO: LocalTime = LocalTime.of(20, 0)

private val HOUR = HOUR_WORDS_PATTERN
private const val MINS = "(?:[:.h](\\d{2}))?"
/**
 * What qualifies an hour, as two groups: a part of the day after "de la"/"in the", or am/pm with
 * or without its space ("10pm"). Never a bare "mañana" after a number — that is tomorrow.
 */
private const val QUAL = "(?: (?:de la|in the) (manana|tarde|noche|morning|afternoon|evening)| ?(a\\.? ?m|p\\.? ?m)\\.?)?"

/** After an hour that may end in a dot ("p.m."): a space or a full stop, as `\\b` cannot follow a dot. */
private const val AFTER = "(?=[\\s.])"

/** The two qualifier groups starting at [index], as the one qualifier said. */
private fun MatchResult.qualifier(index: Int): String = groupValues[index].ifEmpty { groupValues[index + 1] }

/** "Si es", "cuando sea", "if it's": how a condition is led in, which goes with it. */
private const val IF_IT_IS = "(?:(?:si|cuando|mientras|if|when|while) )?(?:(?:es|sea|estoy|este|it s|it is|i m|i am) )?"

/**
 * "Entre las 5 y las 7", "de 17 a 19", "between 5 and 7", "from 5 to 7pm". Eight groups: hour,
 * minutes, two qualifiers, twice. A bare "de 10 a 15" followed by a word that counts something
 * ("flexiones", "kilos", "días") is that thing's amount, not hours: see [countsSomething].
 */
private val WINDOWS: List<Regex> = listOf(
    Regex("\\b${IF_IT_IS}entre (?:las? )?$HOUR$MINS$QUAL y (?:las? )?$HOUR$MINS$QUAL$AFTER"),
    Regex("\\b${IF_IT_IS}de (?:las? )?$HOUR$MINS$QUAL a (?:las? )?$HOUR$MINS$QUAL$AFTER"),
    Regex("\\b${IF_IT_IS}between $HOUR$MINS$QUAL and $HOUR$MINS$QUAL$AFTER"),
    Regex("\\b${IF_IT_IS}from $HOUR$MINS$QUAL to $HOUR$MINS$QUAL$AFTER"),
)

/** "Del 1 al 15 de octubre", "entre el 1 y el 15", "from the 1st to the 15th of october". Four groups: day, month, day, month. */
private val DATE_SPANS: List<Regex> = listOf(
    // A month on at least one end: "del 1 al 5" alone is as likely to be pages, chapters or
    // themes as days, and a bare number is never guessed at.
    Regex("\\b(?:del|desde el) (\\d{1,2})(?: de $MONTH_WORDS_PATTERN)? (?:al|hasta el) (\\d{1,2}) de $MONTH_WORDS_PATTERN\\b"),
    Regex("\\b(?:del|desde el) (\\d{1,2}) de $MONTH_WORDS_PATTERN (?:al|hasta el) (\\d{1,2})()\\b"),
    Regex("\\bentre el (\\d{1,2})(?: de $MONTH_WORDS_PATTERN)? y el (\\d{1,2}) de $MONTH_WORDS_PATTERN\\b"),
    Regex("\\bfrom the (\\d{1,2})(?:st|nd|rd|th)?(?: of $MONTH_WORDS_PATTERN)? (?:to|until) the (\\d{1,2})(?:st|nd|rd|th)? of $MONTH_WORDS_PATTERN\\b"),
)

/** "Al llevar 10 minutos en casa", "al llevar media hora fuera de la oficina". Groups: amount, unit, side. */
private val STAY_AT = Regex("\\bal llevar ${AMOUNT_WORDS_PATTERN} (min|mins|minutos?|horas?) (en|dentro de|fuera de|fuera del)\\b")

private val STAY_AT_EN = Regex("\\bwhen i (?:have |ve )?been (at|in|away from)\\b")
private val STAY_FOR = Regex("^ for ${AMOUNT_WORDS_PATTERN} (min|mins|minutes?|hours?)\\b")

/** "Antes del viernes", "hasta el 15 de marzo", "before friday", "by the 15th". Groups: lead, weekday, day of the month, month. */
private val UNTIL_DAY = Regex(
    "\\b(antes del?|hasta el|before|by|until)(?: proximo| next| the)? (?:$WEEKDAY_WORDS_PATTERN|(?:dia )?(\\d{1,2})(?:st|nd|rd|th)?(?: (?:de|of) $MONTH_WORDS_PATTERN)?)\\b",
)

/** "Tres veces al día (entre las 10 y las 20)", "una vez a la semana", "twice a day". */
private val RANDOM = Regex(
    "\\b(?:${AMOUNT_WORDS_PATTERN} (?:vez|veces|times?)|(once|twice)) (?:al|por|cada|a la|a|per|every) (dia|semana|day|week)" +
        "(?: (?:entre (?:las? )?|between )$HOUR$MINS$QUAL (?:y|and) (?:las? )?$HOUR$MINS$QUAL)?$AFTER",
)

/** "Y cuando lleve diez minutos allí", "al llevar 10 minutos en casa", "after 10 minutes there". */
private val STAY = Regex(
    "\\b(?:(?:y )?(?:(?:cuando|en cuanto) )?(?:lleve|llevo|haya estado|haya pasado)|al llevar|after|once i ve been(?: there)? for) " +
        "${AMOUNT_WORDS_PATTERN} (min|mins|minutos?|minutes?|horas?|hours?)(?: (?:alli|ahi|alla|dentro|fuera|there|inside|outside))?\\b",
)

/**
 * "Si voy en coche", "cuando vaya andando", "if I'm driving": how the phone was moving, said as a
 * condition — with its "si" or "cuando", because "ir en coche a ver a Ana" is somebody's plan and
 * not a fence. After "sólo si" the bare words are enough ([MOVING_BARE]).
 */
private const val GOING = "(?:voy |vaya |vayas |vas |ando |este |estoy |vamos )?"
private val MOVING: List<Pair<Regex, MovingKind>> = listOf(
    Regex("\\b(?:si|cuando|mientras) ${GOING}(?:en (?:el )?coche|conduciendo|en moto)\\b|\\b(?:if|when|while) (?:i m |i am )?(?:driving|by car)\\b") to MovingKind.DRIVING,
    Regex("\\b(?:si|cuando|mientras) ${GOING}(?:andando|a pie|caminando)\\b|\\b(?:if|when|while) (?:i m |i am )?(?:walking|on foot)\\b") to MovingKind.ON_FOOT,
    Regex("\\b(?:si|cuando|mientras) ${GOING}en movimiento\\b|\\b(?:if|when|while) (?:i m |i am )?moving\\b") to MovingKind.ANY,
)
private val MOVING_BARE: List<Pair<Regex, MovingKind>> = listOf(
    Regex("\\b${GOING}(?:en (?:el )?coche|conduciendo|en moto)\\b|\\b(?:i m |i am )?(?:driving|by car)\\b") to MovingKind.DRIVING,
    Regex("\\b${GOING}(?:andando|a pie|caminando)\\b|\\b(?:i m |i am )?(?:walking|on foot)\\b") to MovingKind.ON_FOOT,
    Regex("\\b${GOING}en movimiento\\b|\\b(?:i m |i am )?moving\\b") to MovingKind.ANY,
)

/** "Y si en 2 horas no, olvídalo", "en menos de 2 horas desde el primero", "within 2 hours". */
private val TIMER = Regex(
    "\\b(?:(?:y )?si (?:en|dentro de) ${AMOUNT_WORDS_PATTERN} (min|mins|minutos?|horas?) no(?: (?:olvidalo|dejalo|nada|lo olvides))?|" +
        "en menos de ${AMOUNT_WORDS_PATTERN} (min|mins|minutos?|horas?)(?: desde el primero)?|within ${AMOUNT_WORDS_PATTERN} (minutes?|hours?))\\b",
)

/** "Antes de las 22", "como mucho hasta las 10", "before 10pm". Three groups: hour, minutes, qualifier. */
private val BEFORE = Regex("\\b(?:antes de las?|como mucho (?:hasta )?(?:las?)|by|before) $HOUR$MINS$QUAL$AFTER")

/**
 * "Los días 1 y 15", "el día 1 de cada mes": days of the month, a fence. A single "el día 5"
 * without "de cada mes" is a date ("el día 5 a las 9", "el día 5 de marzo"), and the date
 * readers have it.
 */
private val DAYS_OF_MONTH = Regex(
    "\\b$IF_IT_IS(?:(?:los|solo los) )?dias \\d{1,2}(?:(?:, | y )\\d{1,2})+(?: de cada mes| del mes)?\\b|" +
        "\\b$IF_IT_IS(?:(?:el|los|solo el) )?dias? \\d{1,2}(?:(?:, | y )\\d{1,2})* (?:de cada mes|de todos los meses)\\b",
)
private val MONTH_DAY_NUMBER = Regex("\\d{1,2}")

/** Days that are a kind of day: weekends, working days. A state for the whole of each. */
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
private val WORKDAYS = DayOfWeek.entries.toSet() - WEEKEND
private val DAY_KINDS: List<Pair<Regex, Set<DayOfWeek>>> = listOf(
    Regex("\\b$IF_IT_IS(?:(?:los|en|durante los) )?fines de semana\\b|\\b$IF_IT_IS(?:el )?fin de semana\\b|\\b$IF_IT_IS(?:(?:on|at) )?(?:the )?weekends?\\b") to WEEKEND,
    Regex("\\b$IF_IT_IS(?:entre semana|(?:(?:los|en) )?(?:dias )?laborables?|(?:dia )?laborable|on weekdays|weekdays|a weekday)\\b") to WORKDAYS,
)

private val WEEKDAY_NAME = Regex("\\b${WEEKDAY_WORDS_PATTERN}s?\\b")

/** After "sólo si": "es viernes", "los lunes y jueves". */
private val WEEKDAY_NAMES = Regex(
    "\\b$IF_IT_IS(?:(?:el|los|on) )?${WEEKDAY_WORDS_PATTERN}s?(?:(?:, | y | e | and | or | o )(?:el |los )?${WEEKDAY_WORDS_PATTERN}s?)*\\b",
)

/** A part of the day, as a stretch: "por la tarde", "esta noche", "in the morning", "tonight". Groups: lead, part. */
private val PART = Regex("\\b$IF_IT_IS(por la|esta|de la|this|in the|during the) (manana|tarde|noche|morning|afternoon|evening|night)\\b|\\btonight\\b")

/** "Aquí" said as a doorway out ([hereCircle]). */
internal val HERE = Regex(
    "\\b(?:(?:cuando|en cuanto|nada mas|apenas) (?:me vaya|me marche|salga|te vayas|salgas)|al (?:salir|irme|marcharme)) de aqui\\b|" +
        "\\b(?:when|once|as soon as|after) i (?:leave|get out of|go out of) (?:here|this place)\\b",
)

// --- the rules ---

/**
 * The blocks, composed into rules. Recurrence, deadline, rate and speed are taken out first —
 * each belongs to the set, or to the doorway said before it — and what is left is moments,
 * states and fences, joined.
 */
private fun rulesOf(blocks: List<Block>, override: RuleMatch?, context: Context, spans: List<IntRange>): Composed {
    if (blocks.any { block -> block.said.orEmpty().any { it.piece == Piece.Here } }) return Composed.NeedsHere
    // In order, each piece with the join that came before it: the first piece of a block takes
    // the block's join, and the others in the same block were said beside it with no join at all
    // (null) — which is not a join, so "a las 9 o al llegar a casa por la tarde" is one "o".
    val sequence = blocks.flatMap { block ->
        block.said.orEmpty().sortedBy { it.spans.minOf { span -> span.first } }
            .mapIndexed { index, said -> (if (index == 0) block.join?.join else null) to said.piece }
    }
    val recurrences = sequence.mapNotNull { (it.second as? Piece.Comes)?.recurrence }
    val deadlines = sequence.mapNotNull { (it.second as? Piece.Bound)?.deadline }
    if (recurrences.size > 1 || deadlines.size > 1) return Composed.Ambiguous
    val joins = blocks.mapNotNull { it.join?.join }
    val ors = joins.count { it == Join.OR }
    if (ors > 0 && joins.any { it == Join.AND || it == Join.AT_ONCE || it == Join.ALL }) return Composed.Ambiguous

    // Rules being built: an event, its fences, and the triggers said beside it with no join
    // ("al llegar a casa por la tarde"), which become fences or rules once the joins are known.
    // Each trigger beside the event carries whether a join brought it ("y") or it was merely said
    // next to it: two moments said side by side with nothing between them ("cuando llegue a casa
    // a las 7") are one sentence with two answers, not "either".
    class Draft(
        var event: Trigger?,
        val fences: MutableList<Condition> = mutableListOf(),
        val beside: MutableList<Side> = mutableListOf(),
        var twin: Trigger? = null,
    )
    val drafts = mutableListOf<Draft>()
    val today = context.now.atZone(context.zone).toLocalDate()
    val shape = context.settings.dayShape
    // As a state, and a saved place keeps its key when it becomes a fence, as one chosen in the
    // condition sheet does.
    fun stateOf(trigger: Trigger): Condition? = when (val state = settled(trigger, today).asState(shape)) {
        is Condition.AtPlace -> state.copy(placeId = (trigger as? Trigger.Location)?.placeId)
        else -> state
    }
    fun current() = drafts.lastOrNull() ?: Draft(null).also { drafts += it }
    var fencing = false
    for ((join, piece) in sequence) {
        if (join == Join.ONLY_IF) fencing = true else if (join != null && join != Join.AND) fencing = false
        when (piece) {
            is Piece.Comes, is Piece.Bound, Piece.Here -> Unit
            is Piece.Stay -> {
                val doorway = drafts.lastOrNull()?.event as? Trigger.Location
                if (doorway == null || !doorway.onCrossing) return Composed.Ambiguous
                drafts.last().event = doorway.copy(dwellMinutes = piece.minutes)
            }
            is Piece.Fence -> {
                // A speed is about the crossing of a line, so it needs a doorway to be about.
                if (piece.condition is Condition.Moving && (drafts.lastOrNull()?.event as? Trigger.Location)?.onCrossing != true) return Composed.Ambiguous
                current().fences += piece.condition
            }
            is Piece.When -> when {
                fencing -> current().fences += stateOf(piece.trigger) ?: return Composed.Ambiguous
                join == Join.OR || join == Join.AT_ONCE || join == Join.ALL || drafts.none { it.event != null } -> {
                    val orphan = drafts.lastOrNull()?.takeIf { it.event == null && drafts.size == 1 }
                    val draft = orphan?.also { it.event = piece.trigger } ?: Draft(piece.trigger).also { drafts += it }
                    draft.twin = piece.twin
                }
                else -> drafts.last().beside += Side(piece.trigger, joined = join == Join.AND, twin = piece.twin)
            }
        }
    }
    val real = drafts.filter { it.event != null }
    // Each rule built, by identity, with the other reading of an open hour in it (if any).
    val twinOf = java.util.IdentityHashMap<TriggerRule, Trigger?>()
    val orphanFences = drafts.filter { it.event == null }.flatMap { it.fences }
    // "Y" and nothing else between the pieces.
    val plainAnd = ors == 0 && joins.none { it == Join.AT_ONCE || it == Join.ALL }
    var match = when {
        ors > 0 -> RuleMatch.ANY
        joins.any { it == Join.AT_ONCE } -> RuleMatch.TOGETHER
        joins.any { it == Join.ALL } -> RuleMatch.ALL
        else -> RuleMatch.ANY
    }
    val built: List<TriggerRule> = if (plainAnd && real.size == 1) {
        // One moment and states is one fenced rule; only states, all of them at once; only
        // moments, either of them (the owner's reading: "a las 9 y a las 21" rings twice).
        val draft = real.single()
        val all = listOf(Side(draft.event!!, joined = true, twin = draft.twin)) + draft.beside
        val moments = all.filter { stateOf(it.trigger) == null }
        val states = all.filter { stateOf(it.trigger) != null }.map { it.trigger }
        when {
            moments.size == 1 -> listOf(TriggerRule(moments.single().trigger, states.map { stateOf(it)!! } + draft.fences).also { twinOf[it] = moments.single().twin })
            moments.isEmpty() && states.size == 1 -> listOf(TriggerRule(states.single(), draft.fences))
            // Days on the calendar never hold at once ("el lunes y el jueves"): either of them.
            moments.isEmpty() && states.all { settled(it, today) is Trigger.DayRandom } -> {
                if (draft.fences.isNotEmpty()) return Composed.Ambiguous
                states.map { TriggerRule(it) }
            }
            moments.isEmpty() -> {
                if (draft.fences.isNotEmpty()) return Composed.Ambiguous
                match = RuleMatch.TOGETHER
                states.map { TriggerRule(it) }
            }
            states.isEmpty() && draft.fences.isEmpty() && moments.drop(1).all { it.joined } ->
                moments.map { side -> TriggerRule(side.trigger).also { twinOf[it] = side.twin } }
            else -> return Composed.Ambiguous
        }
    } else {
        // Each joined piece its own rule, and what was said beside one is its fence.
        real.map { draft ->
            TriggerRule(draft.event!!, draft.beside.map { stateOf(it.trigger) ?: return Composed.Ambiguous } + draft.fences).also { twinOf[it] = draft.twin }
        }
    }
    // "A las 9 y a las 21": the 9 read alone came out as the evening's (the next nine to come
    // round), which the 21 already is. Two rules the same are one rule said twice; the one whose
    // hour was open is the other half of the day.
    val rules = built.toMutableList()
    for (i in rules.indices) {
        val other = rules.indices.firstOrNull { it != i && rules[it].trigger == rules[i].trigger } ?: continue
        // The one whose hour was open moves; when both were, the later one.
        val twin = twinOf[rules[i]] ?: continue
        if (twinOf[rules[other]] == null || other < i) rules[i] = rules[i].copy(trigger = twin)
    }
    // Still the same rule twice ("hoy a las 9 y a las 21", the morning one gone by): one of the
    // hours said is not in it, and a person decides.
    if (rules.map { it.trigger }.distinct().size < rules.size) return Composed.Ambiguous
    match = override ?: match
    if (rules.size < 2) match = RuleMatch.ANY
    // A fence with nothing to fence ("sólo si estoy en casa" and nothing else), or fences on a
    // set that is only a repeat, go to the calendar.
    var recurrence = recurrences.singleOrNull() ?: Recurrence.None
    if (orphanFences.isNotEmpty()) {
        val calendar = recurrence as? Recurrence.Calendar ?: return Composed.Ambiguous
        if (rules.isNotEmpty()) return Composed.Ambiguous
        recurrence = calendar.copy(conditions = calendar.conditions + orphanFences)
    }
    // "Cada año el 3 de marzo", "todos los meses el día 5": a calendar and the one date it was
    // said beside is where the calendar starts, and the date's hour is its hour.
    var fold: List<TriggerRule> = rules
    val calendar = recurrence as? Recurrence.Calendar
    val start = rules.singleOrNull()?.takeIf { it.conditions.isEmpty() }?.trigger?.let { settled(it, today) }
    // Only when the two agree on the hour: "a las 9 y a las 21 todos los días" is two hours a
    // day, which no calendar holds, and dropping one of them would be writing half of it.
    val startTime = (start as? Trigger.AtDateTime)?.at?.toLocalTime()
    // An open hour that agrees with the calendar's only because it was read as the evening's
    // ("a las 9 y a las 21 todos los días") is the other nine: two hours a day after all.
    val openTwin = rules.singleOrNull()?.let { twinOf[built.firstOrNull { b -> b.trigger == it.trigger }] } as? Trigger.AtDateTime
    // Moved: the open hour was read as the later half of the day (its twin is the earlier one).
    val openAgreement = openTwin != null && startTime != null && openTwin.at.toLocalTime() < startTime
    val agrees = start is Trigger.DayRandom ||
        (start is Trigger.AtDateTime && (calendar?.repeat?.time == null || (calendar.repeat.time == startTime && !openAgreement)))
    if (calendar != null && start != null && agrees) {
        val date = (start as? Trigger.AtDateTime)?.at?.toLocalDate() ?: (start as Trigger.DayRandom).date
        val time = startTime ?: calendar.repeat.time
        recurrence = calendar.copy(repeat = calendar.repeat.copy(startsOn = date, time = time))
        fold = emptyList()
    }
    // A repeat beside moments of its own is two answers to "when does it come back".
    if (recurrence.isAnchored && fold.isNotEmpty()) return Composed.Ambiguous
    // "Tres veces al día" is its own answer to that question.
    if (recurrence == Recurrence.None && fold.any { it.trigger is Trigger.Random }) recurrence = Recurrence.ByTrigger
    // A deadline bounds a set; beside one rule, "antes de las 22" is a fence on it.
    val deadline = deadlines.singleOrNull()
    if (deadline != null && !deadlineApplies(deadline, fold, match)) {
        val window = deadline as? Deadline.Window ?: return Composed.Ambiguous
        if (fold.isEmpty()) return Composed.Ambiguous
        return Composed.Rules(fold.map { it.copy(conditions = it.conditions + window.asCondition()) }, match, recurrence, null, spans)
    }
    if (fold.isEmpty() && recurrence == Recurrence.None) return Composed.Ambiguous
    // The other half of an open hour, offered when it is a waking hour too ("a las 8": tonight's,
    // or tomorrow morning's) — and only for one rule or one calendar, where it is one tap to
    // understand. When the other half is asleep the person's own hours already said which.
    val alternatives = buildList {
        val rule = fold.singleOrNull()?.takeIf { built.size == 1 && fold === rules }
        val twin = rule?.let { twinOf[built.single()] }
        if (rule != null && twin != null && awake(twin, today, shape)) add(listOf(rule.copy(trigger = twin)) to recurrence)
        val comes = sequence.mapNotNull { it.second as? Piece.Comes }.singleOrNull()
        val calendarTwin = (comes?.twin as? Recurrence.Calendar)?.takeIf { fold.isEmpty() && recurrence == comes.recurrence }
        if (calendarTwin != null && calendarTwin.repeat.time?.let { shape.awakeAt(calendarTwin.repeat.startsOn.atTime(it)) } == true) add(emptyList<TriggerRule>() to calendarTwin)
    }
    return Composed.Rules(fold, match, recurrence, deadline, spans, alternatives = alternatives)
}

/** Whether a moment, read as a date and an hour, falls in the person's waking hours. */
private fun awake(trigger: Trigger, today: java.time.LocalDate, shape: DayShape): Boolean =
    (settled(trigger, today) as? Trigger.AtDateTime)?.let { shape.awakeAt(it.at) } == true

/** A day counted ("mañana") as the day it is, so it can be asked whether it is a state. */
private fun settled(trigger: Trigger, today: java.time.LocalDate): Trigger =
    (trigger as? Trigger.RelativeDate)?.on(today) ?: trigger

/** [text] with [spans] blanked out, so no reader claims them and every offset stays put. */
internal fun mask(text: String, spans: List<IntRange>): String {
    if (spans.isEmpty()) return text
    val chars = text.toCharArray()
    spans.forEach { span -> for (i in span) if (i in chars.indices) chars[i] = ' ' }
    return String(chars)
}
