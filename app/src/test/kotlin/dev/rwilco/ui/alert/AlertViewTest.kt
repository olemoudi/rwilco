package dev.rwilco.ui.alert

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * "Ver", and the lock screen in the way of it.
 *
 * The step is a function so it can be answered here: what cannot be tested on the emulator is the
 * keyguard itself — the test image has none, and `requestDismissKeyguard` on an unlocked phone
 * answers that it succeeded before the call returns, so a device test would only ever walk the
 * one row that already worked.
 */
class AlertViewTest {

    @Test
    fun `an unlocked phone opens the app straight away`() {
        assertEquals(ViewStep.OPEN, viewStep(locked = false))
    }

    @Test
    fun `a locked phone asks for the unlock first`() {
        assertEquals(ViewStep.ASK_TO_UNLOCK, viewStep(locked = true))
    }

    @Test
    fun `the app opens once the unlock went through`() {
        assertEquals(ViewStep.OPEN, viewStep(locked = true, unlock = UnlockAnswer.SUCCEEDED))
    }

    @Test
    fun `an unlock refused or failed leaves the alert where it was`() {
        // And that is the whole fix: the reminder is only let go inside the step that opens.
        assertEquals(ViewStep.STAY, viewStep(locked = true, unlock = UnlockAnswer.CANCELLED))
        assertEquals(ViewStep.STAY, viewStep(locked = true, unlock = UnlockAnswer.ERROR))
    }
}
