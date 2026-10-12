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
    val deadline: Deadline? = null,
    /** What the sentence said about how to be told — "con pantalla completa", "sin sonido". */
    val actions: SpokenActions = SpokenActions(),
)

/**
 * How a sentence asked to be told, as changes to the person's own defaults: "con pantalla
 * completa" adds the full screen to whatever they usually get, "sin sonido" takes the sound away,
 * "solo una notificación" is that and nothing else.
 */
data class SpokenActions(
    val only: Set<Action>? = null,
    val add: Set<Action> = emptySet(),
    val remove: Set<Action> = emptySet(),
) {
    fun applyTo(defaults: Set<Action>): Set<Action> {
        var actions = (only ?: defaults) - remove + add
        // The two sounds are one choice, as the editor's tiles make them: asking for one puts the other away.
        if (Action.SOUND_UNTIL_ANSWERED in add) actions = actions - Action.SOUND
        if (Action.SOUND in add) actions = actions - Action.SOUND_UNTIL_ANSWERED
        // Silenced of everything is a reminder nobody would ever notice: the notification stays.
        return actions.ifEmpty { setOf(Action.NOTIFICATION) }
    }

    operator fun plus(other: SpokenActions) = SpokenActions(other.only ?: only, add + other.add, remove + other.remove)
}

/**
 * A stretch of a sentence as heard — offsets into it — and the family of "when" it was read as;
 * null for how the sentence was said rather than when: "recuérdame", a join, "con pantalla completa".
 */
data class HeardSpan(val range: IntRange, val family: TriggerFamily?)

/** What [hear] made of a sentence. */
sealed interface Heard {
    /**
     * Understood whole: write it. [said] is the sentence minus "recuérdame", for when it cannot be
     * written after all.
     *
     * The rest is for the person checking it (0.181.0): [parts] is where in the sentence each
     * "when" was said, so the sheet can show what was read from what; [doubts] are words left in
     * the reminder that sound like part of a "when" nobody read ("antes de comer", "el primer"),
     * offsets into the sentence; [alternatives] are the same sentence read the other way where
     * the words left an hour open and both halves are waking ones ("a las 8": tonight, or
     * tomorrow morning).
     */
    data class Read(
        val spoken: Spoken,
        val said: String,
        val parts: List<HeardSpan> = emptyList(),
        val doubts: List<IntRange> = emptyList(),
        val alternatives: List<Spoken> = emptyList(),
    ) : Heard

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
    val said = withoutLeadIn(text)
    val actions = ACTIONS.mapNotNull { (pattern, edit) -> pattern.find(folded.text)?.let { edit to folded.original(it.range) } }
    val asked = actions.fold(SpokenActions()) { all, (edit, _) -> all + edit }
    val skip = listOfNotNull(leadIn) + actions.map { it.second }
    // How it was said, not when: shown, but in the prose's colour.
    val manner = skip.map { HeardSpan(it, null) }
    val routine = ROUTINE.find(folded.text)
    val since = SINCE.find(folded.text)
    if (routine != null || since != null) {
        val reading = readWhen(mask(text, skip), now, zone, settings.dayParts, settings.savedPlaces, settings.dayShape)
        val marks = listOfNotNull(routine?.let { folded.original(it.range) }, since?.let { folded.original(it.range) })
        val spans = skip + marks + reading?.spans.orEmpty()
        val span = routineSpan(reading?.understood) ?: return Heard.Unsure(said, Heard.Why.PARTIAL)
        if (HERE.containsMatchIn(folded.text)) return Heard.Unsure(said, Heard.Why.PARTIAL)
        val parts = manner + marks.map { HeardSpan(it, null) } + reading?.spans.orEmpty().map { HeardSpan(it, TriggerFamily.TIME) }
        return written(text, spans, Spoken("", recurrence = span, actions = asked), said, parts)
    }
    return when (val composed = composeWhen(text, skip, Context(now, zone, settings, here, hereLabel))) {
        Composed.Nothing -> Heard.Unsure(said, Heard.Why.NOTHING)
        Composed.Ambiguous -> Heard.Unsure(said, Heard.Why.PARTIAL)
        // Asked for a position only when the rest of it would be written: read once with a
        // stand-in for "aquí", and if that comes to anything but a reading, that is the answer.
        Composed.NeedsHere -> when (val probe = hear(text, now, zone, settings, Fix(0.0, 0.0, 0.0, now), hereLabel)) {
            is Heard.Read -> Heard.NeedsHere(said)
            else -> probe
        }
        is Composed.Rules -> {
            val spoken = Spoken("", composed.rules, composed.match, composed.recurrence, composed.deadline, asked)
            val alternatives = composed.alternatives.map { (rules, recurrence) -> spoken.copy(rules = rules, recurrence = recurrence) }
            when (val heard = written(text, skip + composed.spans, spoken, said, manner + composed.parts, alternatives)) {
                // An alternative is offered only when it could be written as it stands.
                is Heard.Read -> heard.copy(alternatives = heard.alternatives.filter { it.toReminder("alternative", now, zone, settings) != null })
                else -> heard
            }
        }
    }
}

/** [text] without the "recuérdame" it may start with: the words a person goes on to write, if it comes to that. */
fun withoutLeadIn(text: String): String {
    val folded = Words.of(text)
    val leadIn = LEAD_IN.find(folded.text)?.let { folded.original(it.range) }?.takeUnless { it.isEmpty() }
    return (if (leadIn == null) text.trim() else withoutSpans(text, listOf(leadIn))).ifBlank { text.trim() }
}

/**
 * Whether a sentence the grammar could not read has anything in it that might be a "when" or a
 * "where" at all — a word for a time, a day, a season, a doorway, a number — so that asking Gemini
 * could come to something. "Comprar pan" has nothing: no guess can give it a when, and asking
 * would only make it wait.
 */
fun mightSayWhen(text: String): Boolean {
    val words = Words.of(text).text
    return soundsLikeWhen(text) || CUE.containsMatchIn(words)
}

private val CUE = Regex(
    "\\d|\\b(?:" + listOf(
        "cuando", "mientras", "antes", "despues", "durante", "hasta", "desde", "luego", "pronto", "tarde", "manana", "hoy", "ayer", "noche", "madrugada",
        "mediodia", "semana", "semanas", "finde", "mes", "meses", "ano", "anos", "hora", "horas", "minuto", "minutos", "dia", "dias", "cada", "vez", "veces",
        "navidad", "nochevieja", "nochebuena", "reyes", "verano", "invierno", "primavera", "otono", "puente", "vispera", "vacaciones", "cumpleanos",
        "llegue", "llegar", "salga", "salir", "vuelva", "volver", "vaya", "irme", "este", "estoy", "aqui", "alli", "comer", "cenar", "desayunar",
        "lunes", "martes", "miercoles", "jueves", "viernes", "sabado", "domingo",
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
        "when", "while", "before", "after", "during", "until", "since", "soon", "later", "tonight", "tomorrow", "today", "morning", "afternoon",
        "evening", "night", "noon", "week", "weekend", "month", "year", "hour", "minute", "day", "every", "each", "times", "christmas", "summer",
        "winter", "spring", "autumn", "holidays", "birthday", "arrive", "leave", "here", "there", "lunch", "dinner", "breakfast",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    ).joinToString("|") + ")s?\\b",
)

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

/** [reading] with the words that are left once [spans] are out — or why it cannot be written. */
private fun written(text: String, spans: List<IntRange>, reading: Spoken, said: String, parts: List<HeardSpan>, alternatives: List<Spoken> = emptyList()): Heard {
    val words = withoutSpans(text, spans)
    if (words.isBlank()) return Heard.Unsure(said, Heard.Why.NO_WORDS)
    // What is left must not still sound like a "when": "mañana cuando llegue a casa al curro"
    // with no "curro" saved leaves a doorway behind, and writing the rest is writing half of it.
    // Nor like a join nobody understood the other side of ("…y sólo si llueve").
    if (soundsLikeWhen(words) || LEFT_JOIN.containsMatchIn(Words.of(words).text)) return Heard.Unsure(said, Heard.Why.PARTIAL)
    // "Todos los días menos el domingo": an exception nobody can write, and the day after it
    // read as where the series starts. Not by halves.
    if (EXCEPT.containsMatchIn(Words.of(text).text)) return Heard.Unsure(said, Heard.Why.PARTIAL)
    val doubts = doubtsIn(text, spans, parts.filter { it.family != null }.map { it.range })
    return Heard.Read(reading.copy(text = words), said, parts.sortedBy { it.range.first }, doubts, alternatives.map { it.copy(text = words) })
}

/**
 * The words left in a reminder that sound like part of a "when" nobody read (0.181.0) — offsets
 * into [text]. Not a reason to refuse it: what was read is a reading, and the sheet writes it.
 * A reason to look twice, and to ask Gemini when the person has it on.
 *
 * Two kinds. Words that are only ever about time, wherever they are ("antes de comer", "luego",
 * "y media"); and words that only qualify a "when" when they stand right beside one ("el
 * **primer** lunes", "**cada**…") — elsewhere they are the thing itself ("la primera edición").
 * Kept to words that are rarely the thing: "llamar a todos mañana" is not a doubt.
 */
private fun doubtsIn(text: String, spans: List<IntRange>, whens: List<IntRange>): List<IntRange> {
    val (tokens, gone) = goneTokens(text, spans)
    val kept = mask(text, tokens.filterIndexed { i, _ -> gone[i] }.map { it.range })
    val folded = Words.of(kept)
    val anywhere = DOUBTFUL.findAll(folded.text).map { folded.original(it.range) }.filterNot { it.isEmpty() }.toList()
    fun isWhen(i: Int) = tokens.getOrNull(i)?.range?.let { token -> whens.any { it.first <= token.last && token.first <= it.last } } == true
    val beside = tokens.indices
        .filter { i -> !gone[i] && (isWhen(i - 1) || isWhen(i + 1)) && Words.of(tokens[i].value).text.trim() in DOUBTFUL_BESIDE }
        .map { tokens[it].range }
        // "Minutos" beside a "when" is already in "diez minutos": one doubt, not two.
        .filter { range -> anywhere.none { it.first <= range.last && range.first <= it.last } }
    return (anywhere + beside).distinct().sortedBy { it.first }
}

/** Only ever about time, left anywhere in the words. Folded. */
private val DOUBTFUL = Regex(
    "\\b(?:temprano|pronto|luego|enseguida|en un rato|dentro de un rato|mas tarde|a primera hora|a ultima hora|y media|y cuarto|menos cuarto|en punto|" +
        "(?:la )?(?:proxima|siguiente) vez|$COUNTED_PATTERN (?:horas?|minutos?|min)|(?:una|media) hora|medianoche|madrugada|" +
        "antes de|despues de|hasta que|desde que|en cuanto|cuando|mientras|todos los (?:dias|meses|anos)|todas las (?:semanas|mananas|tardes|noches)|" +
        "later|soon|early|first thing|next time|half past|quarter (?:past|to)|o clock|and a half|$COUNTED_PATTERN (?:hours?|minutes?)|midnight|" +
        "before|after|until|when|while)\\b",
)

/** What qualifies a "when" right beside it, and is rarely the thing itself. Folded. */
private val DOUBTFUL_BESIDE = setOf(
    "primer", "primera", "primero", "segundo", "segunda", "tercer", "tercera", "tercero", "ultimo", "ultima",
    "first", "second", "third", "fourth", "last", "horas", "minutos", "hours", "minutes",
)

/**
 * The joins that only ever bring a condition in: left in the words, they brought one nobody
 * understood ("…y sólo si llueve"). "Al mismo tiempo" or "antes del" can be part of the thing
 * itself ("tomar las dos pastillas al mismo tiempo"), so they are not here. And "o pasado" /
 * "y pasado": the day after tomorrow said as its last word, which no reader takes alone.
 */
private val LEFT_JOIN = Regex("\\b(?:solo si|siempre que|only if|as long as)\\b|\\b(?:o|y|u|and|or) pasado\\b")

/** "Menos el domingo", "excepto los sábados", "except sunday": a day left out, which no reading here holds. */
private val EXCEPT = Regex("\\b(?:menos|excepto|salvo|quitando|except|but not|apart from)(?: (?:el|los|la|las|on|the))? ${WEEKDAY_WORDS_PATTERN}s?\\b")

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
    if (warnings(rules, now, zone, settings.defaultTime, match, shape, id, deadline).any { it !is ValidationWarning.BetterAsCondition }) return null
    if (recurrenceWarning(recurrence, now, zone, shape) != null) return null
    val reminder = Reminder(
        id = id,
        text = text,
        rules = settleDays(startCountdowns(settleRelativeDates(rules, now, zone), now), now, zone, shape),
        ruleMatch = match,
        // A routine is told about the way routines are, as one opened in the editor is.
        actions = actions.applyTo(if (recurrence is Recurrence.Since) settings.routineActions else settings.defaultActions),
        recurrence = recurrence,
        deadline = deadline,
        status = Status.ACTIVE,
        createdAt = now,
        updatedAt = now,
    )
    // The deadline's close for the round that starts now, as the editor's save writes it.
    return if (deadline == null) reminder else reminder.copy(expiresAt = reminder.roundExpiry(now, zone, settings.defaultTime, shape = shape))
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
    val (tokens, gone) = goneTokens(text, spans)
    val kept = tokens.filterIndexed { i, _ -> !gone[i] }.joinToString(" ") { it.value }
    return kept.trim(' ', ',', ';', ':', '.', '-', '—').replaceFirstChar { it.titlecase(Locale.ROOT) }
}

/** The sentence's words, and which of them [withoutSpans] takes out: the spans, and the small words that only tied them on. */
private fun goneTokens(text: String, spans: List<IntRange>): Pair<List<MatchResult>, BooleanArray> {
    val tokens = TOKEN.findAll(text).toList()
    val gone = BooleanArray(tokens.size) { i -> spans.any { it.first <= tokens[i].range.last && tokens[i].range.first <= it.last } }
    var changed = true
    while (changed) {
        changed = false
        for (i in tokens.indices) {
            if (gone[i] || !isJoiner(tokens[i].value)) continue
            val beforeGone = i + 1 < tokens.size && gone[i + 1]
            val afterGone = i > 0 && gone[i - 1]
            // "Es", "si", "it": only ever when on both sides of them is the "when" — "a la vez
            // que es por la tarde" — never beside one alone: "fix it tomorrow" keeps its "it".
            if (Words.of(tokens[i].value).text.trim() in BETWEEN_ONLY) {
                if (beforeGone && afterGone) {
                    gone[i] = true
                    changed = true
                }
                continue
            }
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
    return tokens to gone
}

private fun isJoiner(token: String): Boolean = Words.of(token).text.trim().let { it in JOINERS || it in BETWEEN_ONLY }

/** The words that go only when the "when" stands on both sides of them. */
private val BETWEEN_ONLY = setOf("es", "sea", "si", "estoy", "it", "is", "s", "i", "m", "am")

private val TOKEN = Regex("\\S+")

/** What may open the words after a "when" said first, and goes with it: "…a las 9 de llamar", "…at 9 to call". */
private val CLAUSE_JOINERS = setOf("de", "que", "y", "e", "para", "to", "and", "that")

/** The small words that tie a "when" to a sentence, folded. */
private val JOINERS = setOf(
    "el", "la", "los", "las", "lo", "este", "esta", "proximo", "proxima", "que", "de", "del", "a", "al",
    "para", "en", "y", "o", "e", "u", "por", "mi", "mis",
    // "Desde hoy", "a partir de mañana": where a series starts, said with the day (0.183.1).
    "desde", "partir",
    "the", "on", "at", "for", "next", "this", "by", "in", "and", "or", "to", "my", "from", "starting",
)

/**
 * "Recuérdame…", "avísame…", "remind me to…": how a sentence said to a phone starts, and not part
 * of what to remember. Only at the very start. "I'm" and "don't" have lost their apostrophes.
 */
private val LEAD_IN = Regex(
    "^ (?:(?:oye|vale|ok|okay|hey) )?(?:recuerdame|recordarme|acuerdate de|avisame|apuntame|apunta|anota|anotame|" +
        "(?:pon|ponme|crea|creame) un recordatorio(?: para)?|remind me|remember to|don t let me forget|dont let me forget|" +
        "(?:que )?no se me olvide|no te olvides|no olvides|no me dejes olvidar(?:me)?|que no me olvide|" +
        "don t forget|dont forget|do not forget|" +
        "(?:set|add|create) a reminder)(?: (?:que|de|para|to|that|about))?\\b",
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

/**
 * "Con pantalla completa", "que suene hasta que conteste", "sin sonido": how to be told, said with
 * the rest. Only the ways of saying it that cannot be anything else — "poner el móvil en
 * silencio" or "ser insistente con el banco" are the thing to remember, not how to be told.
 */
private val ACTIONS: List<Pair<Regex, SpokenActions>> = listOf(
    Regex("\\b(?:que suene|sonando) hasta que (?:conteste|responda|lo apague|le haga caso|lo vea)\\b|\\b(?:keep ringing|ring) until i answer\\b") to
        SpokenActions(add = setOf(Action.SOUND_UNTIL_ANSWERED)),
    Regex("\\b(?:avisame |que salga )?(?:con|a) pantalla completa\\b|\\bwith a full ?screen alert\\b|\\bas a full ?screen alert\\b") to SpokenActions(add = setOf(Action.FULL_SCREEN)),
    Regex("\\b(?:solo|solamente) (?:con )?(?:una )?notificacion\\b|\\bsolo notificar\\b|\\b(?:just|only) a notification\\b|\\bnotification only\\b") to
        SpokenActions(only = setOf(Action.NOTIFICATION)),
    Regex("\\b(?:sin sonido|que no suene|without (?:a )?sound|with no sound)\\b") to SpokenActions(remove = setOf(Action.SOUND, Action.SOUND_UNTIL_ANSWERED)),
    Regex("\\b(?:sin vibrar|sin vibracion|que no vibre|without vibrating|with no vibration)\\b") to SpokenActions(remove = setOf(Action.VIBRATE)),
    Regex("\\b(?:y que vibre|con vibracion|and vibrate)\\b") to SpokenActions(add = setOf(Action.VIBRATE)),
    Regex("\\b(?:con sonido|with sound)\\b") to SpokenActions(add = setOf(Action.SOUND)),
)

