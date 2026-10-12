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

    /** Where in [heard] a stretch of [text] was said; the spaces [text] put between words map to the word after them. */
    fun inHeard(range: IntRange): IntRange {
        if (range.isEmpty()) return range
        fun at(offset: Int, forward: Boolean): Int? {
            val order = if (forward) words.indices else words.indices.reversed()
            for (i in order) {
                if (!kept[i]) continue
                val start = starts[i]
                val end = start + words[i].last - words[i].first
                if (offset in start..end) return words[i].first + (offset - start)
                if (forward && start > offset) return words[i].first
                if (!forward && end < offset) return words[i].last
            }
            return null
        }
        val first = at(range.first, forward = true) ?: return IntRange.EMPTY
        val last = at(range.last, forward = false) ?: return IntRange.EMPTY
        return if (first > last) IntRange.EMPTY else first..last
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
    val repair = Repair(tokens) { text -> readWhen(text, now, zone, settings.dayParts, settings.savedPlaces, settings.dayShape)?.spans.orEmpty() }
    repair.hesitations()
    repair.corrections()
    repair.restarts()
    repair.repeats()
    return Repaired(heard, tokens.map { it.range }, repair.alive)
}

/** One word as heard: where, in which stretch, and folded for comparing ("Mañana," is "manana"). */
private class Token(val range: IntRange, val stretch: Int, val key: String, val raw: String)

private class Repair(val tokens: List<Token>, val whenSpans: (String) -> List<IntRange>) {
    val alive = BooleanArray(tokens.size) { true }

    private fun living(): List<Int> = tokens.indices.filter { alive[it] }

    private fun drop(indices: Iterable<Int>) = indices.forEach { alive[it] = false }

    /** "Eh", "mmm" anywhere; "bueno", "pues", "o sea" opening a stretch that goes on. */
    fun hesitations() {
        tokens.indices.filter { FILLER.matches(tokens[it].key) }.let(::drop)
        for (stretch in tokens.map { it.stretch }.distinct()) {
            val inIt = living().filter { tokens[it].stretch == stretch }
            for (opening in OPENINGS) {
                if (inIt.size > opening.size && opening.indices.all { tokens[inIt[it]].key == opening[it] }) {
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
            val marker = MARKERS.firstOrNull { marker -> marker.words.indices.all { words.getOrNull(at + it)?.let(tokens::get)?.key == marker.words[it] } }
            if (marker == null) {
                at++
                continue
            }
            val markerAt = words.subList(at, at + marker.words.size)
            val before = words.subList(0, at)
            val after = words.subList(at + marker.words.size, words.size)
            val reparandum = reparandum(marker, markerAt, before, after)
            if (reparandum == null) {
                at += marker.words.size
                continue
            }
            drop(reparandum + markerAt)
            at = before.size - reparandum.size
        }
    }

    /**
     * What the words after [marker] correct, as indices into [tokens] — or null when nothing
     * before it lines up with them, and the marker is the thing itself.
     */
    private fun reparandum(marker: Marker, markerAt: List<Int>, before: List<Int>, after: List<Int>): List<Int>? {
        if (before.isEmpty() || after.isEmpty()) return null
        // "Si no", "que no", "pedir perdón", "lo mejor", "le digo": the word is part of the sentence.
        val previous = tokens[before.last()].key
        if (previous in NOT_AFTER || (marker.words.first().startsWith("perdon") && previous in ASKING)) return null
        // A correction with no "when" in it corrects something that is not one: what lines up
        // with it is looked for outside the "when" before it ("a Juan mañana a las cinco, no, a
        // Pedro" is Juan's "a", not the hour's).
        val lexical = alignment(before, after, skip = if (saysWhen(after, atStart = false)) emptySet() else whenPositions(before))
        // The same two words starting both sides: "a las cinco, no, a las seis".
        if (lexical != null && lexical.second >= 2) return keepingWhens(before.subList(lexical.first, before.size), after)
        // A "when" corrected by a "when": "mañana, no, pasado mañana", "al llegar a casa, no, al
        // salir del trabajo" — or only the part of it the same word opens: "tomorrow at five, no,
        // at six" corrects the hour and keeps the day.
        val endWhen = whenAtEnd(before)
        if (endWhen != null && saysWhen(after, atStart = true)) {
            val narrowed = lexical?.first?.takeIf { it >= endWhen && tokens[before[it]].key == tokens[after.first()].key }
            return before.subList(narrowed ?: endWhen, before.size)
        }
        if (lexical != null) {
            // One word in common: enough after a word that only ever corrects ("perdón", "digo"), after
            // a pause, or when it is a word of substance ("comprar leche no comprar pan"). Not an
            // article on its own: "comprar el pan no el de molde" is two breads.
            val substantial = tokens[after.first()].key !in SMALL_WORDS
            if (marker.strong || pausedAround(markerAt, before, after) || substantial) return keepingWhens(before.subList(lexical.first, before.size), after)
        }
        // "Comprar leche… perdón, pan": nothing lines up, but a pause and a word that only ever
        // corrects say it was a correction, and the word or two after it take the place of as many before.
        if (marker.strong && pausedAround(markerAt, before, after)) {
            val firstWhen = after.indexOfFirst { soundsLikeWhen(tokens[it].raw) }.let { if (it < 0) after.size else it }
            val replaced = after.take(firstWhen).takeWhile { tokens[it].stretch == tokens[after.first()].stretch }.size
            if (replaced in 1..2 && before.size > replaced) return keepingWhens(before.takeLast(replaced), after)
        }
        return null
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
     * how many words in a row agree from there. Index into [before], and the count.
     */
    private fun alignment(before: List<Int>, after: List<Int>, skip: Set<Int>): Pair<Int, Int>? {
        val first = tokens[after.first()].key
        val from = (before.size - 1 downTo (before.size - REACH - skip.size).coerceAtLeast(0))
            .firstOrNull { it !in skip && same(tokens[before[it]].key, first) } ?: return null
        var length = 0
        while (from + length < before.size && length < after.size && same(tokens[before[from + length]].key, tokens[after[length]].key)) length++
        return from to length
    }

    /** Whether a pause or a comma stands beside the marker: a correction is nearly always said with one. */
    private fun pausedAround(markerAt: List<Int>, before: List<Int>, after: List<Int>): Boolean {
        val first = tokens[markerAt.first()]
        val last = tokens[markerAt.last()]
        return tokens[before.last()].stretch != first.stretch || tokens[after.first()].stretch != last.stretch ||
            tokens[before.last()].raw.endsWith(',') || markerAt.any { tokens[it].raw.last() in ",.;:" }
    }

    /** Where the "when" the words [before] end with starts (an index into [before]), or null when they end with none. */
    private fun whenAtEnd(before: List<Int>): Int? {
        val (text, starts) = joined(before)
        val spans = whenSpans(text).sortedBy { it.first }
        val lastWord = starts.last()
        var group = spans.lastOrNull { it.last >= lastWord } ?: return null
        // A "when" said in pieces is one: "mañana a las cinco".
        for (span in spans.reversed()) if (span.last < group.first && group.first - span.last <= 2) group = span.first..group.last
        return starts.indexOfLast { it <= group.first }.takeIf { it >= 0 }
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

    /**
     * "Comprar pan… comprar pan y leche": a stretch that opens with the two words the one before
     * it opened something with is that something said again, and the first try goes.
     */
    fun restarts() {
        for (stretch in tokens.map { it.stretch }.distinct().drop(1)) {
            val words = living()
            val again = words.filter { tokens[it].stretch >= stretch }
            if (again.size < 2 || tokens[again[1]].stretch != stretch || tokens[again[0]].stretch != stretch) continue
            val earlier = words.filter { tokens[it].stretch < stretch }
            if (earlier.isEmpty()) continue
            val previous = earlier.filter { tokens[it].stretch == tokens[earlier.last()].stretch }
            val from = (0 until previous.size - 1).lastOrNull { i ->
                same(tokens[previous[i]].key, tokens[again[0]].key) && same(tokens[previous[i + 1]].key, tokens[again[1]].key)
            } ?: continue
            drop(keepingWhens(previous.subList(from, previous.size), again))
        }
    }

    /** "El el martes", "comprar pan comprar pan": said twice in a row, said once. Never a number: "600 600 600" is a phone. */
    fun repeats() {
        for (n in 3 downTo 1) {
            var words = living()
            var i = 0
            while (i + 2 * n <= words.size) {
                val first = words.subList(i, i + n)
                val second = words.subList(i + n, i + 2 * n)
                val same = first.indices.all { tokens[first[it]].key == tokens[second[it]].key }
                if (same && first.none { tokens[it].key.any(Char::isDigit) }) {
                    drop(first)
                    words = living()
                } else {
                    i++
                }
            }
        }
    }
}

/** Two words the same, or of a kind: "a" and "al" start the same place, "el" and "la" the same day. */
private fun same(a: String, b: String): Boolean = a == b || KINDS.any { a in it && b in it }

private fun keyOf(raw: String): String = Words.of(raw).text.trim()

/** A word that says nothing but that somebody was thinking. Folded. */
private val FILLER = Regex("e+h+m*|e+m{1,}|m{2,}|h+m+|u+h+m*|u+m+|e+r+m+|a+h+")

/** What opens a stretch while the speaker finds the words, when more words follow it. Folded. */
private val OPENINGS: List<List<String>> = listOf(listOf("o", "sea"), listOf("bueno"), listOf("pues"), listOf("vale"), listOf("well"), listOf("so"))

/** A word that says the words after it correct the ones before; [strong] when it can be nothing else. */
private class Marker(val words: List<String>, val strong: Boolean)

/** Longest first, so "no, mejor" is one marker and not "no" and then "mejor". Folded. */
private val MARKERS: List<Marker> = listOf(
    "me he equivocado", "quiero decir", "mejor dicho", "me equivoque", "no perdon", "no espera", "no mejor", "no no", "o sea",
    "perdon", "perdona", "digo", "corrijo", "rectifico", "espera",
    "scratch that", "or rather", "no wait", "no sorry", "i mean", "sorry", "correction", "wait", "actually",
).map { Marker(it.split(' '), strong = true) } + listOf("no", "mejor", "rather").map { Marker(listOf(it), strong = false) }

/** The words before a marker that make it part of the sentence: "si no", "que no", "lo mejor", "le digo", "la sala de espera". */
private val NOT_AFTER = setOf(
    "si", "que", "lo", "le", "les", "te", "se", "me", "nos", "os", "la", "el", "los", "las", "de", "a", "ya", "aun", "todavia", "es", "era", "sera", "seria", "muy",
    "if", "do", "does", "did", "not", "will", "can", "to", "the", "i", "you", "we", "they", "it", "is", "was",
)

/** "Pedir perdón": asking for it, not saying it. Folded. */
private val ASKING = setOf(
    "pedir", "pedirle", "pedirles", "pedirte", "pido", "pide", "piden", "pida", "pidas", "pidan", "pedi", "pidio", "pedile", "pidele",
)

/** Words too small to line a correction up with on their own. Folded. */
private val SMALL_WORDS = setOf(
    "a", "al", "de", "del", "el", "la", "los", "las", "lo", "en", "y", "e", "o", "u", "un", "una", "por", "para", "con", "que",
    "the", "a", "an", "to", "at", "on", "in", "for", "of", "and", "or", "with",
)

/** Words that start the same kind of thing. Folded. */
private val KINDS: List<Set<String>> = listOf(setOf("a", "al"), setOf("de", "del"), setOf("el", "la", "los", "las"), setOf("un", "una"))

/** How far back a correction can reach for the word that starts what it corrects. */
private const val REACH = 6

private val WORD = Regex("\\S+")
