package dev.rwilco.ui

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
import dev.rwilco.model.SavedWindow
import dev.rwilco.debug.DemoData
import dev.rwilco.ui.components.TIME_FIELD_TAG
import dev.rwilco.ui.components.TYPED_TIME_TAG
import dev.rwilco.ui.editor.EDITOR_TEXT_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalTime
import androidx.compose.ui.test.isHeading

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

    private fun s(id: Int, arg: Any): String = rule.activity.getString(id, arg)

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
                    // A stretch of the day under its own name, so the window half of "when in
                    // the day" has the chips it is for rather than two bare fields.
                    savedWindows = listOf(
                        SavedWindow("A la hora de comer", LocalTime.of(14, 0), LocalTime.of(16, 0)),
                        SavedWindow("Por la tarde", LocalTime.of(17, 0), LocalTime.of(20, 0)),
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
        // the words now: a reminder with neither trigger nor action is a note, and saves. Said
        // twice since 0.46.0: the line under the field, and a snackbar for the button that was
        // pressed three cards below it.
        text(s(R.string.common_save)).performClick()
        rule.onAllNodesWithText(s(R.string.editor_error_text), useUnmergedTree = true).onFirst().assertIsDisplayed()

        // Nothing is auto-focused on opening: the button is the way to the keyboard, and what
        // has been written before is offered under it. A refused save is the exception — it
        // wanted the words, so the cursor is already in them and the button has stepped aside.
        rule.onNodeWithTag(EDITOR_TEXT_TAG).assertIsFocused()
        rule.onNodeWithTag(EDITOR_TEXT_TAG).performTextInput(reminderText)
        text(s(R.string.editor_add_trigger)).performScrollTo().performClick()
        rule.waitUntilShown(s(R.string.kind_date))
        shot("editor-kinds")

        // Every configurator opens and cancels cleanly.
        for ((kind, name) in listOf(
            R.string.kind_date to "sheet-date",
            R.string.kind_date_range to "sheet-date-range",
            R.string.kind_time_of_day to "sheet-time-of-day",
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
            // A sheet no longer settles into "hidden", so a fling that runs out of content
            // cannot take a half-filled form with it. The ways out that MEAN it have to still
            // work, and the back gesture is the one that goes through the sheet rather than
            // through a button of ours — so it is the one worth pinning. On the window sheet
            // because it opens instantly; the place one spends six seconds on map tiles.
            if (kind == R.string.kind_interval) {
                rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                rule.waitUntilGone(s(R.string.sheet_cancel))
                // Back closes the picker behind it too, so the way in is the button again.
                text(s(R.string.editor_add_trigger)).performScrollTo().performClick()
                rule.waitUntilShown(s(R.string.kind_date))
                text(s(R.string.kind_interval)).performClick()
                rule.waitUntilDisplayed(s(R.string.sheet_cancel))
            }
            if (kind == R.string.kind_place) {
                // A place being added opens on the doorway — "al llegar" is the sentence people
                // write — so the sheet's own shot above is already the crossing reading. The
                // second shot is the other half of the switch: the place as a state.
                text(s(R.string.place_side_arriving)).performScrollTo().assertIsDisplayed()
                text(s(R.string.place_side_leaving)).performScrollTo().performClick()
                rule.waitUntilDisplayed(s(R.string.place_means_leaving))
                text(s(R.string.place_needs_crossing)).performScrollTo().performClick()
                rule.waitUntilDisplayed(s(R.string.place_side_outside))
                shot("sheet-place-presence")
                // The radius, which lives under the map and so is off the bottom of both shots
                // above: its own, because the slider's inset from the sheet margin is a thing to
                // look at rather than a thing to assert.
                // The slider itself, not its label: the label is the top of the row and scrolling
                // to it leaves the control under the sheet's action bar.
                rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).performScrollTo().assertIsDisplayed()
                shot("sheet-place-radius")
            }
            // The date tile carries all three answers to "when in the day", and it opens on the
            // one that asks for nothing: the hint under it is the day's own waking hours, which
            // is the thing the settings are for.
            if (kind == R.string.kind_date) {
                rule.waitUntilDisplayed(s(R.string.sheet_any_time))
                shot("sheet-date-any-time")
                // A day named without looking: the chip picks the date and nothing else, and
                // the grid turns to it (next Monday may be on the next page).
                text(s(R.string.date_shortcut_next_monday).replaceFirstChar { it.titlecase() }).performScrollTo().performClick()
                rule.waitForIdle()
                // The middle one: a stretch, by the name somebody gave it. The two fields under
                // the chips are the way out of needing a name at all.
                text(s(R.string.sheet_in_window)).performScrollTo().performClick()
                rule.waitUntilShown(s(R.string.sheet_in_window_hint))
                text(s(R.string.sheet_in_window_hint)).performScrollTo()
                rule.waitUntilDisplayed(s(R.string.sheet_in_window_hint))
                shot("sheet-date-window")
                // And the narrowest, which is the only one with an hour to pick.
                text(s(R.string.sheet_at_this_time)).performScrollTo().performClick()
                rule.waitUntilShown(s(R.string.sheet_time))
                text(s(R.string.sheet_time)).performScrollTo()
                rule.waitUntilDisplayed(s(R.string.sheet_time))
                // The wheels, which replaced a dial nobody could hit one-handed.
                rule.onAllNodesWithTag(TIME_FIELD_TAG, useUnmergedTree = true)[0].performClick()
                rule.waitUntilShown(s(R.string.sheet_done))
                shot("time-wheels")
                // And the keypad behind the toggle: "930" reads as half past nine before "Done".
                rule.onNodeWithContentDescription(s(R.string.time_type)).performClick()
                rule.onNodeWithTag(TYPED_TIME_TAG, useUnmergedTree = true).performTextInput("930")
                rule.waitUntilDisplayed("09:30")
                shot("time-typed")
                text(s(R.string.sheet_done)).performClick()
                rule.waitUntilGone(s(R.string.sheet_done))
                rule.waitUntilDisplayed("09:30")
                // And the other way of saying which day: counted from the day it is used, which
                // is what makes a preset for "mañana" mean tomorrow every time.
                text(s(R.string.sheet_date_relative)).performScrollTo().performClick()
                rule.waitUntilShown(s(R.string.sheet_relative_in))
                text(s(R.string.relative_tomorrow).replaceFirstChar { it.titlecase() }).performScrollTo().performClick()
                rule.waitForIdle()
                text(s(R.string.sheet_relative_hint)).performScrollTo()
                rule.waitUntilDisplayed(s(R.string.sheet_relative_hint))
                shot("sheet-date-relative")
                // Back to a day on the calendar, so the rest of the tour reads as it always did.
                text(s(R.string.sheet_date_fixed)).performScrollTo().performClick()
                rule.waitForIdle()
            }
            // An hour and the days it counts on, and no date anywhere: the point a window is a
            // stretch of, and the moment a set is built around.
            if (kind == R.string.kind_time_of_day) {
                text(s(R.string.time_of_day_hint_alone)).performScrollTo().assertIsDisplayed()
                text(s(R.string.trigger_weekdays)).performScrollTo().performClick()
                rule.waitForIdle()
            }
            // Two calendars and no hour at all: a stretch of the calendar, said as a trigger.
            if (kind == R.string.kind_date_range) {
                text(s(R.string.date_range_from)).performScrollTo().assertIsDisplayed()
                text(s(R.string.date_range_to)).performScrollTo().assertIsDisplayed()
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
        // A minute at a time. The chips are the coarse answers; the stepper is the fine one,
        // and it moved five at a time, so from the five-minute chip three minutes could not be
        // asked for at all — only nought or ten.
        text(s(R.string.countdown_minutes, 5)).performClick()
        val less = rule.onAllNodesWithContentDescription(s(R.string.stepper_less), useUnmergedTree = true)
        less[1].performClick()
        less[1].performClick()
        rule.waitUntilDisplayed("3")
        text(s(R.string.sheet_add)).performClick()
        rule.waitUntilGone(s(R.string.sheet_add))
        rule.onNodeWithContentDescription(s(R.string.editor_edit_trigger)).assertIsDisplayed()
        // What the draft will do, read back over the button: a countdown of three minutes rings
        // today, and the line says so in the first amber words of the bar.
        rule.onNode(hasText(s(R.string.editor_will_ring, ""), substring = true), useUnmergedTree = true).assertIsDisplayed()

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
        // The preview is guarded like the real thing: the capture is of the screen once it
        // has armed, and closing it is a hold — with the tick up top the moment before.
        rule.waitUntilArmed(s(R.string.alert_close_preview))
        shot("alert-preview")
        rule.holdToAnswer(s(R.string.alert_close_preview)) { shot("alert-hold") }
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
        // The ten grant reads are off the main thread (see rememberAlertReadiness), so the
        // group in trouble opens itself a beat after the screen arrives. Waited for, or the
        // next tap lands on a list that is still moving — and on this emulator, which never
        // has the overlay or usage access, that group always opens.
        rule.waitUntilShown(s(R.string.settings_test_alert))
        // The folded index is the screen now: ten rows, each carrying its own current value.
        shot("settings")
        // Everything below lives inside a group, so the group is opened before it is reached
        // for. Scrolled to the last thing in each card, so the whole of it is in the frame:
        // the two insistent numbers only appear when something asks for that sound.
        openGroup(s(R.string.settings_sound_title), s(R.string.settings_sound_gap))
        text(s(R.string.settings_sound_gap)).performScrollTo()
        shot("settings-sound")
        openGroup(s(R.string.settings_vibration_strength), s(R.string.settings_vibration_try))
        text(s(R.string.settings_vibration_try)).performScrollTo()
        shot("settings-vibration")
        // Dark mode only, by the owner's rule: the light scheme shares every token and layout,
        // and each extra pass through the emulator costs minutes.
        openGroup(s(R.string.settings_places), s(R.string.watch_log_open))
        text(s(R.string.watch_log_open)).performScrollTo()
        shot("settings-location")
        // The change log lives at the very bottom, which is the last thing the tour scrolls to.
        openGroup(s(R.string.settings_about), s(R.string.settings_release_notes))
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
     * Opens one of Settings' folded groups by its title, unless [marker] — something inside it —
     * is already on screen.
     *
     * The check is the whole point: a group in trouble opens itself on arrival (the alerts, and
     * the places when a place reminder has no background permission, which is every emulator),
     * and a helper called "open" that toggles would close exactly the group whose contents the
     * next line reaches for. That is what it did.
     */
    private fun openGroup(title: String, marker: String) {
        if (rule.onAllNodesWithText(marker, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) return
        // The heading, not merely the words: a group's name can also be the label of a chip
        // inside another group ("Sonido" is both), and a plain text match then finds two.
        rule.onNode(hasText(title, ignoreCase = true) and isHeading(), useUnmergedTree = true)
            .performScrollTo()
            .performClick()
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
