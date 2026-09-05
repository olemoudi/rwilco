package dev.rwilco.model

import kotlinx.serialization.Serializable
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

/**
 * What the person has said about a tag, as opposed to what their reminders say.
 *
 * A tag is normally not a record of its own — it is read off the reminders that carry it, which
 * is why the last one to lose it makes it stop existing, and why nothing has to be tidied up
 * afterwards. This is the exception, and it holds only what a reminder cannot answer: which
 * tags are worth the front of Home's row, and a tag written down before anything wears it. A
 * tag with neither has no row here at all.
 */
@Serializable
data class TagPref(val name: String, val pinned: Boolean = false)

/** The pinned tags, in the order they were pinned: what goes to the front of a row of them. */
fun pinnedTags(prefs: List<TagPref>): List<String> = prefs.filter { it.pinned }.map { it.name }

/**
 * [tags] with the pinned ones brought to the front, in the order they were pinned.
 *
 * A stable sort, so everything else keeps the order it arrived in — which is by use, wherever
 * the list came from [tagsInUse] or [suggestedTags]. Pinning is an order and not a filter: a
 * tag nobody pinned is still on the row, just further along it.
 */
fun pinnedFirst(tags: List<String>, prefs: List<TagPref>): List<String> {
    val pinned = pinnedTags(prefs)
    if (pinned.isEmpty()) return tags
    return tags.sortedBy { tag ->
        val at = pinned.indexOfFirst { it.equals(tag, ignoreCase = true) }
        if (at < 0) Int.MAX_VALUE else at
    }
}

/**
 * Every tag there is: the ones [tags] found on reminders, then the ones written down that
 * nothing wears yet, all pinned-first.
 *
 * The spelling on the reminders wins over the spelling in the row, because that is the one
 * somebody is looking at; the two only drift apart if a rename reaches one and not the other,
 * which is what [withTagRenamed] is for.
 */
fun knownTags(tags: List<String>, prefs: List<TagPref>): List<String> =
    pinnedFirst(normalizeTags(tags + prefs.map { it.name }), prefs)

/**
 * A tag's row, written or rewritten.
 *
 * Also how a tag is created: written down unpinned, it exists — it is offered by the editor and
 * listed with the rest — without anything wearing it yet. Unpinning leaves the row where it is
 * for the same reason: the row may be the only place the tag exists.
 */
fun withTagPref(prefs: List<TagPref>, tag: String, pinned: Boolean): List<TagPref> {
    val name = normalizeTag(tag) ?: return prefs
    val key = name.lowercase(Locale.ROOT)
    if (prefs.none { it.name.lowercase(Locale.ROOT) == key }) return prefs + TagPref(name, pinned)
    return prefs.map { if (it.name.lowercase(Locale.ROOT) == key) it.copy(pinned = pinned) else it }
}

/** A rename reaches the row too, or the tag stays pinned under a name nothing carries. */
fun withTagRenamed(prefs: List<TagPref>, from: String, to: String): List<TagPref> {
    val name = normalizeTag(to) ?: return prefs
    val key = from.lowercase(Locale.ROOT)
    if (prefs.none { it.name.lowercase(Locale.ROOT) == key }) return prefs
    // Renaming onto a tag that already has a row merges the two, the way renameTagIn merges the
    // tags on a reminder: pinned if either of them was, and in the first one's place.
    val merged = LinkedHashMap<String, TagPref>()
    for (pref in prefs) {
        val renamed = if (pref.name.lowercase(Locale.ROOT) == key) pref.copy(name = name) else pref
        val at = renamed.name.lowercase(Locale.ROOT)
        val kept = merged[at]
        merged[at] = if (kept == null) renamed else kept.copy(pinned = kept.pinned || renamed.pinned)
    }
    return merged.values.toList()
}

/** What a removed tag leaves behind: nothing. */
fun withTagForgotten(prefs: List<TagPref>, tag: String): List<TagPref> {
    val key = tag.lowercase(Locale.ROOT)
    return prefs.filterNot { it.name.lowercase(Locale.ROOT) == key }
}
