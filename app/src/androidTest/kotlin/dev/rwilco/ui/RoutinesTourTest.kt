package dev.rwilco.ui

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Action
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.routineDone
import dev.rwilco.ui.home.HOME_SEARCH_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalTime
import java.util.UUID

/**
 * The routines, walked the way a thumb would: Home's line names the one that is overdue, the
 * screen behind it lists every routine as the question it is, "vencidas" filters to the one
 * owed, the swipe's own answer says it has been done and the row turns to "Sí", and "Nueva
 * rutina" opens the form already speaking as a routine.
 *
 * A device test because the three halves are things only a phone answers: that the line is
 * the door, that the swipe's accessibility action reaches the same door "hecho" does, and that
 * the editor relabels itself on a real screen. Captures land beside the tour's.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RoutinesTourTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun useSpanish() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("es-ES")
        }
    }

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    private fun s(id: Int): String = rule.activity.getString(id)
    private fun s(id: Int, arg: Any): String = rule.activity.getString(id, arg)

    private val car = "Mover el coche"
    private val plants = "Regar las plantas"
    private val plain = "Comprar filtros"
    private val carId = UUID.randomUUID().toString()

    @Before
    fun twoRoutinesAndAReminder() = runBlocking {
        app.repository.replaceAll(emptyList())
        app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, theme = ThemeMode.DARK, presets = emptyList()) }
        val now = app.clock.instant()
        fun routine(id: String, text: String, daysAgo: Long, tags: List<String>) = Reminder(
            id = id,
            text = text,
            tags = tags,
            recurrence = Recurrence.Since(21, RecurrenceUnit.DAYS),
            actions = setOf(Action.NOTIFICATION, Action.VIBRATE),
            createdAt = now.minus(Duration.ofDays(daysAgo)),
            updatedAt = now.minus(Duration.ofDays(daysAgo)),
        )
        // The car is nine days overdue; the plants were watered yesterday.
        app.repository.save(routine(carId, car, daysAgo = 30, tags = listOf("coche")))
        app.repository.save(routine(UUID.randomUUID().toString(), plants, daysAgo = 1, tags = listOf("casa")))
        app.repository.save(
            Reminder(
                id = UUID.randomUUID().toString(),
                text = plain,
                rules = listOf(TriggerRule(Trigger.Interval(LocalTime.of(18, 0), LocalTime.of(20, 0)))),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun theLineIsTheDoorAndTheSwipeIsTheAnswer() {
        rule.waitUntil(timeoutMillis = 10_000) { rule.activity.resources.configuration.locales[0].language == "es" }
        // Home: the plain reminder is a card, the routines are not — they are the line, and
        // the line names the one that is overdue.
        rule.waitUntilShown(plain)
        rule.waitUntilShown(s(R.string.home_routines_overdue_title))
        rule.onNodeWithText(car, useUnmergedTree = true).assertIsDisplayed()
        check(rule.onAllNodesWithText(plants, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) { "a routine that is done for now is not on Home" }
        shot("home-routines-line")

        // The line is the door. Its description carries the row's own words as well — a screen
        // reader hears which routine is overdue *and* where the tap goes — so it is matched on
        // the door's half of it.
        rule.onNodeWithContentDescription(s(R.string.home_routines_open), substring = true).performClick()
        rule.waitUntilShown(s(R.string.routines_title))
        rule.waitUntilShown(s(R.string.routines_question, car))
        rule.onNodeWithText(s(R.string.routines_question, plants), substring = true, useUnmergedTree = true).assertIsDisplayed()
        // "Sí" is a button on the card, named after the routine (0.107.0).
        rule.onNodeWithContentDescription(s(R.string.routines_mark_done, plants)).assertIsDisplayed()
        shot("routines")

        // "Vencidas" keeps the one that is owed; "Todas" brings the rest back.
        rule.onNodeWithText(s(R.string.routines_filter_overdue)).performClick()
        rule.waitUntilGone(s(R.string.routines_question, plants))
        rule.onNodeWithText(s(R.string.routines_question, car), substring = true, useUnmergedTree = true).assertIsDisplayed()
        shot("routines-overdue")
        rule.onNodeWithText(s(R.string.routines_filter_all)).performClick()
        rule.waitUntilShown(s(R.string.routines_question, plants))

        // The magnifier in the bar narrows the list by words — the same field Home searches
        // with, in the bar's own place.
        rule.onNodeWithContentDescription(s(R.string.home_search)).performClick()
        rule.onNodeWithTag(HOME_SEARCH_TAG).performTextInput("coche")
        rule.waitUntilGone(s(R.string.routines_question, plants))
        rule.onNodeWithText(s(R.string.routines_question, car), substring = true, useUnmergedTree = true).assertIsDisplayed()
        shot("routines-search")
        rule.onNodeWithContentDescription(s(R.string.common_back)).performClick()
        rule.waitUntilShown(s(R.string.routines_question, plants))

        // The fold, over "Nueva rutina": every routine down to its words and the track of its
        // plazo, and a tap on one opens it back out on its own (0.105.0).
        rule.onNodeWithContentDescription(s(R.string.home_compact_on)).performClick()
        rule.waitUntilGone(s(R.string.routines_question, car))
        rule.onNodeWithText(car, useUnmergedTree = true).assertIsDisplayed()
        // The one that has run out says the word as well as wearing the colour.
        rule.onNodeWithText(s(R.string.routines_no), useUnmergedTree = true).assertIsDisplayed()
        shot("routines-compact")
        rule.onNodeWithText(car, useUnmergedTree = true).performClick()
        rule.waitUntilShown(s(R.string.routines_question, car))
        rule.onNodeWithContentDescription(s(R.string.home_compact_off)).performClick()
        rule.waitUntilShown(s(R.string.routines_question, plants))

        // The three controls a routine's card carries, the way a reminder's does (0.99.0): the
        // "⋯" opens the same menu the held press does, and "posponer" now ends with a calendar.
        rule.onNodeWithContentDescription(s(R.string.card_more, car)).performClick()
        rule.waitUntilShown(s(R.string.home_snooze))
        rule.onNodeWithText(s(R.string.home_snooze), useUnmergedTree = true).performClick()
        rule.waitUntilShown(s(R.string.snooze_pick_date))
        shot("routines-menu")
        rule.onNodeWithText(s(R.string.snooze_pick_date), useUnmergedTree = true).performClick()
        rule.waitUntilShown(s(R.string.snooze_until_title))
        shot("routines-snooze-date")
        rule.onNodeWithText(s(R.string.sheet_cancel), useUnmergedTree = true).performClick()
        rule.waitUntilGone(s(R.string.snooze_until_title))

        // The swipe's own accessibility action is the same door the gesture reaches: the car
        // moved, the count starts again, and the snackbar says when it will ask next.
        // The actions sit on the swipe's own node, over the card that carries the words.
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and hasAnyDescendant(hasText(s(R.string.routines_question, car), substring = true)))
            .performCustomAccessibilityActionWithLabel(s(R.string.card_swipe_done))
        rule.waitUntilShown(s(R.string.common_undo))
        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { app.repository.get(carId) }?.let { it.lastDealtAt != null && it.routineDone(app.clock.instant(), app.clock.zone) } == true
        }
        shot("routines-done")

        // "Nueva rutina" asks what is being added first, and "Rutina" opens the form already
        // speaking as a routine: its own name over it, where the count starts, how often, and
        // what else it does.
        rule.onNodeWithText(s(R.string.routines_new), useUnmergedTree = true).performClick()
        rule.waitUntilShown(s(R.string.routines_new_title))
        shot("routines-new-what")
        rule.onNodeWithText(s(R.string.routines_new_routine), useUnmergedTree = true).performClick()
        // The draft lands a beat after the screen: until it does the form is a blank reminder,
        // so the routine's own title is what says the draft has arrived. The section titles are
        // set in capitals, so they are matched whatever their case.
        rule.waitUntilShown(s(R.string.editor_title_new_routine))
        // Every word on the form says what is being written, not only the one over the door.
        rule.onNodeWithText(s(R.string.editor_routine_write), useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithText(s(R.string.editor_write), useUnmergedTree = true).assertCountEquals(0)
        rule.waitUntilShown(s(R.string.editor_period_title))
        rule.onNode(hasText(s(R.string.editor_start_title), ignoreCase = true), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(s(R.string.routine_start_now), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNode(hasText(s(R.string.editor_period_title), ignoreCase = true), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNode(hasText(s(R.string.editor_when_ask_title), ignoreCase = true), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        // The plazo is the whole of that card now: no "no repetir", and no second chip saying
        // what "otro plazo" says (0.104.0).
        rule.onAllNodesWithText(s(R.string.recur_since_chip), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithText(s(R.string.recur_none), useUnmergedTree = true).assertCountEquals(0)
        shot("routines-editor")
        // And the plazo card itself, which is the one that changed most.
        rule.onNodeWithText(s(R.string.recur_custom_span), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        shot("routines-editor-span")

        // And somebody to keep in touch with, a step at a time — keep in touch, which half of a
        // life, how close — onto a form already following Settings for those answers: three
        // months for a close colleague, on Wednesdays.
        // Back through the activity rather than a tap on the arrow: the "Hecha · deshacer"
        // snackbar from the swipe above sits over the top bar for its few seconds, and the tap
        // went to the snackbar. The editor answers system back exactly as it answers the arrow.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        // Waited for by the form going, not by the button coming back: in Spanish the new
        // routine's form is headed "Nueva rutina" too, so "is it shown?" said yes while the
        // editor was still on screen, and the tap landed on its title.
        rule.waitUntilGone(s(R.string.editor_routine_write))
        rule.onNodeWithText(s(R.string.routines_new), useUnmergedTree = true).performClick()
        rule.waitUntilShown(s(R.string.routines_new_kit))
        rule.onNodeWithText(s(R.string.routines_new_kit), useUnmergedTree = true).performClick()
        rule.waitUntilShown(s(R.string.routines_new_kind_title))
        shot("routines-new-kind")
        rule.onNodeWithText(s(R.string.routines_new_work), useUnmergedTree = true).performClick()
        rule.waitUntilShown(s(R.string.routines_new_closeness_title))
        shot("routines-new-closeness")
        rule.onNodeWithText(s(R.string.routines_new_close), useUnmergedTree = true).performClick()
        rule.waitUntilShown(s(R.string.editor_title_new_contact))
        // Creating one asks for the name and nothing else (0.119.0): the kind and how close were
        // answered on the way in, and Settings answer the rest.
        rule.onNode(hasText(s(R.string.editor_contact_title), ignoreCase = true), useUnmergedTree = true).assertIsDisplayed()
        for (title in listOf(R.string.editor_contact_kind, R.string.editor_start_title, R.string.editor_period_title, R.string.editor_contact_when, R.string.editor_tags_title)) {
            rule.onAllNodesWithText(s(title), ignoreCase = true, useUnmergedTree = true).assertCountEquals(0)
        }
        shot("routines-editor-contact-new")
        rule.onNode(hasSetTextAction(), useUnmergedTree = true).performTextInput("Ana")
        hideKeyboard()
        rule.onNodeWithText(s(R.string.common_save), useUnmergedTree = true).performClick()
        // Saved, it is a row asking the contact's own question.
        val question = s(R.string.routines_contact_question, "Ana")
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText(question, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        shot("routines-contact-row")

        // Opened again, the form has everything a contact is changed by hand with.
        rule.onNodeWithContentDescription(s(R.string.card_edit, "Ana")).performScrollTo().performClick()
        rule.waitUntilShown(s(R.string.editor_title_edit_contact))
        rule.onNodeWithText(s(R.string.editor_contact_close), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        shot("routines-editor-contact-who")
        val threeMonths = s(R.string.editor_contact_follows) + s(R.string.common_separator) +
            rule.activity.resources.getQuantityString(R.plurals.trigger_repeat_months, 3, 3)
        rule.onNodeWithText(threeMonths, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        // Its own note under the cadence: never "suena", never "si lo has hecho".
        rule.onNodeWithText(s(R.string.recur_contact_note), useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithText(s(R.string.recur_since_note), useUnmergedTree = true).assertCountEquals(0)
        shot("routines-editor-contact")
        // When it is told: Settings' days and window, and said to be Settings'.
        rule.onNodeWithText(s(R.string.editor_contact_follows), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        // Nothing asks and nothing is chosen about how it is told: those two cards are not there.
        rule.onAllNodesWithText(s(R.string.editor_when_ask_title), ignoreCase = true, useUnmergedTree = true).assertCountEquals(0)
        shot("routines-editor-contact-when")
        // And the foot says what the net does for a contact, not what it does for something that rings.
        rule.onNodeWithText(s(R.string.editor_net_contact_note), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    /** The keyboard down, the way WhenChipTest puts it down: it resizes the window and covers "Guardar". */
    private fun hideKeyboard() {
        val activity = rule.activity
        rule.runOnUiThread {
            activity.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        rule.waitForIdle()
        Thread.sleep(500)
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_000)
        val dir = java.io.File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilGone(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
}
