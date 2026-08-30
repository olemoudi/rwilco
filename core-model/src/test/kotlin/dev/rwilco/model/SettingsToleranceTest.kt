package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `an unknown action is dropped, not the set`() {
        val settings = decoded("\"defaultActions\":[\"NOTIFICATION\",\"VIBRATE\"]" to "\"defaultActions\":[\"NOTIFICATION\",\"HOLOGRAM\",\"VIBRATE\"]")
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
}
