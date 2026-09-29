package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalTime

/**
 * The settings blob is decoded all at once, and a vault from a newer build — or a phone
 * downgraded — may carry a word this build has no member for. One such word used to hand back
 * `AppSettings()`: the theme, the sound, every saved place and every preset gone, and made
 * permanent by the next write. Each unknown reads as its own default now, and nothing else moves.
 */
class SettingsToleranceTest {

    private val places = listOf(SavedPlace("Casa", 40.4169, -3.7035, 200))
    private val kept = AppSettings(
        savedPlaces = places,
        defaultTime = LocalTime.of(7, 30),
        presets = listOf(Preset(id = "p1", name = "Pan", actions = setOf(Action.SOUND), createdAt = java.time.Instant.EPOCH)),
    )

    /** The blob as written, with one value replaced by a word this build has no member for. */
    private fun decoded(vararg swaps: Pair<String, String>): AppSettings {
        var blob = ReminderCodec.encodeSettings(kept)
        for ((old, new) in swaps) {
            assertTrue(old in blob, "the blob must carry '$old' for the swap to mean anything: $blob")
            blob = blob.replace(old, new)
        }
        val settings = ReminderCodec.decodeSettings(blob)
        assertEquals(places, settings.savedPlaces, "the rest of the settings must survive")
        assertEquals(LocalTime.of(7, 30), settings.defaultTime)
        return settings
    }

    @Test
    fun `an unknown theme or stacking mode reads as the default`() {
        assertEquals(ThemeMode.SYSTEM, decoded("\"theme\":\"SYSTEM\"" to "\"theme\":\"HOLOGRAPHIC\"").theme)
        assertEquals(AlertStacking.SEQUENTIAL, decoded("\"alertStacking\":\"SEQUENTIAL\"" to "\"alertStacking\":\"CAROUSEL\"").alertStacking)
        assertNull(decoded("\"defaultTriggerKind\":null" to "\"defaultTriggerKind\":\"TELEPATHY\"").defaultTriggerKind)
    }

    @Test
    fun `an achievement of a family this build has no word for is dropped, not the rest`() {
        val earned = listOf(
            Unlocked(AchievementFamily.HECHOS, 50, java.time.LocalDate.of(2026, 9, 1)),
            Unlocked(AchievementFamily.STREAK, 7, java.time.LocalDate.of(2026, 9, 2), "r1", "Pan"),
        )
        var blob = ReminderCodec.encodeSettings(kept.copy(achievements = earned))
        assertTrue("\"family\":\"HECHOS\"" in blob, blob)
        blob = blob.replace("\"family\":\"HECHOS\"", "\"family\":\"MARATHONS\"")
        val settings = ReminderCodec.decodeSettings(blob)
        assertEquals(listOf(earned[1]), settings.achievements)
        assertEquals(places, settings.savedPlaces)
    }

    @Test
    fun `an unknown action is dropped, not the set`() {
        val settings = decoded("\"defaultActions\":[\"NOTIFICATION\",\"SOUND\",\"VIBRATE\"]" to "\"defaultActions\":[\"NOTIFICATION\",\"HOLOGRAM\",\"VIBRATE\"]")
        assertEquals(setOf(Action.NOTIFICATION, Action.VIBRATE), settings.defaultActions)
    }

    @Test
    fun `an unknown sound is the phone's own tone, and an unknown insistent tone is unset`() {
        val settings = decoded(
            "\"alertSound\":{\"type\":\"system\"}" to "\"alertSound\":{\"type\":\"theremin\",\"pitch\":3}",
            "\"insistentSound\":null" to "\"insistentSound\":{\"type\":\"theremin\"}",
        )
        assertEquals(AlertSound.System, settings.alertSound)
        assertNull(settings.insistentSound)
    }

    @Test
    fun `a preset with an unknown action or match keeps its other actions`() {
        val settings = decoded(
            "\"actions\":[\"SOUND\"]" to "\"actions\":[\"SOUND\",\"HOLOGRAM\"]",
            "\"ruleMatch\":\"ANY\"" to "\"ruleMatch\":\"MOSTLY\"",
        )
        assertEquals(setOf(Action.SOUND), settings.presets.single().actions)
        assertEquals(RuleMatch.ANY, settings.presets.single().ruleMatch)
    }

    @Test
    fun `what was written reads back unchanged`() {
        val original = AppSettings(
            theme = ThemeMode.DARK,
            savedPlaces = places,
            alertSound = AlertSound.Custom("content://x", "Campana"),
            insistentSound = AlertSound.Bundled(Chime.entries.last()),
            defaultActions = setOf(Action.FULL_SCREEN),
            presets = listOf(Preset(id = "p", name = "Pan", actions = setOf(Action.SOUND_UNTIL_ANSWERED), createdAt = java.time.Instant.EPOCH)),
        )
        assertEquals(original, ReminderCodec.decodeSettings(ReminderCodec.encodeSettings(original)))
    }

    /**
     * A list in the blob read element by element (0.167.0): a preset missing a required field, a
     * place whose latitude is a word, a window whose hour will not parse — each used to throw in
     * the middle of the object and bring every setting back to its default.
     */
    @Test
    fun `a malformed preset, place or window is dropped, not the rest`() {
        val two = kept.copy(
            savedPlaces = places + SavedPlace("Oficina", 40.45, -3.69, 150),
            savedWindows = listOf(SavedWindow("Comida", LocalTime.of(14, 0), LocalTime.of(15, 0)), SavedWindow("Tarde", LocalTime.of(17, 0), LocalTime.of(20, 0))),
            presets = kept.presets + Preset(id = "p2", name = "Leche", actions = setOf(Action.VIBRATE), createdAt = java.time.Instant.EPOCH),
        )
        var blob = ReminderCodec.encodeSettings(two)
        for ((old, new) in listOf(
            "\"name\":\"Pan\"" to "\"nombre\":\"Pan\"",
            "\"label\":\"Oficina\",\"lat\":40.45" to "\"label\":\"Oficina\",\"lat\":\"north\"",
            "\"from\":\"14:00\"" to "\"from\":\"lunchtime\"",
        )) {
            assertTrue(old in blob, "the blob must carry '$old' for the swap to mean anything: $blob")
            blob = blob.replace(old, new)
        }

        val settings = ReminderCodec.decodeSettings(blob)

        assertEquals(listOf("p2"), settings.presets.map { it.id })
        assertEquals(places, settings.savedPlaces)
        assertEquals(listOf("Tarde"), settings.savedWindows.map { it.label })
        assertEquals(LocalTime.of(7, 30), settings.defaultTime, "the rest of the settings must survive")
    }

    @Test
    fun `what a read lost is what the store keeps aside`() {
        val whole = ReminderCodec.encodeSettings(kept)
        assertFalse(ReminderCodec.settingsLostOnRead(whole, ReminderCodec.decodeSettingsOrNull(whole)))
        val droppedPreset = whole.replace("\"name\":\"Pan\"", "\"nombre\":\"Pan\"")
        assertTrue(ReminderCodec.settingsLostOnRead(droppedPreset, ReminderCodec.decodeSettingsOrNull(droppedPreset)))
        assertTrue(ReminderCodec.settingsLostOnRead("{\"theme\":", null))
    }

    @Test
    fun `a blob that will not read at all says so, and the defaults stand in`() {
        assertNull(ReminderCodec.decodeSettingsOrNull("{\"theme\":"))
        assertNull(ReminderCodec.decodeSettingsOrNull("[]"))
        assertEquals(AppSettings(), ReminderCodec.decodeSettings("{\"theme\":"))
        assertEquals(kept, ReminderCodec.decodeSettingsOrNull(ReminderCodec.encodeSettings(kept)))
    }
}
