package dev.rwilco.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rwilco.ui.theme.RwilcoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A chip that does two things must still do the first one.
 *
 * These chips carry a tap (use this text, turn this tag on) and a hold (mend the list it came
 * from). Two gesture handlers on one target is where a tap goes to die, and a chip that does
 * nothing at all is worse than one that never offered the second thing.
 */
@RunWith(AndroidJUnit4::class)
class ChipHoldTest {

    @get:Rule
    val rule = createComposeRule()

    private var tapped = 0
    private var held = 0

    private fun setUp(preset: Boolean) {
        rule.setContent {
            RwilcoTheme(haptics = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (preset) {
                        PresetChip(label = CHIP, onClick = { tapped++ }, onHold = { held++ })
                    } else {
                        TagChip(label = CHIP, selected = false, onClick = { tapped++ }, onHold = { held++ })
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = false
    }

    private fun chip() = rule.onNodeWithText(CHIP)

    @Test
    fun tappingAReuseChipUsesIt() {
        setUp(preset = true)
        chip().performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(80)
        chip().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)
        assertEquals("a tap on a reuse chip did nothing", 1, tapped)
        assertEquals(0, held)
    }

    @Test
    fun tappingATagChipTogglesIt() {
        setUp(preset = false)
        chip().performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(80)
        chip().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)
        assertEquals("a tap on a tag did nothing", 1, tapped)
        assertEquals(0, held)
    }

    @Test
    fun holdingItDoesTheSecondThingAndNotTheFirst() {
        setUp(preset = true)
        chip().performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(HOLD_MILLIS.toLong() + 200)
        chip().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)
        assertEquals("holding did not mend the list", 1, held)
        assertEquals("holding also used the chip", 0, tapped)
    }

    private companion object {
        const val CHIP = "Sacar la basura"
    }
}
