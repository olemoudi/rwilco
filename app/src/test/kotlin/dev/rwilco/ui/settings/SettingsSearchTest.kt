package dev.rwilco.ui.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Finding a setting by the word you happen to have for it.
 *
 * The words are what matter here: a title is what the app calls the thing, and somebody looking
 * for the battery, for "no molestar" or for the quiet hours has never seen any of those titles.
 */
class SettingsSearchTest {

    private val entries = listOf(
        SettingsEntry("Avisos", listOf("batería", "no molestar", "alarmas exactas")),
        SettingsEntry("El día", listOf("horas de silencio", "dormir", "fin de semana")),
        SettingsEntry("Lugares", listOf("ubicación", "gps", "casa")),
    )

    @Test
    fun `a blank query narrows nothing at all`() {
        // Not searching is not the same as nothing matching: a screen that showed no rows until
        // somebody typed would be worse than one with no search in it.
        assertNull(settingsMatches(entries, ""))
        assertNull(settingsMatches(entries, "   "))
    }

    @Test
    fun `a query without accents finds a title that has them`() {
        assertEquals(setOf("El día"), settingsMatches(entries, "dia"))
    }

    @Test
    fun `a word finds the row whose title never mentions it`() {
        assertEquals(setOf("Avisos"), settingsMatches(entries, "no molestar"))
        assertEquals(setOf("Avisos"), settingsMatches(entries, "bateria"))
        assertEquals(setOf("El día"), settingsMatches(entries, "horas de silencio"))
        assertEquals(setOf("Lugares"), settingsMatches(entries, "GPS"))
    }

    @Test
    fun `a query nothing answers comes back empty rather than whole`() {
        assertEquals(emptySet<String>(), settingsMatches(entries, "zzzz"))
    }

    @Test
    fun `every row in the index has its own words`() {
        // The index is the one place a row can be added and forgotten: nothing fails loudly when
        // a new group arrives without the words people would look for it by, so this does.
        // Eleven folding groups plus the backup, which is a row in the index and not a group.
        assertEquals(12, SETTINGS_INDEX.size)
        assertEquals(SETTINGS_INDEX.size, SETTINGS_INDEX.map { it.first }.toSet().size, "a row twice over")
        assertTrue(SETTINGS_INDEX.all { it.second != 0 }, "a row with no words to find it by")
    }
}
