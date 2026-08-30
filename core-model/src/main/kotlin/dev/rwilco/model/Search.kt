package dev.rwilco.model

import java.text.Normalizer
import java.util.Locale

/**
 * Finding something again.
 *
 * Two things can be searched and they are not the same thing: a reminder (open it) and a tag
 * (see everything under it). Both come back from one query, each saying which it is, because a
 * person typing "compra" does not know yet whether what they want is the reminder or the
 * shelf it sits on.
 *
 * The match is forgiving on purpose: accents and case are ignored ("mañana" is found by
 * "manana"), and the letters of the query only have to appear in order, so "cmp" finds
 * "comprar pan". Being forgiving costs nothing here — the list is small and one screen wide —
 * while an exact match asks the person to remember how they wrote it.
 */
sealed interface SearchHit {
    /** Higher is a better match; the list comes back sorted by it. */
    val score: Int

    data class OfReminder(val reminder: Reminder, override val score: Int) : SearchHit

    /** [count] open reminders carry this tag: what the filter would show. */
    data class OfTag(val tag: String, val count: Int, override val score: Int) : SearchHit
}

/**
 * Everything [query] matches among the reminders and the tags the open ones use, best first.
 * A blank query matches nothing: an empty list is what "not searching" looks like.
 *
 * A reminder is matched on its own words only. Its tags are matched as tags — one row for
 * "compra" beats five identical-looking reminder rows that all happen to carry it.
 *
 * What was done is found too, after everything that is open: the history kept three months of
 * it and the only way through was scrolling, so "¿cambié el filtro?" had no answer here. It
 * comes last whatever its score — somebody typing on Home is looking for something to do
 * before something they did — and the hit says which it is.
 */
fun search(reminders: List<Reminder>, query: String, limit: Int = 20): List<SearchHit> {
    val needle = fold(query)
    if (needle.isEmpty()) return emptyList()

    val hits = ArrayList<SearchHit>()
    for (reminder in reminders) {
        val score = fuzzyScore(needle, fold(reminder.text)) ?: continue
        hits += SearchHit.OfReminder(reminder, score)
    }
    for ((tag, count) in tagCounts(reminders)) {
        val score = fuzzyScore(needle, fold(tag)) ?: continue
        hits += SearchHit.OfTag(tag, count, score)
    }
    return hits
        .sortedWith(
            compareBy<SearchHit> { it.done }
                .thenByDescending { it.score }
                // A tag ahead of a reminder on the same score: it is the broader answer, and
                // the reminders under it are one tap away.
                .thenBy { if (it is SearchHit.OfTag) 0 else 1 }
                .thenBy { it.label().lowercase(Locale.ROOT) },
        )
        .take(limit)
}

private fun SearchHit.label(): String = when (this) {
    is SearchHit.OfReminder -> reminder.text
    is SearchHit.OfTag -> tag
}

/** Whether this is a reminder already dealt with; a tag is only ever counted over the open ones. */
val SearchHit.done: Boolean get() = this is SearchHit.OfReminder && reminder.status == Status.DONE

/** Open tags with how many reminders use them; the first spelling of each wins, as everywhere. */
private fun tagCounts(reminders: List<Reminder>): List<Pair<String, Int>> {
    val counts = LinkedHashMap<String, Pair<String, Int>>()
    for (reminder in reminders) {
        if (reminder.status == Status.DONE) continue
        for (tag in reminder.tags) {
            val key = tag.lowercase(Locale.ROOT)
            val (spelling, count) = counts[key] ?: (tag to 0)
            counts[key] = spelling to count + 1
        }
    }
    return counts.values.toList()
}

/**
 * How well [needle] matches [haystack], both already folded, or null when it does not at all.
 * The bands are ordered the way a person ranks them: the whole thing, the start of it, the
 * start of a word in it, somewhere in it, and finally its letters in order.
 */
fun fuzzyScore(needle: String, haystack: String): Int? {
    if (needle.isEmpty() || haystack.isEmpty()) return null
    // Shorter matches rank above longer ones at the same band: "pan" is a better answer to
    // "pan" than "comprar pan y leche" is.
    val brevity = (60 - haystack.length).coerceIn(0, 60)
    if (needle == haystack) return EXACT + brevity
    if (haystack.startsWith(needle)) return PREFIX + brevity
    val at = haystack.indexOf(needle)
    if (at >= 0) {
        val onWordBoundary = at > 0 && !haystack[at - 1].isLetterOrDigit()
        return (if (onWordBoundary) WORD else CONTAINS) + brevity
    }
    return subsequenceScore(needle, haystack)?.plus(brevity / 2)
}

/**
 * The letters of [needle] in order somewhere in [haystack]. Matching the first letter of a word
 * is what makes "cmp" mean "comprar manzanas para el postre" rather than a coincidence, so it
 * is worth more than a letter in the middle; every jump costs.
 */
private fun subsequenceScore(needle: String, haystack: String): Int? {
    var from = 0
    var jumps = 0
    var bonus = 0
    var first = -1
    for (letter in needle) {
        val found = haystack.indexOf(letter, from)
        if (found < 0) return null
        if (first < 0) first = found
        if (found > from) jumps++
        if (found == 0 || !haystack[found - 1].isLetterOrDigit()) bonus += WORD_START_BONUS
        from = found + 1
    }
    return (SUBSEQUENCE + bonus - jumps * JUMP_COST - first.coerceAtMost(MAX_HEAD_COST)).coerceAtLeast(1)
}

/** Case and accents out of the way: what is left is what the two strings are really made of. */
fun fold(text: String): String {
    val lower = text.trim().lowercase(Locale.ROOT)
    if (lower.all { it.code < 0x80 }) return lower
    return Normalizer.normalize(lower, Normalizer.Form.NFD).replace(diacritics, "")
}

private val diacritics = Regex("\\p{Mn}+")

private const val EXACT = 1_000
private const val PREFIX = 800
private const val WORD = 600
private const val CONTAINS = 400
private const val SUBSEQUENCE = 200
private const val WORD_START_BONUS = 12
private const val JUMP_COST = 8
private const val MAX_HEAD_COST = 40
