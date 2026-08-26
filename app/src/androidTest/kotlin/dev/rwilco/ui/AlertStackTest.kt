package dev.rwilco.ui

import android.content.Intent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
    private var scenario: ActivityScenario<AlertActivity>? = null

    @Before
    fun seed() = runBlocking {
        val now = app.clock.instant()
        for ((id, text) in listOf("stack-a" to textA, "stack-b" to textB)) {
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

        rule.onNodeWithText(string { it.getString(R.string.alert_done) }).performClick()

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

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
