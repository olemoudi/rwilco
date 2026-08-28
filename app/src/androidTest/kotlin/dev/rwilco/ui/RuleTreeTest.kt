package dev.rwilco.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.model.Presence
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.RuleStanding
import dev.rwilco.model.Trigger
import dev.rwilco.model.family
import dev.rwilco.ui.components.RuleTree
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.home.TriggerRow
import dev.rwilco.ui.home.TriggerRowUi
import dev.rwilco.ui.theme.RwilcoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * The three readings of a set of rules, one under the other, on the dark scheme.
 *
 * The same reason the standing marks have a test of their own: a dashed trunk and a 16dp glyph
 * are the whole difference between "either of these" and "both of these, at once", and that is
 * not a thing anybody can judge from the code. This renders one tree per reading, with the same
 * two rules under each, so the difference is the only thing on the screen.
 */
@RunWith(AndroidJUnit4::class)
class RuleTreeTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val place = Trigger.Location(40.4169, -3.7035, 150, Presence.INSIDE, "Casa")
    private val clock = Trigger.AtTime(LocalTime.of(20, 30), DayOfWeek.entries.toSet())

    private fun row(trigger: Trigger, standing: RuleStanding) = TriggerRowUi(
        trigger = trigger,
        conditions = emptyList(),
        family = trigger.family,
        nextAt = null,
        window = null,
        standing = standing,
    )

    @Test
    fun theThreeReadingsAreTellableApart() {
        rule.setContent {
            RwilcoTheme(darkTheme = true) {
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        for (match in RuleMatch.entries) {
                            RwilcoCard(modifier = Modifier.padding(bottom = 12.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    RuleTree(match = match, count = 2) { index ->
                                        // Under "cualquiera" a rule has no standing at all; the
                                        // other two mark it, which is part of what the tree is
                                        // for and belongs in the picture.
                                        val standing = when (match) {
                                            RuleMatch.ANY -> null
                                            RuleMatch.ALL -> if (index == 0) RuleStanding.DONE else RuleStanding.PENDING
                                            RuleMatch.TOGETHER -> if (index == 0) RuleStanding.HOLDING else RuleStanding.NOT_HOLDING
                                        }
                                        TriggerRow(
                                            row = row(if (index == 0) place else clock, standing ?: RuleStanding.PENDING)
                                                .copy(standing = standing),
                                            today = LocalDate.of(2026, 8, 26),
                                            defaultTime = LocalTime.of(9, 0),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val bitmap: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        File(dir, "rule-trees.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
