package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which of the two tones a firing plays.
 *
 * The split is the action somebody ticked — "sonido" says it once, "hasta que reciba caso" comes
 * back every few minutes — and not the way the noise happens to come out.
 */
class SoundForTest {

    private val once = firingPlan(setOf(Action.SOUND))
    private val insisting = firingPlan(setOf(Action.SOUND_UNTIL_ANSWERED))
    private val chime = AlertSound.Bundled(Chime.LOW)
    private val other = AlertSound.Bundled(Chime.ALERT)

    @Test
    fun `with no second tone chosen, both play the one sound`() {
        val settings = AppSettings(alertSound = chime)
        assertEquals(chime, settings.soundFor(once))
        assertEquals(chime, settings.soundFor(insisting))
    }

    @Test
    fun `a second tone is only ever heard by the reminders that keep asking`() {
        val settings = AppSettings(alertSound = chime, insistentSound = other)
        assertEquals(chime, settings.soundFor(once))
        assertEquals(other, settings.soundFor(insisting))
    }

    @Test
    fun `a firing with no sound at all still answers, because the channel is named either way`() {
        // AlertNotifications names its channel after the tone whether or not it will be heard;
        // asking for one must never be a decision this can refuse to make.
        val settings = AppSettings(alertSound = chime, insistentSound = other)
        assertEquals(chime, settings.soundFor(firingPlan(setOf(Action.NOTIFICATION))))
        assertEquals(chime, settings.soundFor(firingPlan(emptySet())))
    }

    @Test
    fun `insisting and saying it once at the same time is still insisting`() {
        // Both ticked is one reminder that keeps asking, so it gets the tone chosen for that.
        val both = firingPlan(setOf(Action.SOUND, Action.SOUND_UNTIL_ANSWERED))
        assertEquals(other, AppSettings(alertSound = chime, insistentSound = other).soundFor(both))
    }
}
