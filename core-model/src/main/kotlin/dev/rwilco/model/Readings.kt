package dev.rwilco.model

/*
 * What else a recognizer heard (0.192.0). It offers a few readings of each stretch, best first;
 * one is taken (the person's own words choosing, `Vocabulary.choose`) and the others are kept
 * for one thing: when a word turns out wrong and is tapped, what the recognizer also heard *there*
 * is the likeliest right word. So each reading is lined up word for word against the one taken,
 * and where they differ, the other's word is an option for that word.
 */

/**
 * Where [others] — other readings of the stretch [chosen] — differ from it: each stretch of
 * [chosen] that one of them heard otherwise (a word, or two or three heard as one), and the words
 * they heard there instead, most offered first, at most [MAX_OPTIONS]. Offsets into [chosen].
 */
fun wordAlternatives(chosen: String, others: List<String>): Map<IntRange, List<String>> {
    // Each word without the punctuation around it: what is offered for "Bito," is a word for
    // "Bito", and the comma stays where it was.
    val a = TOKEN.findAll(chosen).mapNotNull { core(it.value, it.range.first) }.toList()
    val keysA = a.map { keyOf(it.value) }
    val offered = LinkedHashMap<IntRange, MutableMap<String, Int>>()
    for (other in others) {
        val b = TOKEN.findAll(other).mapNotNull { core(it.value, 0)?.value }.toList()
        if (a.size.toLong() * b.size > MAX_TABLE) continue
        val keysB = b.map(::keyOf)
        fun same(i: Int, j: Int) = keysA[i] == keysB[j]
        // The longest run of words both say, as a table: what lines up.
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (same(i, j)) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            if (i < a.size && j < b.size && same(i, j)) {
                i++
                j++
                continue
            }
            val (fromA, fromB) = i to j
            while (i < a.size && j < b.size && !same(i, j)) if (lcs[i + 1][j] >= lcs[i][j + 1]) i++ else j++
            while (i < a.size && (j >= b.size || !same(i, j)) && lcs[i + 1][j] == lcs[i][j]) i++
            while (j < b.size && (i >= a.size || !same(i, j)) && lcs[i][j + 1] == lcs[i][j]) j++
            val gone = i - fromA
            val come = j - fromB
            when {
                gone == 0 || come == 0 -> Unit
                // Word for word: each its own option.
                gone == come && gone <= MAX_RUN -> for (k in 0 until gone) offer(offered, a[fromA + k].range, b[fromB + k])
                // One heard as a few, or a few as one: the whole of it is the option.
                gone <= MAX_RUN && come <= MAX_RUN -> offer(offered, a[fromA].range.first..a[i - 1].range.last, b.subList(fromB, j).joinToString(" "))
            }
        }
    }
    return offered.mapValues { (_, counts) -> counts.entries.sortedByDescending { it.value }.map { it.key }.take(MAX_OPTIONS) }
}

/** A word, the punctuation off either end, and where it is — or null for punctuation alone. */
private class Core(val value: String, val range: IntRange)

private fun core(token: String, at: Int): Core? {
    val start = token.indexOfFirst(Char::isLetterOrDigit).takeIf { it >= 0 } ?: return null
    val end = token.indexOfLast(Char::isLetterOrDigit)
    return Core(token.substring(start, end + 1), (at + start)..(at + end))
}

private fun offer(offered: MutableMap<IntRange, MutableMap<String, Int>>, range: IntRange, word: String) {
    val counts = offered.getOrPut(range) { LinkedHashMap() }
    counts[word] = (counts[word] ?: 0) + 1
}

private fun keyOf(raw: String): String = Words.of(raw).text.trim()

private val TOKEN = Regex("\\S+")

/** How many options a word is offered from the other readings. */
private const val MAX_OPTIONS = 3

/** How many words in a row two readings may differ by and still be lined up. */
private const val MAX_RUN = 3

/** Past this many words, two readings are not lined up at all. */
private const val MAX_TABLE = 10_000L
