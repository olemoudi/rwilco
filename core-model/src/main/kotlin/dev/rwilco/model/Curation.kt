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

/**
 * Whether renaming [from] to [to] would **merge two tags into one**, and how many reminders the
 * one left would be on — or null when it is only a rename.
 *
 * A rename has an inverse (rename it back) and so it asks nothing; a merge has none, because
 * afterwards nothing says which reminders wore which, and the house rule is a question where
 * there is no way back. It used to be neither: the rows were rewritten, the snackbar said how
 * many, and the undo was quietly withheld. [known] is every tag that exists without being worn
 * — written down in the panel, or carried by a preset — since merging onto one of those leaves
 * one row where there were two just the same.
 *
 * A respelling ("casa" to "Casa") is a rename: the tag it lands on is itself.
 */
fun tagMergeCount(reminders: List<Reminder>, known: List<String>, from: String, to: String): Int? {
    val target = normalizeTag(to)?.lowercase(Locale.ROOT) ?: return null
    val source = from.lowercase(Locale.ROOT)
    if (target == source) return null
    val exists = known.any { it.lowercase(Locale.ROOT) == target } ||
        reminders.any { reminder -> reminder.tags.any { it.lowercase(Locale.ROOT) == target } }
    if (!exists) return null
    return reminders.count { reminder -> reminder.tags.any { it.lowercase(Locale.ROOT).let { tag -> tag == source || tag == target } } }
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
 * The same two edits, on the tags a **preset** carries.
 *
 * A preset keeps tags of its own and puts them on every reminder made from it, so a tag removed
 * from every reminder and left in a preset is not removed — it comes back the next time that
 * shape is used, and a renamed one comes back under its old spelling alongside the new. "Quitar
 * esta etiqueta de todo" has to mean the shapes too, or it is not "de todo".
 *
 * The whole list back rather than only what changed: this one is written to the settings blob,
 * which is replaced whole, and not to rows that can be saved individually.
 */
fun renameTagInPresets(presets: List<Preset>, tag: String, to: String): List<Preset> {
    val replacement = normalizeTag(to) ?: return presets
    val key = tag.lowercase(Locale.ROOT)
    return presets.map { preset ->
        if (preset.tags.none { it.lowercase(Locale.ROOT) == key }) return@map preset
        // Through normalizeTags for the reason renameTagIn is: renaming onto a tag the preset
        // already carries merges them rather than leaving it wearing the same tag twice.
        preset.copy(tags = normalizeTags(preset.tags.map { if (it.lowercase(Locale.ROOT) == key) replacement else it }))
    }
}

fun removeTagInPresets(presets: List<Preset>, tag: String): List<Preset> {
    val key = tag.lowercase(Locale.ROOT)
    return presets.map { preset ->
        preset.copy(tags = preset.tags.filterNot { it.lowercase(Locale.ROOT) == key })
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

/*
 * A saved place, edited in Settings.
 *
 * A rule does not point at a saved place, it copies it — name, pin and radius — so moving "la
 * oficina" in Settings used to reach no reminder at all. These carry the edit over, once
 * somebody has been asked. What counts as using a place is its **pin**: the chips copy it to the
 * last digit, and a name is the part most likely to have been retyped. And only what the edit
 * changed is carried, and only where the copy still had the old value: a reminder that widened
 * the circle for itself keeps its own radius when the pin moves.
 */

private fun SavedPlace.pinOf(lat: Double, lng: Double): Boolean = lat == this.lat && lng == this.lng

private fun Trigger.Location.movedTo(old: SavedPlace, new: SavedPlace): Trigger.Location =
    if (!old.pinOf(lat, lng)) this
    else copy(
        lat = new.lat,
        lng = new.lng,
        radiusM = if (radiusM == old.radiusM) new.radiusM else radiusM,
        label = if (label == old.label) new.label else label,
    )

private fun Condition.movedTo(old: SavedPlace, new: SavedPlace): Condition =
    if (this !is Condition.AtPlace || !old.pinOf(lat, lng)) this
    else copy(
        lat = new.lat,
        lng = new.lng,
        radiusM = if (radiusM == old.radiusM) new.radiusM else radiusM,
        label = if (label == old.label) new.label else label,
    )

private fun List<TriggerRule>.movedTo(old: SavedPlace, new: SavedPlace): List<TriggerRule> = map { rule ->
    rule.copy(
        trigger = (rule.trigger as? Trigger.Location)?.movedTo(old, new) ?: rule.trigger,
        conditions = rule.conditions.map { it.movedTo(old, new) },
    )
}

private fun Recurrence.movedTo(old: SavedPlace, new: SavedPlace): Recurrence =
    withConditions(conditions.map { it.movedTo(old, new) })

/**
 * Every reminder still to ring with a place on [old]'s pin, carried over to [new]: its rules,
 * their fences, its calendar's fences and a snooze waiting at that door. Only the rows that
 * changed. DONE ones are left as they were written: they are history, not plans.
 */
fun movePlaceIn(reminders: List<Reminder>, old: SavedPlace, new: SavedPlace): List<Reminder> {
    if (old == new) return emptyList()
    return reminders.mapNotNull { reminder ->
        if (reminder.status == Status.DONE) return@mapNotNull null
        val moved = reminder.copy(
            rules = reminder.rules.movedTo(old, new),
            recurrence = reminder.recurrence.movedTo(old, new),
            snoozedToPlace = reminder.snoozedToPlace?.movedTo(old, new),
        )
        moved.takeIf { it != reminder }
    }
}

/** The same, on the presets: the whole list back, since the settings blob is written whole. */
fun movePlaceInPresets(presets: List<Preset>, old: SavedPlace, new: SavedPlace): List<Preset> =
    if (old == new) presets
    else presets.map { it.copy(rules = it.rules.movedTo(old, new), recurrence = it.recurrence.movedTo(old, new)) }
