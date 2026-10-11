package dev.rwilco.model

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/*
 * A reminder said out loud.
 *
 * The words of a typed reminder stay as typed and their "when" is only ever offered
 * ([whenInText]); a sentence said to the phone is the opposite case — "comprar pan cuando llegue a
 * casa" is a reminder *and* its trigger in one breath, and the person who said it is not looking
 * at a form. So this reads the same "when" the chip reads, takes it out of the words, and hands
 * back what to write — or says plainly why it will not: nothing was understood, more was said
 * than was understood, or the "aquí" in it needs a position first. **A half-reading is never
 * written**: what is left of the words is read again for anything that still sounds like a
 * "when", and if something does, a person looks at it (the editor, with the words as said).
 */

/** What a sentence comes to once its "when" is out of it: the words, and how it rings. */
data class Spoken(
    val text: String,
    val rules: List<TriggerRule> = emptyList(),
    val match: RuleMatch = RuleMatch.ANY,
    val recurrence: Recurrence = Recurrence.None,
)

/** What [hear] made of a sentence. */
sealed interface Heard {
    /** Understood whole: write it. [said] is the sentence minus "recuérdame", for when it cannot be written after all. */
    data class Read(val spoken: Spoken, val said: String) : Heard

    /** "Cuando me vaya de aquí" — understood, but "aquí" is a position, and there is none yet. */
    data class NeedsHere(val text: String) : Heard

    /** Not something to write without a person looking: [text] is the sentence, minus "recuérdame". */
    data class Unsure(val text: String, val why: Why) : Heard

    enum class Why {
        /** No "when" in it at all. */
        NOTHING,

        /** A "when", and more of one than was understood: a place nobody saved, two answers to one question. */
        PARTIAL,

        /** Nothing but a "when": there is no reminder left to write. */
        NO_WORDS,
    }
}

/**
 * Reads a sentence said out loud, against the clock [now] in [zone] and the person's own words
 * for the parts of the day and for places ([settings]). [here] is a fresh position for "aquí" when
 * the caller has one, and [hereLabel] the word for it in the person's language — the name the
 * circle is written under, as the snooze to "aquí" does.
 */
fun hear(
    text: String,
    now: Instant,
    zone: ZoneId,
    settings: AppSettings,
    here: Fix? = null,
    hereLabel: String = "",
): Heard {
    val folded = Words.of(text)
    val leadIn = LEAD_IN.find(folded.text)?.let { folded.original(it.range) }?.takeUnless { it.isEmpty() }
    val said = (if (leadIn == null) text.trim() else withoutSpans(text, listOf(leadIn))).ifBlank { text.trim() }
    val reading = readWhen(text, now, zone, settings.dayParts, settings.savedPlaces)
    val leaving = HERE.find(folded.text)
    val routine = ROUTINE.find(folded.text)
    val since = SINCE.find(folded.text)
    if (routine != null || since != null) {
        val spans = listOfNotNull(leadIn, routine?.let { folded.original(it.range) }, since?.let { folded.original(it.range) }) + reading?.spans.orEmpty()
        val span = routineSpan(reading?.understood) ?: return Heard.Unsure(said, Heard.Why.PARTIAL)
        if (leaving != null) return Heard.Unsure(said, Heard.Why.PARTIAL)
        return written(text, spans, emptyList(), span, said)
    }
    if (leaving != null) {
        // "Aquí" and another "when" beside it are two answers to one question.
        if (reading != null) return Heard.Unsure(said, Heard.Why.PARTIAL)
        here ?: return Heard.NeedsHere(said)
        return written(text, listOfNotNull(leadIn, folded.original(leaving.range)), listOf(TriggerRule(hereCircle(here, hereLabel))), Recurrence.None, said)
    }
    reading ?: return Heard.Unsure(said, Heard.Why.NOTHING)
    val spans = listOfNotNull(leadIn) + reading.spans
    return when (val understood = reading.understood) {
        is Understood.Once -> written(text, spans, listOf(TriggerRule(understood.trigger)), Recurrence.None, said)
        is Understood.Comes -> written(text, spans, emptyList(), understood.recurrence, said)
    }
}

/**
 * The span a routine counts ("rutina: mover el coche cada 3 semanas", "regar cada 5 días desde la
 * última vez"), from the repeat the words said — a routine is a count since the last time, so
 * "cada 3 semanas" there is three weeks since it was last done, not a calendar. Said with no span
 * at all it is the week a routine opens with in the editor. A repeat a routine cannot be — on
 * named weekdays, at an hour, on a day of the month — or a moment instead of a repeat is null:
 * more was said than a routine can hold.
 */
private fun routineSpan(understood: Understood?): Recurrence.Since? = when (understood) {
    null -> Recurrence.Since(1, RecurrenceUnit.WEEKS)
    is Understood.Once -> null
    is Understood.Comes -> when (val recurrence = understood.recurrence) {
        is Recurrence.After -> if (recurrence.unit == RecurrenceUnit.HOURS) Recurrence.Since(recurrence.amount, RecurrenceUnit.HOURS) else null
        is Recurrence.Calendar -> recurrence.repeat.takeIf { it.days.isEmpty() && it.time == null && it.monthly == null }?.let { repeat ->
            val unit = when (repeat.unit) {
                RepeatUnit.DAY -> RecurrenceUnit.DAYS
                RepeatUnit.WEEK -> RecurrenceUnit.WEEKS
                RepeatUnit.MONTH -> RecurrenceUnit.MONTHS
                RepeatUnit.YEAR -> RecurrenceUnit.YEARS
            }
            Recurrence.Since(repeat.every, unit)
        }
        else -> null
    }
}

private fun written(text: String, spans: List<IntRange>, rules: List<TriggerRule>, recurrence: Recurrence, said: String): Heard {
    val words = withoutSpans(text, spans)
    if (words.isBlank()) return Heard.Unsure(said, Heard.Why.NO_WORDS)
    // What is left must not still sound like a "when": "mañana cuando llegue a casa" read as
    // tomorrow leaves "cuando llegue a casa" behind, and writing that is writing half of it.
    if (soundsLikeWhen(words)) return Heard.Unsure(said, Heard.Why.PARTIAL)
    return Heard.Read(Spoken(words, rules, RuleMatch.ANY, recurrence), said)
}

/**
 * The reminder [Spoken] stands for, written now — or null when a person should look first.
 *
 * Settled the way a one-tap preset is (`Preset.toReminder`): a countdown starts ticking, a day
 * counted is the day it means, a day left to the day is narrowed to what is left of it. And
 * judged the way the editor judges what it is about to save — **anything it would warn about is
 * a reason not to write it unseen**: a moment already gone, a rule that can never ring, a series
 * already over. The one warning that is not is [ValidationWarning.BetterAsCondition], which is
 * advice about a shape this never writes.
 */
fun Spoken.toReminder(id: String, now: Instant, zone: ZoneId, settings: AppSettings): Reminder? {
    if (validate(text, rules, recurrence).isNotEmpty()) return null
    val shape = settings.dayShape
    if (warnings(rules, now, zone, settings.defaultTime, match, shape, id).any { it !is ValidationWarning.BetterAsCondition }) return null
    if (recurrenceWarning(recurrence, now, zone, shape) != null) return null
    return Reminder(
        id = id,
        text = text,
        rules = settleDays(startCountdowns(settleRelativeDates(rules, now, zone), now), now, zone, shape),
        ruleMatch = match,
        // A routine is told about the way routines are, as one opened in the editor is.
        actions = if (recurrence is Recurrence.Since) settings.routineActions else settings.defaultActions,
        recurrence = recurrence,
        status = Status.ACTIVE,
        createdAt = now,
        updatedAt = now,
    )
}

/**
 * The sentence without the stretches in [spans] — offsets into it — and without the small words
 * that only tied them to the rest: "comprar flores para el sábado" is "comprar flores", not
 * "comprar flores para el".
 *
 * Whole words go: a word the "when" touches goes with it. A joining word goes when it stands
 * right before something that went ("para el" before "sábado"), or between two that went, or
 * last after one; and a word that joins a clause on ("de", "que", "to") goes when nothing but
 * gone words stand before it. Never on the strength of one gone word before it alone, or
 * "comprar pan mañana de oferta" would lose its "de". What is left starts with a capital, as a typed reminder does.
 */
internal fun withoutSpans(text: String, spans: List<IntRange>): String {
    val tokens = TOKEN.findAll(text).toList()
    val gone = BooleanArray(tokens.size) { i -> spans.any { it.first <= tokens[i].range.last && tokens[i].range.first <= it.last } }
    var changed = true
    while (changed) {
        changed = false
        for (i in tokens.indices) {
            if (gone[i] || !isJoiner(tokens[i].value)) continue
            val beforeGone = i + 1 < tokens.size && gone[i + 1]
            val afterGone = i > 0 && gone[i - 1]
            // Everything before it went: "mañana a las 9 de llamar a Ana" starts at "llamar".
            // Only the words that join a clause on, never an article or a preposition that is
            // the start of the thing itself: "recuérdame a mi madre…" keeps "a mi madre".
            val leading = i > 0 && (0 until i).all { gone[it] } && Words.of(tokens[i].value).text.trim() in CLAUSE_JOINERS
            if (beforeGone || leading || (afterGone && i + 1 == tokens.size)) {
                gone[i] = true
                changed = true
            }
        }
    }
    val kept = tokens.filterIndexed { i, _ -> !gone[i] }.joinToString(" ") { it.value }
    return kept.trim(' ', ',', ';', ':', '.', '-', '—').replaceFirstChar { it.titlecase(Locale.ROOT) }
}

private fun isJoiner(token: String): Boolean = Words.of(token).text.trim() in JOINERS

private val TOKEN = Regex("\\S+")

/** What may open the words after a "when" said first, and goes with it: "…a las 9 de llamar", "…at 9 to call". */
private val CLAUSE_JOINERS = setOf("de", "que", "y", "e", "para", "to", "and", "that")

/** The small words that tie a "when" to a sentence, folded. */
private val JOINERS = setOf(
    "el", "la", "los", "las", "lo", "este", "esta", "proximo", "proxima", "que", "de", "del", "a", "al",
    "para", "en", "y", "o", "e", "u", "por", "mi", "mis",
    "the", "on", "at", "for", "next", "this", "by", "in", "and", "or", "to", "my",
)

/**
 * "Recuérdame…", "avísame…", "remind me to…": how a sentence said to a phone starts, and not part
 * of what to remember. Only at the very start. "I'm" and "don't" have lost their apostrophes.
 */
private val LEAD_IN = Regex(
    "^ (?:(?:oye|vale|ok|okay|hey) )?(?:recuerdame|recordarme|acuerdate de|avisame|apuntame|apunta|anota|anotame|" +
        "(?:pon|ponme|crea|creame) un recordatorio(?: para)?|remind me|remember to|don t let me forget|dont let me forget|" +
        "(?:set|add|create) a reminder)(?: (?:que|de|para|to|that|about))?\\b",
)

/**
 * "Cuando me vaya de aquí", "al salir de aquí", "when I leave here": a circle drawn around where
 * the phone is now, crossed on the way out — what "al salir de aquí" has meant on the alert since
 * the snooze to a place ([hereCircle]).
 */
private val HERE = Regex(
    "\\b(?:(?:cuando|en cuanto|nada mas|apenas) (?:me vaya|me marche|salga|te vayas|salgas)|al (?:salir|irme|marcharme)) de aqui\\b|" +
        "\\b(?:when|once|as soon as|after) i (?:leave|get out of|go out of) (?:here|this place)\\b",
)

/**
 * "Rutina: …", "nueva rutina …", "routine: …" — at the start, after "recuérdame" or "apunta" if
 * those came first. What makes the sentence a routine rather than a reminder.
 */
private val ROUTINE = Regex(
    "^ (?:(?:oye|vale|ok|okay|hey) )?(?:(?:recuerdame|apunta|apuntame|anota|anotame|crea|creame|pon|ponme|add|create) )?" +
        "(?:(?:una|nueva|una nueva|a|new|a new) )?(?:rutina|routine)(?: (?:de|para|que|to|for))?\\b",
)

/** "Desde la última vez", "desde que lo haga", "since I last did it": a span counted from the deed, which is a routine. */
private val SINCE = Regex(
    "\\bdesde (?:la ultima vez|que lo (?:haga|hice|hago|hagas|hiciste|haya hecho|hayas hecho))\\b|" +
        "\\bsince (?:the )?last time\\b|\\bsince i last did it\\b|\\bafter i last did it\\b",
)

