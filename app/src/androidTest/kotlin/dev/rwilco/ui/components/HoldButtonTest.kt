package dev.rwilco.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.ui.theme.RwilcoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The guard on the one action that must never happen by accident. Only a device can run a real
 * gesture against a real animation, and only a hand-driven clock can say "let go after one
 * second" and mean it.
 */
@RunWith(AndroidJUnit4::class)
class HoldButtonTest {

    @get:Rule
    val rule = createComposeRule()

    private var fired = 0

    private fun setUp() {
        rule.setContent {
            RwilcoTheme(haptics = false) {
                // Room around it: the ring reaches past the button on purpose, and a capture
                // cropped to the button's own bounds would cut off the thing being tested.
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Box(Modifier.padding(24.dp)) {
                        HoldButton(icon = Icons.Outlined.Pause, label = LABEL, onHoldComplete = { fired++ })
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = false
    }

    private fun button() = rule.onNodeWithContentDescription(LABEL)

    @Test
    fun aPressLetGoBeforeTwoSecondsDoesNothing() {
        setUp()
        button().performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1_500)
        button().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals("a press let go at 1.5s fired", 0, fired)
    }

    @Test
    fun aPressHeldThroughFiresOnceAndOnlyOnce() {
        setUp()
        button().performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(2_100)
        assertEquals(1, fired)
        // Still down a second later: holding on does not fire it again.
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(1, fired)
        button().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(1, fired)
    }

    @Test
    fun twoBriefPressesInARowDoNotAddUp() {
        setUp()
        repeat(3) {
            button().performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(1_200)
            button().performTouchInput { up() }
            rule.mainClock.advanceTimeBy(300)
        }
        assertEquals("three brushes of the thumb paused it", 0, fired)
    }

    @Test
    fun aQuickTapDoesNothingAndAScreenReaderGoesStraightThrough() {
        setUp()
        rule.mainClock.autoAdvance = true
        // An ordinary tap — a real down and up, the way a thumb brushes past.
        button().performClick()
        rule.waitForIdle()
        assertEquals("a quick tap paused it", 0, fired)

        // The accessibility action, which is what TalkBack's double tap performs: already a
        // deliberate act, and there is no thumb to slip.
        button().performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(1, fired)
    }

    /** A picture of the ring half filled, for the README and for a human to judge the radius. */
    @Test
    fun theRingIsOnScreenWhileTheFingerIsDown() {
        setUp()
        button().performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1_000)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        File(dir, "hold-ring.png").outputStream().use { out ->
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        button().performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)
        assertEquals(0, fired)
    }

    private companion object {
        const val LABEL = "Pausar"
    }
}
