package dev.rwilco.ui.settings

import dev.rwilco.R
import dev.rwilco.model.fold
import dev.rwilco.model.fuzzyScore

/**
 * Finding a setting by the word you happen to have for it.
 *
 * Eleven folding rows and no way in but knowing where things live: the quiet hours are inside
 * "Tu día", the battery and the exact alarms inside "Avisos", and nothing on the screen says so.
 *
 * The match is the app's own — [fuzzyScore] over [fold], so accents and case do not matter — and
 * it is asked of three things: what a row is called, what its closed line says *right now*, and
 * the words somebody would look for it by, which is the part a title cannot carry ("no molestar",
 * "batería", "horas de silencio"). Pure, so the index can be tested without a screen.
 */
data class SettingsEntry(
    /** The row's title, which is also what identifies it: it is what the rows are drawn by. */
    val title: String,
    /**
     * What people call the thing, in this language.
     *
     * The closed row's own summary is deliberately **not** indexed: every one of them is worked
     * out where the row is drawn, and a second copy here would be eleven sentences to keep in
     * step. Anything worth finding a row by goes in these words instead, which is one list.
     */
    val keywords: List<String>,
)

/**
 * The titles of the rows that answer [query] — or **null for a blank one**.
 *
 * Not searching is not the same as nothing matching, and a screen that showed nothing until
 * somebody typed would be worse than the one with no search in it at all.
 */
fun settingsMatches(entries: List<SettingsEntry>, query: String): Set<String>? {
    val needle = fold(query.trim())
    if (needle.isEmpty()) return null
    return entries.filter { it.answers(needle) }.mapTo(LinkedHashSet()) { it.title }
}

private fun SettingsEntry.answers(needle: String): Boolean =
    fuzzyScore(needle, fold(title)) != null || keywords.any { fuzzyScore(needle, fold(it)) != null }

/**
 * The index, in the order the screen lays its rows out: each row's title, and the words it can be
 * found by. The backup is in it too — it is a row in that index, not a group — and a row added
 * without its words is what `SettingsSearchTest` fails over.
 */
internal val SETTINGS_INDEX: List<Pair<Int, Int>> = listOf(
    R.string.settings_alerts to R.array.settings_keywords_alerts,
    R.string.settings_sound_title to R.array.settings_keywords_sound,
    R.string.settings_vibration_strength to R.array.settings_keywords_vibration,
    R.string.settings_net_title to R.array.settings_keywords_net,
    R.string.settings_group_new to R.array.settings_keywords_new,
    R.string.settings_group_day to R.array.settings_keywords_day,
    R.string.settings_contacts_title to R.array.settings_keywords_contacts,
    R.string.settings_places to R.array.settings_keywords_places,
    R.string.settings_group_look to R.array.settings_keywords_look,
    R.string.vault_card_title to R.array.settings_keywords_backup,
    R.string.settings_updates to R.array.settings_keywords_updates,
    R.string.settings_about to R.array.settings_keywords_about,
)
