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
import dev.rwilco.model.SavedPlace
import dev.rwilco.debug.DemoData
import dev.rwilco.ui.components.TIME_FIELD_TAG
import dev.rwilco.ui.editor.EDITOR_TEXT_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import androidx.test.rule.GrantPermissionRule
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

    /**
     * Handed over rather than asked for: the app asks for notifications on its first resume, and
     * a system dialog over the screen is a tap that lands nowhere and a screenshot of the
     * permission controller.
     */
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
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
            // Two saved places, because a place condition ("y sólo si estoy en casa") picks from
            // them: with none saved the condition sheet has only its hours to offer.
            app.settingsStore.update {
                it.copy(
                    lastSeenVersionCode = BuildConfig.VERSION_CODE,
                    presets = emptyList(),
                    savedPlaces = listOf(
                        SavedPlace("Casa", 40.4169, -3.7035, 200),
                        SavedPlace("Oficina", 40.4500, -3.6900, 150),
                    ),
                )
            }
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
        rule.waitUntilShown(s(R.string.kind_date))
        shot("editor-kinds")

        // Every configurator opens and cancels cleanly.
        for ((kind, name) in listOf(
            R.string.kind_date to "sheet-date",
            R.string.kind_interval to "sheet-interval",
            R.string.kind_random to "sheet-random",
            R.string.kind_place to "sheet-place",
        )) {
            text(s(kind)).performClick()
            rule.waitUntilDisplayed(s(R.string.sheet_cancel))
            // The place sheet is fetching map tiles over the emulator's slow network.
            if (kind == R.string.kind_place) Thread.sleep(6_000)
            shot(name)
            // The four readings of a circle out of two controls: the switch relabels the
            // segments, so what is on screen is always one of the four things people say.
            if (kind == R.string.kind_place) {
                text(s(R.string.place_side_inside)).performScrollTo().assertIsDisplayed()
                text(s(R.string.place_needs_crossing)).performScrollTo().performClick()
                rule.waitUntilDisplayed(s(R.string.place_side_arriving))
                shot("sheet-place-crossing")
                text(s(R.string.place_side_leaving)).performScrollTo().performClick()
                rule.waitUntilDisplayed(s(R.string.place_means_leaving))
            }
            if (kind == R.string.kind_date) {
                // The wheels, which replaced a dial nobody could hit one-handed.
                rule.onAllNodesWithTag(TIME_FIELD_TAG, useUnmergedTree = true)[0].performClick()
                rule.waitUntilShown(s(R.string.sheet_done))
                shot("time-wheels")
                text(s(R.string.sheet_done)).performClick()
                rule.waitUntilGone(s(R.string.sheet_done))
            }
            // The date tile now carries the other answer to "when in the day", and the hint
            // under it is the day's own waking hours — the thing the settings are for.
            if (kind == R.string.kind_date) {
                text(s(R.string.sheet_random_in_day)).performScrollTo().performClick()
                rule.waitUntilDisplayed(s(R.string.sheet_at_this_time))
                shot("sheet-date-random")
            }
            text(s(R.string.sheet_cancel)).performClick()
            rule.waitUntilGone(s(R.string.sheet_cancel))
            text(s(R.string.editor_add_trigger)).performScrollTo().performClick()
            rule.waitUntilShown(s(R.string.kind_date))
        }

        // A countdown becomes a trigger row and the error goes away.
        text(s(R.string.kind_countdown)).performClick()
        rule.waitUntilDisplayed(s(R.string.sheet_add))
        shot("sheet-countdown")
        text(s(R.string.sheet_add)).performClick()
        rule.waitUntilGone(s(R.string.sheet_add))
        rule.onNodeWithContentDescription(s(R.string.editor_edit_trigger)).assertIsDisplayed()

        // "Vuelve" asks the whole question: the span on the buttons, and which moment it is
        // counted from underneath. Choosing an anchor must not clear the span, which is the
        // one way these two halves could quietly fight each other.
        text(s(R.string.recur_week)).performScrollTo().performClick()
        rule.waitForIdle()
        // The anchor row appears under the buttons, which puts it below the fold on a phone.
        text(s(R.string.recur_counts_from)).performScrollTo()
        rule.waitUntilDisplayed(s(R.string.recur_counts_from))
        shot("editor-recurrence")
        text(s(R.string.recur_from_ringing)).performScrollTo().performClick()
        rule.waitForIdle()
        text(s(R.string.recur_week)).performScrollTo().assertIsDisplayed()
        shot("editor-recurrence-ringing")
        text(s(R.string.recur_none)).performScrollTo().performClick()
        rule.waitUntilGone(s(R.string.recur_counts_from))

        // The other half of "Vuelve", and the reason there is no repeating-time tile any more:
        // a calendar is four shapes, not one. Months ask which day of the month, and so do
        // years — "el primer miércoles de mayo" is a yearly, and saying it any other way is
        // arithmetic on a date that moves.
        text(s(R.string.recur_calendar)).performScrollTo().performClick()
        rule.waitUntilDisplayed(s(R.string.sheet_cancel))
        shot("sheet-calendar")
        text(s(R.string.sheet_repeat_unit_months)).performScrollTo().performClick()
        rule.waitUntilDisplayed(s(R.string.sheet_repeat_monthly_nth))
        shot("sheet-calendar-monthly")
        text(s(R.string.sheet_repeat_monthly_nth)).performScrollTo().performClick()
        rule.waitUntilDisplayed(s(R.string.repeat_ordinal_last))
        text(s(R.string.sheet_repeat_unit_years)).performScrollTo().performClick()
        rule.waitUntilDisplayed(s(R.string.sheet_repeat_monthly_nth))
        shot("sheet-calendar-yearly")
        // The confirm bar is the sheet's own footer, outside the scrolling column.
        text(s(R.string.sheet_add)).performClick()
        rule.waitUntilGone(s(R.string.sheet_repeat_ends))
        // Back on the card it reads itself back, and can be fenced like the rule it used to be.
        // Scrolled to the fence button rather than to the top of the card: it is the last thing
        // on it, and a capture that crops the one control this shot is for shows nothing.
        // Two "sólo si" on the screen by now — the countdown rule has one — and the calendar's
        // is the lower, because "Vuelve" sits under "Cuándo".
        rule.onAllNodesWithText(s(R.string.editor_add_condition), useUnmergedTree = true)[1]
            .performScrollTo()
            .assertIsDisplayed()
        shot("editor-recurrence-calendar")
        text(s(R.string.recur_none)).performScrollTo().performClick()
        rule.waitForIdle()

        // A rule can be fenced in, by hours or by a place: the trigger only counts inside them.
        text(s(R.string.editor_add_condition)).performScrollTo().performClick()
        rule.waitUntilDisplayed(s(R.string.condition_title))
        shot("sheet-condition")
        text(s(R.string.condition_kind_place)).performClick()
        rule.waitUntilDisplayed(s(R.string.condition_place_inside))
        shot("sheet-condition-place")
        text(s(R.string.condition_kind_hours)).performClick()
        rule.waitUntilDisplayed(s(R.string.condition_window_hint))
        text(s(R.string.sheet_add)).performClick()
        rule.waitUntilGone(s(R.string.condition_title))
        // Opening "nueva etiqueta" must not take the way to save with it. It used to: the
        // field was a one-way door, the Save bar stepped aside while it was open, and anybody
        // who scrolled back down to the triggers had lost the button with no way to get it
        // back. Both halves are checked — the bar stays, and the field closes behind you.
        text(s(R.string.editor_new_tag)).performScrollTo().performClick()
        rule.waitUntilDisplayed(s(R.string.editor_new_tag_hint))
        text(s(R.string.common_save)).assertIsDisplayed()
        text(s(R.string.editor_add_trigger)).performScrollTo().performClick()
        rule.waitUntilShown(s(R.string.kind_date))
        // All the way into a configurator and back out: the tag field is left behind twice over.
        text(s(R.string.kind_date)).performClick()
        rule.waitUntilDisplayed(s(R.string.sheet_cancel))
        text(s(R.string.sheet_cancel)).performClick()
        rule.waitUntilGone(s(R.string.sheet_cancel))
        text(s(R.string.common_save)).assertIsDisplayed()
        text(s(R.string.editor_new_tag)).performScrollTo().assertIsDisplayed()

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
        // The folded index is the screen now: ten rows, each carrying its own current value.
        shot("settings")
        // Everything below lives inside a group, so the group is opened before it is reached
        // for. Scrolled to the last thing in each card, so the whole of it is in the frame:
        // the two insistent numbers only appear when something asks for that sound.
        openGroup(s(R.string.settings_sound_title))
        text(s(R.string.settings_sound_gap)).performScrollTo()
        shot("settings-sound")
        openGroup(s(R.string.settings_vibration_strength))
        text(s(R.string.settings_vibration_try)).performScrollTo()
        shot("settings-vibration")
        // Dark mode only, by the owner's rule: the light scheme shares every token and layout,
        // and each extra pass through the emulator costs minutes.
        openGroup(s(R.string.settings_places))
        text(s(R.string.watch_log_open)).performScrollTo()
        shot("settings-location")
        // The change log lives at the very bottom, which is the last thing the tour scrolls to.
        openGroup(s(R.string.settings_about))
        text(s(R.string.settings_release_notes)).performScrollTo().performClick()
        rule.waitUntilDisplayed(s(R.string.whats_new_ok))
        shot("settings-release-notes")
        text(s(R.string.whats_new_ok)).performClick()
        rule.waitUntilGone(s(R.string.whats_new_ok))
        // Back up the screen: the release notes are at the very bottom, and Settings has grown
        // sections since this line was written — the row is above the fold by the time we get
        // here. Places is still open from the screenshot above, so the row is there to reach.
        text(s(R.string.watch_log_open)).performScrollTo().performClick()
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

    /**
     * Opens one of Settings' folded groups by its title. Idempotent by way of being called
     * once per group: a second call would close it again, which is exactly the trap a helper
     * called "open" should not set, so each group is opened where it is first needed.
     */
    private fun openGroup(title: String) {
        text(title).performScrollTo().performClick()
        rule.waitForIdle()
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
