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
    fun `the alerts run out, and stop the moment they do`() {
        // Three alerts: the first has gone out, so two more follow, and then nothing.
        val gaps = generateSequence(1) { it + 1 }
            .map { alerted -> alerted to nextSoundIn(alerted, rounds = 3, gapMinutes = 5) }
            .takeWhile { (_, gap) -> gap != null }
            .toList()
        assertEquals(listOf(1, 2), gaps.map { it.first })
        assertTrue(gaps.all { it.second == Duration.ofMinutes(5) })
        assertNull(nextSoundIn(alerted = 3, rounds = 3, gapMinutes = 5), "a third alert is the last of three")
        assertNull(nextSoundIn(alerted = 9, rounds = 3, gapMinutes = 5), "and past the end stays past it")
    }

    @Test
    fun `the numbers are clamped rather than trusted`() {
        // A settings blob edited by hand, or an older one read back: neither may produce a siren.
        assertNull(nextSoundIn(alerted = 1, rounds = 0, gapMinutes = 5), "at least the one alert, and nothing after it")
        assertEquals(
            Duration.ofMinutes(SoundLimits.GAP_MINUTES.first.toLong()),
            nextSoundIn(alerted = 1, rounds = 5, gapMinutes = 0),
            "no gap at all would be one long noise",
        )
        assertEquals(
            Duration.ofMinutes(SoundLimits.GAP_MINUTES.last.toLong()),
            nextSoundIn(alerted = 1, rounds = 5, gapMinutes = 9_999),
        )
        assertNull(nextSoundIn(alerted = 40, rounds = 9_999, gapMinutes = 5), "twenty is as insistent as it gets")
        assertEquals(SoundLimits.PLAYS.last, AppSettings(soundPlays = 999).tonesInARow(insistent = true))
        assertEquals(SoundLimits.PLAYS.first, AppSettings(soundPlays = 0).tonesInARow(insistent = true))
    }

    @Test
    fun `plain sound says the tone once, and the insistent one as many times in a row as it is set to`() {
        val settings = AppSettings(soundPlays = 4)
        assertEquals(1, settings.tonesInARow(insistent = false))
        assertEquals(4, settings.tonesInARow(insistent = true))
    }

    @Test
    fun `the defaults are five in a row, five minutes apart, three times`() {
        val settings = AppSettings()
        assertEquals(5, settings.soundPlays)
        assertEquals(5, settings.soundGapMinutes)
        assertEquals(3, settings.soundRounds, "the owner's number for how many times at most (2026-09-25)")
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
