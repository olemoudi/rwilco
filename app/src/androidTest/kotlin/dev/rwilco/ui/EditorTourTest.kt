package dev.rwilco.ui

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.debug.DemoData
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

    /** The activity's resources, not the instrumentation's: the app runs under its own per-app locale. */
    private fun s(id: Int): String = rule.activity.getString(id)

    private fun text(value: String) = rule.onNodeWithText(value, useUnmergedTree = true)

    @Before
    fun seedDemoData() {
        val app = context.applicationContext as RwilcoApplication
        runBlocking { DemoData.seed(app.repository, app.clock) }
    }

    @Test
    fun theWholeFirstPhaseHoldsTogether() {
        rule.waitUntilShown(s(R.string.home_next_up))
        shot("home")

        text(s(R.string.home_new)).performClick()
        rule.waitUntilShown(s(R.string.editor_title_new))
        shot("editor-empty")

        // Saving an empty form names what is missing instead of doing nothing.
        text(s(R.string.common_save)).performClick()
        text(s(R.string.editor_error_text)).assertIsDisplayed()
        text(s(R.string.editor_error_trigger)).assertIsDisplayed()

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
            rule.waitUntilShown(s(R.string.sheet_cancel))
            shot(name)
            text(s(R.string.sheet_cancel)).performClick()
            rule.waitUntilGone(s(R.string.sheet_cancel))
            text(s(R.string.editor_add_trigger)).performScrollTo().performClick()
            rule.waitUntilShown(s(R.string.kind_date_time))
        }

        // A countdown becomes a trigger row and the error goes away.
        text(s(R.string.kind_countdown)).performClick()
        rule.waitUntilShown(s(R.string.sheet_add))
        shot("sheet-countdown")
        text(s(R.string.sheet_add)).performClick()
        rule.waitUntilGone(s(R.string.sheet_add))
        text(s(R.string.editor_error_trigger)).assertDoesNotExist()
        rule.onNodeWithContentDescription(s(R.string.editor_edit_trigger)).assertIsDisplayed()
        shot("editor-filled")

        rule.onNodeWithContentDescription(s(R.string.editor_preview)).performClick()
        rule.waitUntilShown(s(R.string.alert_done))
        shot("alert-preview")
        text(s(R.string.alert_close_preview)).performClick()
        rule.waitUntilGone(s(R.string.alert_done))

        text(s(R.string.common_save)).performClick()
        rule.waitUntilShown(reminderText)
        shot("home-after-save")

        rule.onNodeWithContentDescription(s(R.string.home_settings)).performClick()
        rule.waitUntilShown(s(R.string.settings_title))
        shot("settings")
        // Dark mode only, by the owner's rule: the light scheme shares every token and layout,
        // and each extra pass through the emulator costs minutes.
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

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilGone(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    /**
     * A full-screen capture (dialogs and sheets included), after the UI has settled. Falls back
     * to the main window's own pixels if UiAutomation refuses, which loses sheets but not the run.
     */
    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(500)
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
