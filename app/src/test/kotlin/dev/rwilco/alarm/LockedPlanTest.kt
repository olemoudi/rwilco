package dev.rwilco.alarm

import org.junit.jupiter.api.Assertions.assertEquals
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
}
