package dev.rwilco.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.model.Condition
import dev.rwilco.model.RuleStanding
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import dev.rwilco.model.family
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.home.TriggerRow
import dev.rwilco.ui.home.TriggerRowUi
import dev.rwilco.ui.theme.RwilcoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * The five marks, side by side and life-size, on the dark scheme.
 *
 * A mark three millimetres across cannot be judged from the code or from a screenshot of a
 * screen it happens to appear on: it has to be looked at. This renders one row per standing so
 * that looking at it takes one command.
 */
@RunWith(AndroidJUnit4::class)
class StandingMarkTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val place = Trigger.Location(40.4169, -3.7035, 150, Transition.ENTER, "Casa")
    private val clock = Trigger.AtTime(LocalTime.of(20, 30), java.time.DayOfWeek.entries.toSet())

    @Test
    fun everyMarkIsLegibleInTheDark() {
        rule.setContent {
            RwilcoTheme(darkTheme = true) {
                Surface {
                    Column(androidx.compose.ui.Modifier.padding(16.dp)) {
                        RwilcoCard {
                            Column(androidx.compose.ui.Modifier.padding(16.dp)) {
                                for (standing in RuleStanding.entries) {
                                    TriggerRow(
                                        row = TriggerRowUi(
                                            trigger = if (standing == RuleStanding.UNKNOWN) place else clock,
                                            conditions = if (standing == RuleStanding.HOLDING) {
                                                listOf(Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0)))
                                            } else {
                                                emptyList()
                                            },
                                            family = (if (standing == RuleStanding.UNKNOWN) place else clock).family,
                                            nextAt = null,
                                            window = null,
                                            standing = standing,
                                        ),
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
        rule.waitForIdle()
        val bitmap: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        File(dir, "standing-marks.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
