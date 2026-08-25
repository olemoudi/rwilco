package dev.rwilco.ui

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.debug.DemoData
import dev.rwilco.ui.components.TIME_FIELD_TAG
import dev.rwilco.ui.editor.EDITOR_TEXT_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Walks the whole first-phase UI the way a thumb would — create, configure every trigger kind,
 * preview, save, visit Settings and Done, delete — asserting the flow at each step, and leaves a
 * full-screen capture of every screen in the app's external files dir
 * (`adb exec-out run-as dev.rwilco tar -cf - files/screenshots | tar -x`). The README's screenshots come
 * from here, so they can never drift from what the app does.
 */
@RunWith(AndroidJUnit4::class)
class EditorTourTest {

    companion object {
        /** The owner's language, and the one the README shows; set before the activity exists. */
        @JvmStatic
        @BeforeClass
        fun useSpanish() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("es-ES")
        }
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val reminderText = "Comprar filtros para la cafetera"

    /** The demo reminder that rings by a recurrence and nothing else; see DemoData. */
    private val routineText = "Tomar el antibiótico"

    /** The activity's resources, not the instrumentation's: the app runs under its own per-app locale. */
    private fun s(id: Int): String = rule.activity.getString(id)

    private fun text(value: String) = rule.onNodeWithText(value, useUnmergedTree = true)

    @Before
    fun seedDemoData() {
        val app = context.applicationContext as RwilcoApplication
        runBlocking {
            DemoData.seed(app.repository, app.clock)
            // A build the phone has not seen opens "What's new" over the first screen — and
            // over every capture after it, since the tour never taps it away. Seen already.
            // No presets either: with one kept, "New" asks a question first, and the tour is
            // walking the path somebody with none walks.
            app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, presets = emptyList()) }
            // A watch that has been running: the location log has nothing to show on a phone
            // that has never looked, and an empty state is not what that screen is for.
            app.placeLog.clear()
            for (note in DemoData.watchNotes(app.clock)) app.placeLog.note(note)
        }
        // Yesterday's captures would otherwise be pulled along with today's and quietly go stale.
        File(context.filesDir, "screenshots").listFiles()?.forEach { it.delete() }
    }

    @Test
    fun theWholeFirstPhaseHoldsTogether() {
        rule.waitUntilShown(s(R.string.home_next_up))
        shot("home")

        text(s(R.string.home_new)).performClick()
        rule.waitUntilShown(s(R.string.editor_title_new))
        shot("editor-empty")

        // Saving an empty form names what is missing instead of doing nothing — which is only
        // the words now: a reminder with neither trigger nor action is a note, and saves.
        text(s(R.string.common_save)).performClick()
        text(s(R.string.editor_error_text)).assertIsDisplayed()

        // Nothing is auto-focused any more: the button is the way to the keyboard, and what has
        // been written before is offered under it.
        text(s(R.string.editor_write)).performClick()
        rule.onNodeWithTag(EDITOR_TEXT_TAG).performTextInput(reminderText)
        text(s(R.string.editor_add_trigger)).performScrollTo().performClick()
        rule.waitUntilShown(s(R.string.kind_date_time))
        shot("editor-kinds")

        // Every configurator opens and cancels cleanly.
        for ((kind, name) in listOf(
            R.string.kind_date_time to "sheet-datetime",
            R.string.kind_repeat_time to "sheet-repeat",
            R.string.kind_random to "sheet-random",
            R.string.kind_place to "sheet-place",
        )) {
            text(s(kind)).performClick()
            rule.waitUntilDisplayed(s(R.string.sheet_cancel))
            // The place sheet is fetching map tiles over the emulator's slow network.
            if (kind == R.string.kind_place) Thread.sleep(6_000)
            shot(name)
            if (kind == R.string.kind_date_time) {
                // The wheels, which replaced a dial nobody could hit one-handed.
                rule.onAllNodesWithTag(TIME_FIELD_TAG, useUnmergedTree = true)[0].performClick()
                rule.waitUntilShown(s(R.string.sheet_done))
                shot("time-wheels")
                text(s(R.string.sheet_done)).performClick()
                rule.waitUntilGone(s(R.string.sheet_done))
            }
            text(s(R.string.sheet_cancel)).performClick()
            rule.waitUntilGone(s(R.string.sheet_cancel))
            text(s(R.string.editor_add_trigger)).performScrollTo().performClick()
            rule.waitUntilShown(s(R.string.kind_date_time))
        }

        // A countdown becomes a trigger row and the error goes away.
        text(s(R.string.kind_countdown)).performClick()
        rule.waitUntilDisplayed(s(R.string.sheet_add))
        shot("sheet-countdown")
        text(s(R.string.sheet_add)).performClick()
        rule.waitUntilGone(s(R.string.sheet_add))
        rule.onNodeWithContentDescription(s(R.string.editor_edit_trigger)).assertIsDisplayed()

        // A rule can be fenced in: the trigger only counts inside these hours.
        text(s(R.string.editor_add_condition)).performScrollTo().performClick()
        rule.waitUntilDisplayed(s(R.string.condition_title))
        shot("sheet-condition")
        text(s(R.string.sheet_add)).performClick()
        rule.waitUntilGone(s(R.string.condition_title))
        // A tag on, so the capture shows what "on" looks like next to "off".
        text("casa").performScrollTo().performClick()
        shot("editor-filled")

        // The bottom of the form: the four action tiles, some on and some off.
        text(s(R.string.action_full_screen)).performScrollTo()
        shot("editor-what")

        rule.onNodeWithContentDescription(s(R.string.editor_preview)).performClick()
        rule.waitUntilShown(s(R.string.alert_done))
        shot("alert-preview")
        text(s(R.string.alert_close_preview)).performClick()
        rule.waitUntilGone(s(R.string.alert_done))

        text(s(R.string.common_save)).performClick()
        rule.waitUntilShown(reminderText)
        shot("home-after-save")

        // The shape with no trigger at all: a routine counted from the moment it was last dealt
        // with. Its card had nothing to say about when it rings until the recurrence got a row.
        //
        // Scrolled by the list rather than by the node: a lazy list has not composed a card
        // this far down, so performScrollTo has nothing to scroll to. Home has two lazy
        // containers and the list is the outer one — the tag row is an item inside it.
        rule.onAllNodes(hasScrollToIndexAction())[0].performScrollToNode(hasText(routineText))
        rule.waitUntilDisplayed(routineText)
        shot("home-recurrence")
        // Back to the top, or the header the next step reaches for is off the screen.
        rule.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(0)
        rule.waitUntilDisplayed(s(R.string.home_next_up))

        rule.onNodeWithContentDescription(s(R.string.home_settings)).performClick()
        rule.waitUntilShown(s(R.string.settings_title))
        shot("settings")
        // Dark mode only, by the owner's rule: the light scheme shares every token and layout,
        // and each extra pass through the emulator costs minutes.
        text(s(R.string.watch_log_open)).performScrollTo()
        shot("settings-location")
        text(s(R.string.watch_log_open)).performClick()
        rule.waitUntilShown(s(R.string.watch_log_title))
        shot("watch-log")
        rule.onNodeWithContentDescription(s(R.string.common_back)).performClick()
        rule.waitUntilShown(s(R.string.settings_title))
        rule.onNodeWithContentDescription(s(R.string.common_back)).performClick()
        rule.waitUntilShown(s(R.string.home_next_up))

        rule.onNodeWithContentDescription(s(R.string.home_done_list)).performClick()
        rule.waitUntilShown(s(R.string.done_title))
        shot("done")
        rule.onNodeWithContentDescription(s(R.string.common_back)).performClick()
        rule.waitUntilShown(s(R.string.home_next_up))

        // Editing the saved reminder and deleting it leaves the list as it was.
        text(reminderText).performScrollTo().performClick()
        rule.waitUntilShown(s(R.string.editor_title_edit))
        rule.onNodeWithContentDescription(s(R.string.editor_delete)).performClick()
        rule.waitUntilGone(reminderText)
    }

    // Case-insensitive: labels such as "Lo siguiente" are drawn in capitals.
    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * In the tree AND on screen. A bottom sheet composes its content before it has slid up,
     * so [waitUntilShown] returns while it is still below the fold — and the capture that
     * follows is of the screen behind it.
     */
    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilDisplayed(text: String) {
        waitUntil(timeoutMillis = 10_000) {
            runCatching { onAllNodesWithText(text, ignoreCase = true, useUnmergedTree = true)[0].isDisplayed() }.getOrDefault(false)
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilGone(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    /**
     * A full-screen capture (dialogs and sheets included), after the UI has settled. Falls back
     * to the main window's own pixels if UiAutomation refuses, which loses sheets but not the run.
     */
    private fun shot(name: String) {
        rule.waitForIdle()
        // Idle is about composition; a sheet's own window can still be a frame or two from
        // painted on the software-rendered emulator, and the random sheet was reliably captured
        // as the editor behind it at half this.
        Thread.sleep(1_500)
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
