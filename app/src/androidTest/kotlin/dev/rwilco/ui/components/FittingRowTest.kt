package dev.rwilco.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.rwilco.ui.theme.RwilcoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the tags land. A layout that reports less width than the minimum it was measured with
 * is stretched back up by the parent and has its contents centred in the difference — which is
 * how one tag in a card footer ended up adrift in the middle of the card instead of against the
 * left margin (0.69.0). Only a real measure pass can answer this, so it runs on the device.
 */
@RunWith(AndroidJUnit4::class)
class FittingRowTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun oneTagInAWeightedSlotSitsAgainstTheLeftEdge() {
        compose.setContent {
            RwilcoTheme {
                Row(modifier = Modifier.fillMaxWidth()) {
                    // What a card footer does: the tags take the leftover width, the glyphs the rest.
                    FittingRow(gap = 4.dp, modifier = Modifier.weight(1f), more = { Text("+") }) {
                        Text("chores", modifier = Modifier.testTag("tag"))
                    }
                    Spacer(Modifier.width(64.dp))
                }
            }
        }
        compose.onNodeWithTag("tag").assertLeftPositionInRootIsEqualTo(0.dp)
    }

    @Test
    fun withNoMinimumToMeetItStillShrinksToWhatItUsed() {
        compose.setContent {
            RwilcoTheme {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    // What a compact card does: hung off the end, it must sit against the right edge.
                    FittingRow(gap = 4.dp, modifier = Modifier.testTag("row"), more = { Text("+") }) {
                        Text("chores")
                    }
                }
            }
        }
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag("row").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("row").assertLeftPositionInRootIsEqualTo(screen.right - (row.right - row.left))
    }
}
