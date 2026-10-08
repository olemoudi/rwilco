package dev.rwilco.alarm

import dev.rwilco.model.Action
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * What a phone that has just booted, and has not been unlocked, does with the moments it mirrored:
 * the ones ahead are armed, one the restart itself sat through is said at once, and anything older
 * is left to the catch-up after the unlock.
 */
class LockedPlanTest {

    private val now = Instant.parse("2026-10-08T05:00:00Z")
    private fun wake(id: String, minutes: Long, loud: Boolean = true) = LockedWake(id, now.plus(Duration.ofMinutes(minutes)).toEpochMilli(), loud = loud)

    @Test
    fun `moments ahead are armed`() {
        val plan = lockedPlan(LockedMirrorState(wakes = listOf(wake("pills", 120), wake("bins", 600))), now)
        assertEquals(listOf("pills", "bins"), plan.arm.map { it.id })
        assertTrue(plan.announceNow.isEmpty())
    }

    @Test
    fun `a moment the restart sat through is said at once, and one long gone is not`() {
        val plan = lockedPlan(LockedMirrorState(wakes = listOf(wake("restart", -3), wake("night", -240))), now)
        assertEquals(listOf("restart"), plan.announceNow.map { it.id })
        assertTrue(plan.arm.isEmpty(), "and neither is armed")
    }

    @Test
    fun `a quiet reminder makes no generic noise, and nothing is said twice`() {
        val said = wake("pills", -3)
        val plan = lockedPlan(LockedMirrorState(wakes = listOf(said, wake("note", 60, loud = false)), announced = listOf(said)), now)
        assertTrue(plan.arm.isEmpty())
        assertTrue(plan.announceNow.isEmpty())
    }

    /**
     * The locked channel always plays the alarm tone, so only a reminder that makes a sound when
     * the phone is unlocked is given one before. A full screen with no sound is silent unlocked,
     * and was the alarm tone at alarm volume locked.
     */
    @Test
    fun `only a reminder that sounds is said aloud before the first unlock`() {
        assertTrue(soundsWhileLocked(setOf(Action.NOTIFICATION, Action.SOUND)))
        assertTrue(soundsWhileLocked(setOf(Action.SOUND_UNTIL_ANSWERED)))
        assertFalse(soundsWhileLocked(setOf(Action.FULL_SCREEN)), "a silent takeover stays silent")
        assertFalse(soundsWhileLocked(setOf(Action.NOTIFICATION, Action.VIBRATE)))
        assertFalse(soundsWhileLocked(emptySet()))
    }
}
