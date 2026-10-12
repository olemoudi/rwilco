package dev.rwilco.model

import java.time.Instant
import java.time.ZoneId

/*
 * What was meant, from what was said (0.187.0).
 *
 * Since 0.186.0 a dictation is listened to until "Terminado", so it can be said calmly — and said
 * calmly, people correct themselves: "a las cinco… no, a las seis", "mañana, digo, pasado
 * mañana", "comprar pan… comprar pan y leche". The grammar reads one sentence, and both halves of
 * a correction are a "when" to it: two answers to one question, or worse, both rung. So before it
 * reads anything, the dictation is repaired, the way a person listening would: the hesitations go
 * ("eh", "mmm"), a correction takes the place of what it corrects, a start said again after a
 * pause takes the place of the start it abandoned, and a word said twice in a row is said once.
 *
 * What is taken out is kept as offsets into what was heard, so the sheet can show it struck
 * through: a correction understood is seen to be one, and one understood wrongly is seen too.
 *
 * Erring towards leaving words in. A correction is only taken as one when something before it
 * lines up with it — the same words starting it ("a las cinco, no, **a las** seis"), or a "when"
 * corrected by a "when" ("mañana, no, pasado mañana") — never because a "no" was said: "si no
 * llueve", "pedir perdón a Ana" and "comprar el pan, no el de molde" are the thing itself.
 */

/**
 * A dictation as it was heard — every stretch, joined — and as it was meant: [text], which is
 * [heard] without the stretches in [dropped].
 */
class Repaired internal constructor(
    val heard: String,
    /** The words of [heard] (offsets), and which of them were kept. */
    private val words: List<IntRange>,
    private val kept: BooleanArray,
) {
    /** What to read: the words kept, single-spaced. */
    val text: String

    /** Where each kept word starts in [text]. */
    private val starts: IntArray

    init {
        val out = StringBuilder()
        starts = IntArray(words.size) { -1 }
        words.forEachIndexed { i, range ->
            if (!kept[i]) return@forEachIndexed
            if (out.isNotEmpty()) out.append(' ')
            starts[i] = out.length
            out.append(heard, range.first, range.last + 1)
        }
        text = out.toString()
    }

    /** The stretches of [heard] taken out, a run of words at a time ("a las cinco, no,"). */
    val dropped: List<IntRange> = buildList {
        var first = -1
        for (i in words.indices) {
            if (!kept[i] && first < 0) first = i
            if (kept[i] && first >= 0) {
                add(words[first].first..words[i - 1].last)
                first = -1
            }
        }
        if (first >= 0) add(words[first].first..words.last().last)
    }

    /**
     * Where in [heard] a stretch of [text] was said: a piece for each run of words said together,
     * so a stretch that spans words taken out does not claim them too ("al llegar al trabajo" out
     * of "al llegar a casa, perdón, al trabajo" is two pieces). Empty for a stretch of no words.
     */
    fun inHeard(range: IntRange): List<IntRange> {
        if (range.isEmpty()) return emptyList()
        val pieces = mutableListOf<IntRange>()
        var piece: IntRange? = null
        var previous = -1
        for (i in words.indices) {
            if (!kept[i]) continue
            val start = starts[i]
            val end = start + words[i].last - words[i].first
            if (end < range.first || start > range.last) continue
            val from = words[i].first + (range.first - start).coerceAtLeast(0)
            val to = words[i].last - (end - range.last).coerceAtLeast(0)
            // A word dropped between this one and the last starts a new piece.
            piece = if (piece != null && (previous until i).drop(1).none { !kept[it] }) piece.first..to else {
                piece?.let(pieces::add)
                from..to
            }
            previous = i
        }
        piece?.let(pieces::add)
        return pieces
    }
}

/**
 * Repairs a dictation said as [stretches] — each closed by a pause — against the clock and the
 * person's places, which is what tells "mañana" or "al llegar a casa" for a "when".
 */
fun repaired(stretches: List<String>, now: Instant, zone: ZoneId, settings: AppSettings): Repaired {
    val heard = stretches.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    val tokens = mutableListOf<Token>()
    var offset = 0
    stretches.map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { stretch, words ->
        for (match in WORD.findAll(words)) {
            val range = (offset + match.range.first)..(offset + match.range.last)
            tokens += Token(range, stretch, keyOf(match.value), match.value)
        }
        offset += words.length + 1
    }
    val repair = Repair(
        tokens,
        readSpans = { text -> readWhen(text, now, zone, settings.dayParts, settings.savedPlaces, settings.dayShape)?.spans.orEmpty() },
        namesPlace = { text -> Words.of(text).text.let { words -> words.indices.any { it > 0 && words[it - 1] == ' ' && savedPlaceAt(words, it - 1, settings.savedPlaces) != null } } },
    )
    repair.fillers()
    // A marker that also opens a stretch while somebody finds the words ("Bueno, el martes")
    // is asked about as a correction first; only one that corrects nothing is a hesitation.
    repair.corrections()
    repair.openings()
    repair.restarts()
    repair.repeats()
    return Repaired(heard, tokens.map { it.range }, repair.alive)
}

/** One word as heard: where, in which stretch, and folded for comparing ("Mañana," is "manana"). */
private class Token(val range: IntRange, val stretch: Int, val key: String, val raw: String)

private class Repair(val tokens: List<Token>, private val readSpans: (String) -> List<IntRange>, private val namesPlace: (String) -> Boolean) {
    val alive = BooleanArray(tokens.size) { true }

    /** Reading a "when" is a few hundred regex passes, and the same words are asked about more than once. */
    private val spansOf = HashMap<String, List<IntRange>>()

    private fun whenSpans(text: String): List<IntRange> = spansOf.getOrPut(text) { readSpans(text) }

    /** The words still in that are words: a dash or a lone comma lines nothing up. */
    private fun living(): List<Int> = tokens.indices.filter { alive[it] && tokens[it].key.isNotEmpty() }

    private fun drop(indices: Iterable<Int>) = indices.forEach { alive[it] = false }

    private fun key(index: Int) = tokens[index].key

    /** "Eh", "mmm" anywhere. */
    fun fillers() {
        tokens.indices.filter { FILLER.matches(key(it)) }.let(::drop)
    }

    /** "Bueno", "pues", "o sea" opening a stretch that goes on, when they corrected nothing. */
    fun openings() {
        for (stretch in tokens.map { it.stretch }.distinct()) {
            val inIt = living().filter { tokens[it].stretch == stretch }
            for (opening in OPENINGS) {
                if (inIt.size > opening.size && opening.indices.all { key(inIt[it]) == opening[it] }) {
                    drop(inIt.take(opening.size))
                    break
                }
            }
        }
    }

    /** "A las cinco, no, a las seis": a correction in place of what it corrects. */
    fun corrections() {
        var at = 0
        while (true) {
            val words = living()
            if (at >= words.size) return
            val marker = markerAt(words, at)
            if (marker == null) {
                at++
                continue
            }
            // Markers said one after another are one: "espera, mejor el jueves", "no, perdón, a las seis".
            var size = marker.words.size
            var strong = marker.strong
            while (true) {
                val next = markerAt(words, at + size) ?: break
                size += next.words.size
                strong = strong || next.strong
            }
            val markerAt = words.subList(at, at + size)
            val before = words.subList(0, at)
            val after = words.subList(at + size, words.size)
            val reparandum = reparandum(strong, markerAt, before, after)
            if (reparandum == null) {
                at += size
                continue
            }
            drop(reparandum + markerAt)
            at = before.count { alive[it] }
        }
    }

    /** The marker said at [at] in [words], if one is; "espera", "actually" only with a break after them, or they are the thing itself. */
    private fun markerAt(words: List<Int>, at: Int): Marker? {
        val marker = MARKERS.firstOrNull { marker -> marker.words.indices.all { words.getOrNull(at + it)?.let(::key) == marker.words[it] } } ?: return null
        if (!marker.needsBreak) return marker
        val last = words[at + marker.words.size - 1]
        val next = words.getOrNull(at + marker.words.size)
        return marker.takeIf { tokens[last].raw.last() in BREAKS || (next != null && tokens[next].stretch != tokens[last].stretch) }
    }

    /**
     * What goes besides the marker, as indices into [tokens]: what the words after it correct —
     * nothing when they only narrow it down, or the words they say again — or null when nothing
     * before it lines up with them, and the marker is the thing itself.
     */
    private fun reparandum(strong: Boolean, markerAt: List<Int>, before: List<Int>, after: List<Int>): List<Int>? {
        if (before.isEmpty() || after.isEmpty()) return null
        // "Si no", "y no", "pedir perdón", "lo mejor", "le digo": the word is part of the sentence.
        val previous = key(before.last())
        if (previous in NOT_AFTER || (key(markerAt.first()).startsWith("perdon") && previous in ASKING)) return null
        val paused = pausedAround(markerAt, before, after)
        // A number corrected by a number: "a las cinco, no, seis", "at five, no, six".
        if (isNumber(before.last()) && isNumber(after.first())) return listOf(before.last())
        // A correction with no "when" or place in it corrects something that is not one: what
        // lines up with it is looked for outside the "when" before it ("a Juan mañana a las
        // cinco, no, a Pedro" is Juan's "a", not the hour's; "al llegar a casa, perdón, al
        // trabajo" corrects the place's).
        val text = joined(after).first
        val lexical = alignment(before, after, skip = if (mightSayWhen(text) || namesPlace(text)) emptySet() else whenPositions(before))
        // The same words again to add a "when" to them: "llamar al médico, mejor llamar por la
        // mañana" is still the doctor, in the morning — the words said again go, nothing before.
        if (lexical != null && lexical.second < after.size && whenPositions(before).none { it >= lexical.first }) {
            val rest = after.drop(lexical.second)
            if (whenSpans(joined(rest).first).any { it.first == 0 }) return after.take(lexical.second)
        }
        // The same two words starting both sides: "a las cinco, no, a las seis".
        if (lexical != null && lexical.second >= 2) return keepingWhens(before.subList(lexical.first, before.size), after)
        // A "when" corrected by a "when": "mañana, no, pasado mañana", "al llegar a casa, no, al
        // salir del trabajo" — or only the part of it the same word opens: "tomorrow at five, no,
        // at six" corrects the hour and keeps the day. Unless the two make one "when" together:
        // "el lunes, mejor a las diez" narrows Monday down, and only the "mejor" goes.
        val endWhen = whenAtEnd(before)
        if (endWhen != null && saysWhen(after, atStart = true)) {
            if (narrows(before, endWhen, after)) return emptyList()
            val narrowed = lexical?.first?.takeIf { it >= endWhen && key(before[it]) == key(after.first()) }
            return before.subList(narrowed ?: endWhen, before.size)
        }
        if (lexical != null) {
            // One word in common: enough after a word that only ever corrects ("perdón", "digo")
            // or a "no" said on its own ("…a las cinco, no, a Pedro"), or after a pause when it is
            // a word of substance ("comprar leche… no comprar pan"). Not an article on its own
            // after a "no" that runs on: "llamar a Ana mañana, no a Marta" says who not to call.
            val substantial = key(after.first()) !in SMALL_WORDS
            if (strong || breakAfter(markerAt, after) || (paused && substantial)) return keepingWhens(before.subList(lexical.first, before.size), after)
        }
        // "Comprar leche… perdón, pan": nothing lines up, but a pause and a word that only ever
        // corrects say it was a correction, and the word or two after it take the place of as many
        // before — never a "when", a small word, or a verb starting the sentence over ("llamar a
        // Juan, no, mejor escribirle").
        if (strong && paused) {
            val firstWhen = after.indexOfFirst { soundsLikeWhen(tokens[it].raw) }.let { if (it < 0) after.size else it }
            val replaced = after.take(firstWhen).takeWhile { tokens[it].stretch == tokens[after.first()].stretch }.size
            if (replaced !in 1..2 || before.size <= replaced || INFINITIVE.matches(key(after.first()))) return null
            val whens = whenPositions(before)
            val positions = before.size - replaced until before.size
            if (positions.any { it in whens || key(before[it]) in SMALL_WORDS }) return null
            return keepingWhens(before.takeLast(replaced), after)
        }
        return null
    }

    /**
     * Whether the "when" [after] a marker and the one [before] it from [endWhen] read as one
     * "when" together — every word of both read — which makes the second a narrowing of the
     * first and not a correction of it.
     */
    private fun narrows(before: List<Int>, endWhen: Int, after: List<Int>): Boolean {
        val own = whenPositions(before).filter { it >= endWhen }
        val inAfter = whenPositions(after).sorted()
        val first = inAfter.firstOrNull()?.takeIf { it <= 1 } ?: return false
        // The "when" the words after open with, and no further: what comes after it is another matter.
        val lead = inAfter.filterIndexed { i, position -> position == first + i }
        if (own.isEmpty()) return false
        val together = whenPositions(before + after)
        return own.all { it in together } && lead.all { before.size + it in together }
    }

    /**
     * [reparandum] without the "when" in it, when the correction says none of its own: "llamar a
     * Juan mañana a las cinco, no, a Pedro" corrects who, and it is still tomorrow at five.
     */
    private fun keepingWhens(reparandum: List<Int>, correction: List<Int>): List<Int> {
        if (saysWhen(correction, atStart = false)) return reparandum
        val whens = whenPositions(reparandum)
        return reparandum.filterIndexed { i, _ -> i !in whens }
    }

    /** Which of the words [indices] (positions in it) a "when" was read from. */
    private fun whenPositions(indices: List<Int>): Set<Int> {
        val (text, starts) = joined(indices)
        val spans = whenSpans(text)
        return indices.indices.filter { i ->
            val end = starts[i] + tokens[indices[i]].raw.length - 1
            spans.any { it.first <= end && starts[i] <= it.last }
        }.toSet()
    }

    /**
     * Where the words after a correction line up with the words before it: the nearest word before
     * that starts them (or one of its kind: "al" for "a"), within reach and not among [skip], and
     * how many words in a row are the very same from there. Index into [before], and the count.
     */
    private fun alignment(before: List<Int>, after: List<Int>, skip: Set<Int>): Pair<Int, Int>? {
        val first = key(after.first())
        val from = (before.size - 1 downTo (before.size - REACH - skip.size).coerceAtLeast(0))
            .firstOrNull { it !in skip && same(key(before[it]), first) } ?: return null
        var length = 1
        while (from + length < before.size && length < after.size && key(before[from + length]) == key(after[length])) length++
        return from to length
    }

    /** Whether the marker stands on its own: a comma or a pause after it ("no, a las seis"). */
    private fun breakAfter(markerAt: List<Int>, after: List<Int>): Boolean =
        tokens[markerAt.last()].raw.last() in BREAKS || tokens[after.first()].stretch != tokens[markerAt.last()].stretch

    /** Whether a pause or a comma stands beside the marker: a correction is nearly always said with one. */
    private fun pausedAround(markerAt: List<Int>, before: List<Int>, after: List<Int>): Boolean {
        val first = tokens[markerAt.first()]
        val last = tokens[markerAt.last()]
        return tokens[before.last()].stretch != first.stretch || tokens[after.first()].stretch != last.stretch ||
            tokens[before.last()].raw.last() in BREAKS || markerAt.any { tokens[it].raw.last() in BREAKS }
    }

    /**
     * Where the "when" the words [before] end with starts (an index into [before]), with the
     * article that says it ("el lunes", "on Friday"), or null when they end with none.
     */
    private fun whenAtEnd(before: List<Int>): Int? {
        val (text, starts) = joined(before)
        val spans = whenSpans(text).sortedBy { it.first }
        val lastWord = starts.last()
        var group = spans.lastOrNull { it.last >= lastWord } ?: return null
        // A "when" said in pieces is one: "mañana a las cinco".
        for (span in spans.reversed()) if (span.last < group.first && group.first - span.last <= 2) group = span.first..group.last
        val start = starts.indexOfLast { it <= group.first }.takeIf { it >= 0 } ?: return null
        return if (start > 0 && key(before[start - 1]) in WHEN_ARTICLES) start - 1 else start
    }

    /**
     * Whether the words [after] a correction say a "when" — [atStart], one they open with, or an
     * article and one: "pasado mañana", "el jueves".
     */
    private fun saysWhen(after: List<Int>, atStart: Boolean): Boolean {
        val (text, starts) = joined(after)
        val reach = if (atStart) starts.getOrElse(2) { text.length } else text.length
        return whenSpans(text).any { it.first < reach }
    }

    private fun joined(indices: List<Int>): Pair<String, IntArray> {
        val out = StringBuilder()
        val starts = IntArray(indices.size)
        indices.forEachIndexed { i, index ->
            if (i > 0) out.append(' ')
            starts[i] = out.length
            out.append(tokens[index].raw)
        }
        return out.toString() to starts
    }

    /** A number, in digits or words — not "un" or "a", which are as often an article. */
    private fun isNumber(index: Int): Boolean = key(index).any(Char::isDigit) || (key(index) !in ARTICLE_AMOUNTS && amountIn(key(index)) != null)

    /**
     * "Comprar pan… comprar pan y leche", "el día… el día 15": a stretch that opens with the very
     * words the one before it ended on is those words said again, and the first try goes. Word for
     * word: "ir a la peluquería… a las 5" goes on, it does not start over.
     */
    fun restarts() {
        for (stretch in tokens.map { it.stretch }.distinct().drop(1)) {
            val words = living()
            val again = words.filter { tokens[it].stretch == stretch }
            val earlier = words.filter { tokens[it].stretch < stretch }
            if (again.isEmpty() || earlier.isEmpty()) continue
            val previous = earlier.filter { tokens[it].stretch == tokens[earlier.last()].stretch }
            // The longest end of the stretch before that the new one opens with.
            val from = previous.indices.firstOrNull { i ->
                val tail = previous.subList(i, previous.size)
                tail.size <= again.size && tail.indices.all { key(tail[it]) == key(again[it]) }
            } ?: continue
            drop(previous.subList(from, previous.size))
        }
    }

    /**
     * "El el martes", "comprar pan comprar pan": said twice in a row, said once. Never a number —
     * "600 600 600" and "seis seis seis" are a phone — nor a name: "Bora Bora", "Duran Duran".
     */
    fun repeats() {
        fun keeps(index: Int) = isNumber(index) || (tokens[index].raw.first().isUpperCase() && !opensStretch(index))
        for (n in 3 downTo 1) {
            var words = living()
            var i = 0
            while (i + 2 * n <= words.size) {
                val first = words.subList(i, i + n)
                val second = words.subList(i + n, i + 2 * n)
                if (first.indices.all { key(first[it]) == key(second[it]) } && first.none(::keeps)) {
                    drop(first)
                    words = living()
                } else {
                    i++
                }
            }
        }
    }

    private fun opensStretch(index: Int): Boolean = index == 0 || tokens[index - 1].stretch != tokens[index].stretch
}

/** Two words the same, or of a kind: "a" and "al" start the same place, "el" and "la" the same day. */
private fun same(a: String, b: String): Boolean = a == b || KINDS.any { a in it && b in it }

private fun keyOf(raw: String): String = Words.of(raw).text.trim()

/**
 * A word that says nothing but that somebody was thinking. Folded. Not "mm" or "em", which are
 * millimetres and a name as often as a murmur.
 */
private val FILLER = Regex("e+h+m*|e+h*m{2,}|m{3,}|h+m+|u+h+m*|u+m+|e+r+m+|a+h+")

/** What opens a stretch while the speaker finds the words, when more words follow it. Folded. */
private val OPENINGS: List<List<String>> = listOf(listOf("o", "sea"), listOf("bueno"), listOf("pues"))

/**
 * A word that says the words after it correct the ones before; [strong] when it can be nothing
 * else, [needsBreak] when it is a correction only with a comma or a pause after it ("espera, mejor
 * el jueves" — but "cena a las 8, espera a Ana").
 */
private class Marker(val words: List<String>, val strong: Boolean, val needsBreak: Boolean = false)

/** Longest first, so "no, mejor" is one marker and not "no" and then "mejor". Folded. */
private val MARKERS: List<Marker> = (
    listOf(
        "me he equivocado", "quiero decir", "quise decir", "queria decir", "mejor dicho", "me equivoque", "no perdon", "no espera",
        "no mejor", "no no", "uy no", "ay no", "o sea", "perdon", "perdona", "digo", "corrijo", "rectifico",
        "scratch that", "or rather", "make that", "no wait", "no sorry", "i mean", "i meant", "sorry", "correction",
    ).map { Marker(it.split(' '), strong = true) } +
        listOf("espera", "wait", "actually").map { Marker(listOf(it), strong = true, needsBreak = true) } +
        listOf("en realidad", "bueno").map { Marker(it.split(' '), strong = false, needsBreak = true) } +
        listOf("mas bien", "no", "mejor", "rather").map { Marker(it.split(' '), strong = false) }
    ).sortedByDescending { it.words.size }

/**
 * The words before a marker that make it part of the sentence: "si no", "y no", "que no", "lo
 * mejor", "le digo", "la sala de espera". Folded.
 */
private val NOT_AFTER = setOf(
    "si", "que", "lo", "le", "les", "te", "se", "me", "nos", "os", "la", "el", "los", "las", "de", "a", "ya", "aun", "todavia",
    "es", "era", "sera", "seria", "muy", "y", "e", "pero", "sino", "ni", "o", "u", "porque", "aunque", "cuando", "como",
    "if", "do", "does", "did", "not", "will", "can", "to", "the", "i", "you", "we", "they", "it", "is", "was", "and", "but", "or", "nor", "because", "when",
)

/** "Pedir perdón": asking for it, not saying it. Folded. */
private val ASKING = setOf(
    "pedir", "pedirle", "pedirles", "pedirte", "pido", "pide", "piden", "pida", "pidas", "pidan", "pedi", "pidio", "pedile", "pidele",
)

/** Words too small to line a correction up with on their own. Folded. */
private val SMALL_WORDS = setOf(
    "a", "al", "de", "del", "el", "la", "los", "las", "lo", "en", "y", "e", "o", "u", "un", "una", "por", "para", "con", "que",
    "the", "an", "to", "at", "on", "in", "for", "of", "and", "or", "with",
)

/** What says a "when" with it, and goes with it when it is corrected: "el lunes", "on Friday". Folded. */
private val WHEN_ARTICLES = setOf("el", "la", "los", "las", "este", "esta", "on", "the", "this")

/** Amount words that are as often an article. Folded. */
private val ARTICLE_AMOUNTS = setOf("un", "una", "a", "an")

/** A Spanish infinitive, with a pronoun or not: "escribirle", "llamar", "comprarlo". Folded. */
private val INFINITIVE = Regex("\\p{L}+(?:ar|er|ir)(?:se|le|les|lo|la|los|las|me|te|nos)?")

/** What ends a word said before a pause: a comma, a full stop, an ellipsis. */
private const val BREAKS = ",.;:…"

/** Words that start the same kind of thing. Folded. */
private val KINDS: List<Set<String>> = listOf(setOf("a", "al"), setOf("de", "del"), setOf("el", "la", "los", "las"), setOf("un", "una"))

/** How far back a correction can reach for the word that starts what it corrects. */
private const val REACH = 6

private val WORD = Regex("\\S+")
