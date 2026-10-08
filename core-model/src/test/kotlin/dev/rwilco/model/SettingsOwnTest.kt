package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a settings blob reset to the factory looks like, as the vault's guard needs to tell it:
 * nothing in it that only a person puts there. Bookkeeping the app writes on its own (the version
 * last seen, a milestone, a snooze counted) is no evidence either way.
 */
class SettingsOwnTest {

    @Test
    fun `the factory settings hold nothing of anybody's`() {
        assertTrue(AppSettings().holdsNothingOfTheirOwn())
        assertTrue(AppSettings(lastSeenVersionCode = 228, snoozeUses = mapOf("TEN" to 3), disclaimerRead = true).holdsNothingOfTheirOwn(), "what the app writes by itself")
    }

    @Test
    fun `anything a person kept is theirs`() {
        val home = SavedPlace("Casa", 40.4, -3.7, 100, id = "p1")
        assertFalse(AppSettings(savedPlaces = listOf(home)).holdsNothingOfTheirOwn())
        assertFalse(AppSettings(hiddenTexts = listOf("comprar pan")).holdsNothingOfTheirOwn())
        assertFalse(AppSettings(customSnoozes = listOf("45m")).holdsNothingOfTheirOwn())
        assertFalse(AppSettings(tagPrefs = listOf(TagPref("casa", pinned = true))).holdsNothingOfTheirOwn())
    }
}
