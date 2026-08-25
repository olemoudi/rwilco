package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The sound: which one, and the round of plays that keeps asking until somebody answers.
 */
class AlertSoundTest {

    @Test
    fun `asking for a sound once and asking for it until answered are one choice`() {
        val plain = firingPlan(setOf(Action.SOUND))
        assertTrue(plain.sound)
        assertFalse(plain.insistent)

        val insistent = firingPlan(setOf(Action.SOUND_UNTIL_ANSWERED))
        assertTrue(insistent.sound, "the insistent one is still a sound")
        assertTrue(insistent.insistent)

        // Nothing that does not ask for a sound gets one.
        assertFalse(firingPlan(setOf(Action.NOTIFICATION, Action.VIBRATE)).sound)
        assertEquals(SOUND_ACTIONS, setOf(Action.SOUND, Action.SOUND_UNTIL_ANSWERED))
    }

    @Test
    fun `a round runs out, and stops the moment it does`() {
        // Five plays: the first has gone out, so four more follow, and then nothing.
        val gaps = generateSequence(1) { it + 1 }
            .map { played -> played to nextSoundIn(played, plays = 5, gapMinutes = 5) }
            .takeWhile { (_, gap) -> gap != null }
            .toList()
        assertEquals(listOf(1, 2, 3, 4), gaps.map { it.first })
        assertTrue(gaps.all { it.second == Duration.ofMinutes(5) })
        assertNull(nextSoundIn(played = 5, plays = 5, gapMinutes = 5), "a fifth play is the last of five")
        assertNull(nextSoundIn(played = 9, plays = 5, gapMinutes = 5), "and past the end stays past it")
    }

    @Test
    fun `the numbers are clamped rather than trusted`() {
        // A settings blob edited by hand, or an older one read back: neither may produce a siren.
        assertNull(nextSoundIn(played = 2, plays = 1, gapMinutes = 5), "one play is not a round")
        assertEquals(
            Duration.ofMinutes(SoundLimits.GAP_MINUTES.first.toLong()),
            nextSoundIn(played = 1, plays = 5, gapMinutes = 0),
            "no gap at all would be one long noise",
        )
        assertEquals(
            Duration.ofMinutes(SoundLimits.GAP_MINUTES.last.toLong()),
            nextSoundIn(played = 1, plays = 5, gapMinutes = 9_999),
        )
        assertNull(nextSoundIn(played = 40, plays = 9_999, gapMinutes = 5), "twenty is as insistent as it gets")
    }

    @Test
    fun `the defaults are the five and five that were asked for`() {
        val settings = AppSettings()
        assertEquals(5, settings.soundPlays)
        assertEquals(5, settings.soundGapMinutes)
        // The phone's own tone until somebody chooses otherwise: the chimes are subtler than an
        // alarm tone, and an alarm nobody recognises is an alarm somebody sleeps through.
        assertEquals(AlertSound.System, settings.alertSound)
    }

    @Test
    fun `every sound has its own key, because a channel cannot change its tone`() {
        val keys = Chime.entries.map { AlertSound.Bundled(it).key } +
            AlertSound.System.key +
            AlertSound.Custom("content://x/1", "Timbre").key
        assertEquals(keys.size, keys.distinct().size, "two sounds share a channel id: $keys")
        // Stable, because the id is what tells one channel from another between launches.
        assertEquals(AlertSound.Bundled(Chime.LOW).key, AlertSound.Bundled(Chime.LOW).key)
        // And the label is not part of it: renaming a file must not orphan its channel.
        assertEquals(
            AlertSound.Custom("content://x/1", "Timbre").key,
            AlertSound.Custom("content://x/1", "Otro nombre").key,
        )
        assertNotEquals(
            AlertSound.Custom("content://x/1", "Timbre").key,
            AlertSound.Custom("content://x/2", "Timbre").key,
        )
    }

    @Test
    fun `a sound survives a round trip, custom one included`() {
        for (sound in listOf(AlertSound.System, AlertSound.Bundled(Chime.SOFT), AlertSound.Custom("content://x/1", "Timbre"))) {
            val settings = AppSettings(alertSound = sound)
            assertEquals(settings, ReminderCodec.decodeSettings(ReminderCodec.encodeSettings(settings)))
        }
    }
}
