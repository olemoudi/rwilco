package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * What a settings blob reset to the factory looks like, as the vault's guard needs to tell it:
 * nothing in it that only a person puts there. Bookkeeping the app writes on its own (the version
 * last seen, a milestone, a snooze counted) is no evidence either way. Read off the JSON as
 * written, so a blob this build cannot decode still says what it carries.
 */
class SettingsOwnTest {

    private fun own(settings: AppSettings) = ReminderCodec.settingsHoldTheirOwn(ReminderCodec.encodeSettings(settings))

    @Test
    fun `the factory settings hold nothing of anybody's`() {
        assertEquals(false, own(AppSettings()))
        assertEquals(false, own(AppSettings(lastSeenVersionCode = 228, snoozeUses = mapOf("TEN" to 3), disclaimerRead = true)), "what the app writes by itself")
    }

    @Test
    fun `anything a person kept is theirs`() {
        val home = SavedPlace("Casa", 40.4, -3.7, 100, id = "p1")
        assertEquals(true, own(AppSettings(savedPlaces = listOf(home))))
        assertEquals(true, own(AppSettings(savedWindows = listOf(SavedWindow("Por la tarde", LocalTime.of(16, 0), LocalTime.of(20, 0))))))
        assertEquals(true, own(AppSettings(presets = listOf(Preset(id = "pre1", name = "Pastillas", createdAt = java.time.Instant.EPOCH)))))
        assertEquals(true, own(AppSettings(hiddenTexts = listOf("comprar pan"))))
        assertEquals(true, own(AppSettings(customSnoozes = listOf("45m"))))
        assertEquals(true, own(AppSettings(tagPrefs = listOf(TagPref("casa", pinned = true)))))
    }

    @Test
    fun `a blob this build cannot decode still says what it carries`() {
        // A field whose type moved under a later build: the whole blob stops decoding.
        val unknownShape = """{"haptics":"sometimes","savedPlaces":[{"name":"Casa","lat":40.4,"lng":-3.7,"radiusM":100}]}"""
        assertNull(ReminderCodec.decodeSettingsOrNull(unknownShape), "the premise: it does not decode")
        assertEquals(true, ReminderCodec.settingsHoldTheirOwn(unknownShape))
        assertNull(ReminderCodec.settingsHoldTheirOwn("{\"theme\":"), "not JSON at all says nothing")
    }
}
