package dev.rwilco.ui

import android.content.Intent
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
import dev.rwilco.model.Reminder
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.ui.alert.AlertActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * The one big button on a ringing alarm silences it first, and only then means "hecho".
 *
 * Half awake with the phone buzzing, "make it stop" and "I have done that" are the same reflex
 * and only one of them is true — and a reminder dismissed without being read is gone for good.
 * So the noise gets its own answer, the screen stays exactly as it was to decide with, and the
 * button becomes the ordinary "Hecho" once there is nothing left to silence.
 *
 * A device test because all three halves of it are only real on a device: a reminder that
 * actually asks for a noise, the ringer that makes one, and a button that changes under a thumb.
 */
@RunWith(AndroidJUnit4::class)
class AlertSilenceTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as RwilcoApplication
    private var scenario: ActivityScenario<AlertActivity>? = null

    private val loudId = "silence-loud"
    private val quietId = "silence-quiet"
    private val onceId = "silence-once"
    private val noteId = "silence-note"
    private val loud = "Sacar el pan del horno (prueba ruidosa)"
    private val quiet = "Bajar la basura (prueba silenciosa)"
    private val once = "Regar las plantas (prueba de un solo tono)"
    private val note = "Renovar el abono (prueba de la red)"

    /** [rang] false is a reminder that never went off — what the safety net's first word is about. */
    private fun seed(id: String, text: String, actions: Set<Action>, rang: Boolean = true) = runBlocking {
        val now = app.clock.instant()
        app.repository.save(
            Reminder(
                id = id,
                text = text,
                rules = listOf(TriggerRule(Trigger.AtTime(LocalTime.of(9, 0), DayOfWeek.entries.toSet()))),
                actions = actions,
                createdAt = now,
                updatedAt = now,
                lastFiredAt = if (rang) now else null,
            ),
        )
    }

    @After
    fun clean() = runBlocking {
        scenario?.onActivity { it.finish() }
        runCatching { scenario?.close() }
        app.repository.delete(loudId)
        app.repository.delete(quietId)
        app.repository.delete(onceId)
        app.repository.delete(noteId)
    }

    @Test
    fun aRingingAlertAsksToBeSilencedBeforeItWillTakeHecho() {
        seed(loudId, loud, setOf(Action.FULL_SCREEN, Action.VIBRATE))
        scenario = ActivityScenario.launch(alert(loudId))
        rule.waitUntilShown(loud)

        // While it buzzes there is one answer on the bottom of the screen, and it is not the
        // one that files the reminder away.
        val silence = string { it.getString(R.string.alert_silence) }
        val done = string { it.getString(R.string.alert_done) }
        rule.onNodeWithText(silence).assertIsDisplayed()
        check(rule.onAllNodesWithText(done).fetchSemanticsNodes().isEmpty()) {
            "«Hecho» was reachable while the alarm was still making a noise"
        }
        shot("alert-silence")

        rule.onNodeWithText(silence).performClick()

        // The noise is what was answered. The reminder is not: it is still owed one, still on
        // the screen, and the button now says so.
        rule.waitUntilShown(done)
        shot("alert-silenced")
        rule.onNodeWithText(loud).assertIsDisplayed()
        check(rule.onAllNodesWithText(silence).fetchSemanticsNodes().isEmpty())
        runBlocking {
            check(app.repository.get(loudId)?.lastDealtAt == null) { "silencing dismissed the reminder" }
        }

        // And now the ordinary answer, which is the one it was keeping out of reach.
        rule.onNodeWithText(done).performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { app.repository.get(loudId)?.lastDealtAt != null }
        }
    }

    @Test
    fun anAlertThatMakesNoNoiseGoesStraightToHecho() {
        // Nothing to silence, so nothing is asked: a full-screen alert with neither sound nor
        // vibration is the same one button it has always been.
        seed(quietId, quiet, setOf(Action.FULL_SCREEN, Action.NOTIFICATION))
        scenario = ActivityScenario.launch(alert(quietId))
        rule.waitUntilShown(quiet)

        rule.onNodeWithText(string { it.getString(R.string.alert_done) }).assertIsDisplayed()
        check(rule.onAllNodesWithText(string { it.getString(R.string.alert_silence) }).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `an alert that says its tone once goes straight to hecho`() {
        // "Sonido" is one tone and then silence. The silence step is there to stop a hand from
        // answering a NOISE instead of a reminder, and two seconds later there is no noise to
        // answer — so the step stood in a silent room, holding "hecho" out of reach for the
        // rest of the minute.
        seed(onceId, once, setOf(Action.FULL_SCREEN, Action.SOUND))
        scenario = ActivityScenario.launch(alert(onceId))
        rule.waitUntilShown(once)

        rule.onNodeWithText(string { it.getString(R.string.alert_done) }).assertIsDisplayed()
        check(rule.onAllNodesWithText(string { it.getString(R.string.alert_silence) }).fetchSemanticsNodes().isEmpty()) {
            "a tone said once asked to be silenced"
        }
    }

    @Test
    fun `a reminder opened from the safety net's note stays on the screen and stays quiet`() {
        // The net's word is about a reminder that is owed nothing — this one never rang at all
        // — and the screen's usual rule would take it off on the first emission, so the tap
        // would flash and do nothing. It is held, and it arrives silent: a note about a moment
        // that already got away is not an alarm, whatever the reminder was asked to do.
        seed(noteId, note, setOf(Action.FULL_SCREEN, Action.VIBRATE), rang = false)
        scenario = ActivityScenario.launch(
            alert(noteId).putExtra(ReminderScheduler.EXTRA_ANYWAY, true),
        )
        rule.waitUntilShown(note)

        rule.onNodeWithText(string { it.getString(R.string.alert_done) }).assertIsDisplayed()
        check(rule.onAllNodesWithText(string { it.getString(R.string.alert_silence) }).fetchSemanticsNodes().isEmpty()) {
            "the note's alert made a noise"
        }
        shot("alert-from-note")

        // And the ordinary answer still works, which is the whole reason it is on the screen.
        rule.onNodeWithText(string { it.getString(R.string.alert_done) }).performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { app.repository.get(noteId)?.lastDealtAt != null }
        }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(700)
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
