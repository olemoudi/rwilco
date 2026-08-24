package dev.rwilco.model

import java.util.Locale

const val MAX_TAG_LENGTH = 24

private val innerWhitespace = Regex("\\s+")

/** One tag as the user meant it: trimmed, single-spaced, capped; null when nothing is left. */
fun normalizeTag(raw: String): String? =
    raw.trim().replace(innerWhitespace, " ").take(MAX_TAG_LENGTH).trim().ifEmpty { null }

/**
 * Tags in insertion order, de-duplicated case-insensitively so "Compra" and "compra" are one tag.
 * The first spelling wins: it is the one the person typed on purpose.
 */
fun normalizeTags(raw: Iterable<String>): List<String> {
    val seen = HashSet<String>()
    val result = ArrayList<String>()
    for (candidate in raw) {
        val tag = normalizeTag(candidate) ?: continue
        if (seen.add(tag.lowercase(Locale.ROOT))) result += tag
    }
    return result
}
