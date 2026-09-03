package dev.rwilco.ui

import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.alarm.ReminderScheduler
import dev.rwilco.model.Action
import dev.rwilco.model.AlertStacking
import dev.rwilco.model.Reminder
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.ui.alert.AlertActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Two reminders ringing within moments of each other both reach the person. The second start
 * of the alert activity used to clear its task and destroy the first alert, which left the
 * loser as a silent card in the shade — indistinguishable from never having rung.
 */
@RunWith(AndroidJUnit4::class)
class AlertStackTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as RwilcoApplication
    private val textA = "Llamar a Marta (prueba A)"
    private val textB = "Recoger el paquete (prueba B)"

    /** The third one exists so that answering one of them leaves a *stack* behind, not a single alert. */
    private val textC = "Sacar la basura (prueba C)"
    private var scenario: ActivityScenario<AlertActivity>? = null

    @Before
    fun seed() = runBlocking {
        val now = app.clock.instant()
        for ((id, text) in listOf("stack-a" to textA, "stack-b" to textB, "stack-c" to textC)) {
            app.repository.save(
                Reminder(
                    id = id,
                    text = text,
                    rules = listOf(TriggerRule(Trigger.AtTime(LocalTime.of(9, 0), DayOfWeek.entries.toSet()))),
                    actions = setOf(Action.FULL_SCREEN, Action.NOTIFICATION),
                    createdAt = now,
                    updatedAt = now,
                    lastFiredAt = now,
                ),
            )
        }
    }

    @After
    fun clean() = runBlocking {
        // The scenario cannot walk a singleTask activity that took a second intent down to
        // DESTROYED on its own; finishing it by hand is what the person's "Hecho" does anyway.
        scenario?.onActivity { it.finish() }
        runCatching { scenario?.close() }
        app.repository.delete("stack-a")
        app.repository.delete("stack-b")
        app.repository.delete("stack-c")
        app.settingsStore.update { it.copy(alertStacking = AlertStacking.SEQUENTIAL) }
    }

    @Test
    fun asStripsBothShareTheScreen() {
        runBlocking { app.settingsStore.update { it.copy(alertStacking = AlertStacking.STRIPS) } }
        scenario = ActivityScenario.launch(alert("stack-a"))
        rule.waitUntilShown(textA)

        context.startActivity(alert("stack-b").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        rule.waitUntilShown(textB)
        rule.onNodeWithText(textA).assertIsDisplayed()
        rule.onNodeWithText(textB).assertIsDisplayed()
        shot("alert-strips")

        // One hold answers both, on release: the strips empty first, then each is dealt with
        // in turn.
        val doneAll = string { it.getString(R.string.alert_done_all) }
        rule.onNodeWithText(doneAll).assertIsDisplayed()
        rule.holdToAnswer(doneAll) {
            check(runBlocking { app.repository.get("stack-a")?.lastDealtAt == null }) { "answered before the finger lifted" }
        }
        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { app.repository.get("stack-a")?.lastDealtAt != null && app.repository.get("stack-b")?.lastDealtAt != null }
        }
        // With nothing left ringing the activity closes itself. That it is gone is the assertion.
        rule.waitUntil(timeoutMillis = 10_000) {
            // A finished activity has no hierarchy to ask, which the test rule reports by throwing.
            runCatching { rule.onAllNodesWithText(doneAll).fetchSemanticsNodes().isEmpty() }.getOrDefault(true)
        }
    }

    @Test
    fun verOnAStripGivesThatOneTheScreenAndAnsweringItComesBackToTheRest() {
        // The complaint from the phone: three reminders on the screen, "Ver" on one of them,
        // and what came up was the edit form — the one thing nobody wants with an alarm going.
        // It gives that reminder the whole screen now, with every answer on it, and dealing with
        // it puts the person back among the ones that are left.
        runBlocking { app.settingsStore.update { it.copy(alertStacking = AlertStacking.STRIPS) } }
        scenario = ActivityScenario.launch(alert("stack-a"))
        rule.waitUntilShown(textA)
        context.startActivity(alert("stack-b").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        rule.waitUntilShown(textB)
        context.startActivity(alert("stack-c").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        rule.waitUntilShown(textC)

        // A tap, not a hold: it answers nothing. It does sleep through the guard's countdown
        // with the rest of the strip, which is what [tapView] waits for.
        val viewA = string { it.getString(R.string.alert_view_one, textA) }
        rule.tapView(viewA)

        // That one alone, on the screen the single alert uses: the other two are not on it.
        rule.waitUntilShown(string { it.getString(R.string.alert_snooze) })
        rule.onNodeWithText(textA).assertIsDisplayed()
        rule.onAllNodesWithText(textB).assertCountEquals(0)
        rule.onAllNodesWithText(textC).assertCountEquals(0)
        shot("alert-strip-focused")

        // The arrow up top puts it back among the others, having answered nothing.
        rule.onNodeWithContentDescription(string { it.getString(R.string.common_back) }).performClick()
        rule.waitUntilShown(textB)
        rule.onNodeWithText(textA).assertIsDisplayed()
        rule.onNodeWithText(textC).assertIsDisplayed()

        // And answering it on its own screen does the same thing, with one fewer.
        rule.tapView(viewA)
        rule.waitUntilShown(string { it.getString(R.string.alert_snooze) })
        rule.holdToAnswer(string { it.getString(R.string.alert_done) })
        rule.waitUntil(timeoutMillis = 10_000) { runBlocking { app.repository.get("stack-a")?.lastDealtAt != null } }
        rule.waitUntilShown(textB)
        rule.onNodeWithText(textC).assertIsDisplayed()
        rule.onAllNodesWithText(textA).assertCountEquals(0)
    }

    @Test
    fun oneAfterTheOtherTheSecondWaitsAndThenTakesOver() {
        scenario = ActivityScenario.launch(alert("stack-a"))
        rule.waitUntilShown(textA)

        context.startActivity(alert("stack-b").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        val waiting = string { it.resources.getQuantityString(R.plurals.alert_waiting, 1, 1) }
        rule.waitUntilShown(waiting)
        rule.onNodeWithText(textA).assertIsDisplayed()
        rule.onAllNodesWithText(textB).assertCountEquals(0)
        shot("alert-sequential")

        rule.holdToAnswer(string { it.getString(R.string.alert_done) })

        rule.waitUntilShown(textB)
        rule.onAllNodesWithText(waiting).assertCountEquals(0)
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_000)
        val dir = java.io.File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun alert(id: String) = Intent(context, AlertActivity::class.java).setData(ReminderScheduler.reminderUri(id))

    /** From the activity, which runs under the app's own per-app locale. */
    private fun string(read: (AlertActivity) -> String): String {
        var value = ""
        scenario!!.onActivity { value = read(it) }
        return value
    }

    /**
     * "Ver" on the strip whose description is [description] — the reminder's own words are in it,
     * because three strips are three "Ver"s. It is a plain tap, but it sleeps through the
     * screen's countdown with everything else, and a sleeping control has no click action:
     * waiting for one is waiting for the strip to answer at all.
     */
    private fun androidx.compose.ui.test.junit4.ComposeTestRule.tapView(description: String) {
        val button = hasContentDescription(description) and hasClickAction()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }
        onNode(button).performClick()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
