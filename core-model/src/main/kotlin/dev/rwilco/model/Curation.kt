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
 * A rule copies a saved place — name, pin and radius — so that it rings by its own circle and a
 * place deleted in Settings takes nothing with it; and it keeps the place's key beside the copy
 * ([Trigger.Location.placeId]), which is how these find it again when the place is edited.
 *
 * A rule written before keys existed has none, and is known the only ways it can be: the same
 * pin to the last digit (a chip copies it exactly) or the same name. Carried over, it is given
 * the key, so from then on it is found by that alone.
 *
 * What moves: the pin whenever the edit moved it — the place is where it is, whatever a copy
 * held — and the radius and the name only where the copy still had the old ones, so a circle
 * one reminder widened for itself, or a place it called something else, stays its own.
 */

private fun SavedPlace.isOf(placeId: String?, lat: Double, lng: Double, label: String): Boolean =
    if (placeId != null) placeId == id
    else (lat == this.lat && lng == this.lng) || label.trim().equals(this.label.trim(), ignoreCase = true)

private class PlaceEdit(val old: SavedPlace, val new: SavedPlace) {
    val moved = old.lat != new.lat || old.lng != new.lng
    fun lat(own: Double) = if (moved) new.lat else own
    fun lng(own: Double) = if (moved) new.lng else own
    fun radius(own: Int) = if (own == old.radiusM) new.radiusM else own
    fun label(own: String) = if (own.trim().equals(old.label.trim(), ignoreCase = true)) new.label else own
    fun key(own: String?) = new.id.ifBlank { null } ?: own
}

private fun Trigger.Location.movedBy(edit: PlaceEdit): Trigger.Location =
    if (!edit.old.isOf(placeId, lat, lng, label)) this
    else copy(lat = edit.lat(lat), lng = edit.lng(lng), radiusM = edit.radius(radiusM), label = edit.label(label), placeId = edit.key(placeId))

private fun Condition.movedBy(edit: PlaceEdit): Condition =
    if (this !is Condition.AtPlace || !edit.old.isOf(placeId, lat, lng, label)) this
    else copy(lat = edit.lat(lat), lng = edit.lng(lng), radiusM = edit.radius(radiusM), label = edit.label(label), placeId = edit.key(placeId))

private fun List<TriggerRule>.movedBy(edit: PlaceEdit): List<TriggerRule> = map { rule ->
    rule.copy(
        trigger = (rule.trigger as? Trigger.Location)?.movedBy(edit) ?: rule.trigger,
        conditions = rule.conditions.map { it.movedBy(edit) },
    )
}

private fun Recurrence.movedBy(edit: PlaceEdit): Recurrence =
    withConditions(conditions.map { it.movedBy(edit) })

/**
 * Every reminder still to ring with a place taken from [old], carried over to [new]: its rules,
 * their fences, its calendar's fences and a snooze waiting at that door. Only the rows that
 * changed. DONE ones are left as they were written: they are history, not plans.
 */
fun movePlaceIn(reminders: List<Reminder>, old: SavedPlace, new: SavedPlace): List<Reminder> {
    if (old == new) return emptyList()
    val edit = PlaceEdit(old, new)
    return reminders.mapNotNull { reminder ->
        if (reminder.status == Status.DONE) return@mapNotNull null
        val moved = reminder.copy(
            rules = reminder.rules.movedBy(edit),
            recurrence = reminder.recurrence.movedBy(edit),
            snoozedToPlace = reminder.snoozedToPlace?.movedBy(edit),
        )
        moved.takeIf { it != reminder }
    }
}

/**
 * Every reminder still to ring that takes something from [place] — a rule, a fence, its
 * calendar's fence or a snooze at that door — known the way an edit knows them. What deleting
 * the place is asked about: those can go with it, or stay on their own copies of the circle.
 */
fun placeUsersOf(reminders: List<Reminder>, place: SavedPlace): List<Reminder> {
    fun Trigger.Location.takes() = place.isOf(placeId, lat, lng, label)
    fun Condition.takes() = this is Condition.AtPlace && place.isOf(placeId, lat, lng, label)
    return reminders.filter { reminder ->
        reminder.status != Status.DONE && (
            reminder.rules.any { rule -> (rule.trigger as? Trigger.Location)?.takes() == true || rule.conditions.any { it.takes() } } ||
                reminder.recurrence.conditions.any { it.takes() } ||
                reminder.snoozedToPlace?.takes() == true
            )
    }
}

/** The same, on the presets: the whole list back, since the settings blob is written whole. */
fun movePlaceInPresets(presets: List<Preset>, old: SavedPlace, new: SavedPlace): List<Preset> =
    if (old == new) presets
    else PlaceEdit(old, new).let { edit -> presets.map { it.copy(rules = it.rules.movedBy(edit), recurrence = it.recurrence.movedBy(edit)) } }
