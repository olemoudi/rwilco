package dev.rwilco.model

import java.util.Locale

/*
 * Tidying what the editor offers back.
 *
 * The tags and the texts on offer are not a list somebody keeps — they are read off everything
 * ever written. So mending one means mending the reminders that carry it, and these say which
 * reminders those are. Each returns ONLY what changed, so a rename touches three rows rather
 * than three hundred.
 *
 * `updatedAt` is deliberately left alone: the ranking behind the offers reads it as "when this
 * was last used", and fixing a typo is not using anything.
 */

/** Every reminder carrying [tag] (case-insensitively), with the tag renamed to [to]. */
fun renameTagIn(reminders: List<Reminder>, tag: String, to: String): List<Reminder> {
    val replacement = normalizeTag(to) ?: return emptyList()
    val key = tag.lowercase(Locale.ROOT)
    return reminders.mapNotNull { reminder ->
        if (reminder.tags.none { it.lowercase(Locale.ROOT) == key }) return@mapNotNull null
        // Through normalizeTags so renaming "compra" to an existing "casa" merges rather than
        // leaving the reminder wearing the same tag twice.
        val tags = normalizeTags(reminder.tags.map { if (it.lowercase(Locale.ROOT) == key) replacement else it })
        reminder.takeIf { tags != it.tags }?.copy(tags = tags)
    }
}

/** Every reminder carrying [tag], without it. */
fun removeTagIn(reminders: List<Reminder>, tag: String): List<Reminder> {
    val key = tag.lowercase(Locale.ROOT)
    return reminders.mapNotNull { reminder ->
        val tags = reminder.tags.filterNot { it.lowercase(Locale.ROOT) == key }
        reminder.takeIf { tags.size != it.tags.size }?.copy(tags = tags)
    }
}

/**
 * Every reminder whose words are exactly [text] (case-insensitively), reworded to [to]. The
 * whole phrase or nothing: a suggestion is one phrase, and half-replacing it inside a longer
 * sentence would rewrite reminders nobody asked about.
 */
fun renameTextIn(reminders: List<Reminder>, text: String, to: String): List<Reminder> {
    val replacement = to.trim().take(MAX_TEXT_LENGTH)
    if (replacement.isEmpty()) return emptyList()
    val key = text.trim().lowercase(Locale.ROOT)
    return reminders.mapNotNull { reminder ->
        reminder.takeIf { it.text.trim().lowercase(Locale.ROOT) == key && it.text != replacement }
            ?.copy(text = replacement)
    }
}

/**
 * The texts still worth offering: what has been written before, less what has been dismissed.
 * Dismissing hides a phrase from the offers and leaves the reminders that used it alone —
 * deleting those would be deleting somebody's history to tidy a list of suggestions.
 */
fun visibleTexts(texts: List<String>, hidden: Collection<String>): List<String> {
    if (hidden.isEmpty()) return texts
    val dismissed = hidden.mapTo(HashSet()) { it.trim().lowercase(Locale.ROOT) }
    return texts.filterNot { it.trim().lowercase(Locale.ROOT) in dismissed }
}

/** Adding to the dismissed list, case-insensitively and without repeats. */
fun withHiddenText(hidden: List<String>, text: String): List<String> {
    val phrase = text.trim()
    if (phrase.isEmpty()) return hidden
    val key = phrase.lowercase(Locale.ROOT)
    if (hidden.any { it.trim().lowercase(Locale.ROOT) == key }) return hidden
    return hidden + phrase
}
