package dev.rwilco.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.SavedPlace
import dev.rwilco.ui.editor.EDITOR_TEXT_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * "Y sólo si voy en coche", from the sheet that draws the circle.
 *
 * The fence was reachable only from "y sólo si" — a grey text button under a trigger that had to
 * exist first — so it went unused, and the one it was built for (a garage door that must not count
 * a walk as the car moving) is written in the place sheet, three taps earlier. This walks the
 * thumb's own path: draw the circle, ask for the speed beside it, and find it afterwards as a
 * condition on the rule, which is where it is edited from either screen.
 *
 * Its own class rather than another step of `EditorTourTest`: this is one flow, it needs no
 * screenshots, and the tour is a long walk that has to pass in full to say anything.
 */
@RunWith(AndroidJUnit4::class)
class PlaceSpeedTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** One capture, so the row can be looked at rather than only asserted. See EditorTourTest. */
    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_500)
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun s(id: Int): String = rule.activity.getString(id)

    private fun text(value: String) = rule.onNodeWithText(value, useUnmergedTree = true)

    private fun waitFor(value: String) = rule.waitUntil(timeoutMillis = 15_000) {
        runCatching { rule.onAllNodesWithText(value, useUnmergedTree = true)[0].isDisplayed() }.getOrDefault(false)
    }

    /** A saved place, so the pin comes off a chip and the map is not what this test is about. */
    @Before
    fun aPlaceWorthNaming() {
        val app = context.applicationContext as RwilcoApplication
        runBlocking {
            app.repository.deleteAll()
            app.settingsStore.update {
                it.copy(
                    lastSeenVersionCode = BuildConfig.VERSION_CODE,
                    disclaimerRead = true,
                    presets = emptyList(),
                    savedPlaces = listOf(SavedPlace("El garaje", 40.4169, -3.7035, 150)),
                )
            }
        }
    }

    @Test
    fun theSpeedIsAskedBesideTheLineAndLandsOnTheRule() {
        waitFor(s(R.string.home_new))
        text(s(R.string.home_new)).performClick()
        waitFor(s(R.string.editor_title_new))
        rule.onNodeWithTag(EDITOR_TEXT_TAG).performTextInput("Mover el coche")

        text(s(R.string.editor_add_trigger)).performScrollTo().performClick()
        waitFor(s(R.string.kind_date))
        text(s(R.string.kind_place)).performClick()
        waitFor(s(R.string.sheet_cancel))
        // The place sheet fetches map tiles over the emulator's slow network; the tour waits the
        // same way. Nothing here is about the map — the pin comes off the saved chip.
        Thread.sleep(6_000)
        text("El garaje").performScrollTo().performClick()
        rule.waitForIdle()

        // Closed to begin with: a speed is the rare answer, and the common one costs no taps.
        rule.onAllNodesWithText(s(R.string.condition_moving_driving), useUnmergedTree = true).assertCountEquals(0)
        // The switch is driven through its own semantics rather than by a tap at its centre: the
        // sheet's action bar floats over the bottom of the scroll, so a control scrolled just
        // into view can be under it, and a tap that lands on "Cancelar" is a test about the wrong
        // thing. The 48dp the row is worth is a design question, and the chip row above answers it.
        rule.onNode(hasText(s(R.string.place_speed_label)) and isToggleable())
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        // It opens on "en coche", which is the answer somebody reaching for this is after — the
        // same opening the "y sólo si" sheet has. Two floors on one axis, so the line under them
        // says which was picked: "en movimiento" is cleared by a car too, and two words cannot
        // say that on their own.
        text(s(R.string.condition_moving_means_driving)).performScrollTo().assertIsDisplayed()
        rule.onNode(hasText(s(R.string.condition_moving_walking)) and isSelectable())
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        text(s(R.string.condition_moving_means_walking)).performScrollTo().assertIsDisplayed()
        rule.onNode(hasText(s(R.string.condition_moving_driving)) and isSelectable())
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        text(s(R.string.condition_moving_means_driving)).performScrollTo().assertIsDisplayed()
        shot("sheet-place-speed")

        text(s(R.string.sheet_add)).performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText(s(R.string.sheet_cancel), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }

        // And there it is on the rule: the same fence "y sólo si" sets, written three taps
        // earlier and edited from either screen after.
        text(s(R.string.condition_moving_driving)).performScrollTo().assertIsDisplayed()
        // The way to a second one is a chip now, not a bare label: the control that nobody knew
        // was a control, and the one that missed the 48dp floor.
        // On the merged tree on purpose: the chip carries the click and the label carries the
        // words, and "is this thing tappable" is a question about the chip.
        rule.onNodeWithText(s(R.string.editor_add_another_condition)).performScrollTo().assertHasClickAction()
        shot("editor-speed-fence")
    }
}
