package dev.rwilco.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Snooze
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rules of the alert's press guard, with no clock and no screen: nothing fires unless a
 * hold was begun armed, kept to its end, and then let go. The countdown, the ring and the
 * tick are the device test's ([dev.rwilco.ui.AlertGuardTest]); this is the part that must
 * never be wrong whatever they do.
 */
class PressGuardTest {

    private val done = GuardedAction(icon = Icons.Filled.Check, holding = "Hecho")
    private val snooze = GuardedAction(icon = Icons.Outlined.Snooze, holding = "Posponer", done = "Pospuesto", detail = "10 min")

    @Test
    fun `a press before the screen is armed counts for nothing, however long it is kept`() {
        val guard = PressGuard()
        assertFalse(guard.begin(done))
        guard.complete(done)
        assertFalse(guard.confirmed)
        assertFalse(guard.release(done))
        assertNull(guard.holding)
        // Not even the hint: the digits up top are already saying why.
        assertFalse(guard.hinting)
    }

    @Test
    fun `a hold let go early does nothing, and says how`() {
        val guard = PressGuard().apply { arm() }
        assertTrue(guard.begin(done))
        assertSame(done, guard.holding)
        assertFalse(guard.confirmed)
        assertFalse(guard.release(done))
        assertNull(guard.holding)
        assertTrue(guard.hinting)
    }

    @Test
    fun `a hold kept to its end fires when the finger lifts, and only once`() {
        val guard = PressGuard().apply { arm() }
        assertTrue(guard.begin(snooze))
        guard.complete(snooze)
        assertTrue(guard.confirmed, "the tick is up while the finger is still down")
        assertSame(snooze, guard.holding, "and the finger is still down")
        assertTrue(guard.release(snooze))
        assertFalse(guard.hinting)
        assertNull(guard.holding)
        assertFalse(guard.confirmed)
        assertFalse(guard.release(snooze), "a second lift is nothing")
    }

    @Test
    fun `leaving the screen mid-hold forgets the finger`() {
        val guard = PressGuard().apply { arm() }
        assertTrue(guard.begin(done))
        guard.disarm()
        guard.complete(done)
        assertFalse(guard.confirmed, "a hold that outlived the screen does not finish itself off")
        assertFalse(guard.release(done))
        assertFalse(guard.hinting)
    }

    @Test
    fun `leaving the screen after the tick still fires nothing`() {
        val guard = PressGuard().apply { arm() }
        assertTrue(guard.begin(done))
        guard.complete(done)
        guard.disarm()
        assertFalse(guard.release(done), "the answer is given on release, and the release never came to this screen")
    }

    @Test
    fun `one finger at a time`() {
        val guard = PressGuard().apply { arm() }
        assertTrue(guard.begin(done))
        assertFalse(guard.begin(snooze), "a second finger on another button counts for nothing")
        assertFalse(guard.release(snooze), "and lifting it fires nothing")
        assertSame(done, guard.holding, "the first hold is untouched by the second")
        guard.complete(done)
        assertTrue(guard.release(done))
    }

    @Test
    fun `arming ends the countdown and a new hold starts clean`() {
        val guard = PressGuard()
        guard.secondsLeft = 2
        guard.hinting = true
        guard.arm()
        assertEquals(0, guard.secondsLeft)
        assertTrue(guard.armed)
        assertTrue(guard.begin(done))
        assertFalse(guard.hinting, "the hint makes way for the hold")
    }
}
