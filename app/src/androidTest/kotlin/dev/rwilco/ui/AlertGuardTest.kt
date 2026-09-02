package dev.rwilco.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
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
import dev.rwilco.ui.components.GUARD_TICK_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * The alert screen takes no tap. For two seconds after it comes up nothing but Silence
 * answers; after that, "Hecho" and the snoozes answer only to a finger kept on them, and
 * only when it lifts. [dev.rwilco.ui.components.PressGuardTest] has the rules
 * with no clock; this is the wiring — the countdown, the ring, the tick, the release — on a
 * screen that is actually up.
 *
 * The test clock stands still where a test is about *when* the finger went down: with it
 * running, the framework fast-forwards every animation to the end the moment it waits for
 * idle, and the two seconds are over before the first touch lands.
 */
@RunWith(AndroidJUnit4::class)
class AlertGuardTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as RwilcoApplication
    private var scenario: ActivityScenario<AlertActivity>? = null

    private val tapId = "guard-tap"
    private val earlyId = "guard-early"
    private val holdId = "guard-hold"
    private val snoozeId = "guard-snooze"
    private val tap = "Pagar el seguro (prueba de un toque)"
    private val early = "Sacar la basura (prueba de dedo impaciente)"
    private val hold = "Llamar al dentista (prueba de dedo que se queda)"
    private val snooze = "Poner la lavadora (prueba de posponer)"

    /** Quiet on purpose: nothing to silence, so "Hecho" is the button from the first frame. */
    private fun seed(id: String, text: String) = runBlocking {
        val now = app.clock.instant()
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

    @After
    fun clean() = runBlocking {
        rule.mainClock.autoAdvance = true
        // The screen leaves on its own once an answer is written; finishing it by hand is only
        // for a test that stopped short of that.
        runCatching { scenario?.onActivity { it.finish() } }
        runCatching { scenario?.close() }
        for (id in listOf(tapId, earlyId, holdId, snoozeId)) app.repository.delete(id)
    }

    @Test
    fun aPressThatBeginsDuringTheCountdownDoesNothingHoweverLongItIsKept() {
        seed(earlyId, early)
        rule.mainClock.autoAdvance = false
        scenario = ActivityScenario.launch(alert(earlyId))
        // Frame by frame until the words are up: a few dozen milliseconds into the countdown.
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.mainClock.advanceTimeByFrame()
            rule.onAllNodesWithText(early, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val done = string { it.getString(R.string.alert_done) }
        // The digit is composed on the frame after the words; a few more frames lay it out.
        rule.mainClock.advanceTimeBy(64)
        rule.onNodeWithText("2").assertIsDisplayed()
        check(rule.onAllNodes(hasText(done) and hasClickAction()).fetchSemanticsNodes().isEmpty()) {
            "«Hecho» answered while the digits were still up"
        }

        // Down while the digits are up, and kept well past the end of the countdown and the
        // length of a hold: the press never counted, so however long it stays it is nothing.
        rule.onNodeWithText(done).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(4_000)
        check(rule.onAllNodesWithTag(GUARD_TICK_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            "a press that began during the countdown got the tick"
        }
        rule.onNodeWithText(done).performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)

        check(runBlocking { app.repository.get(earlyId)?.lastDealtAt } == null) { "a press begun during the countdown filed the reminder away" }
        rule.onNodeWithText(early).assertIsDisplayed()
    }

    @Test
    fun aTapOnHechoDoesNothingAndSaysHow() {
        seed(tapId, tap)
        scenario = ActivityScenario.launch(alert(tapId))
        rule.waitUntilShown(tap)
        val done = string { it.getString(R.string.alert_done) }
        rule.waitUntilArmed(done)
        // Arming shows the hint once, as a promise; let it pass, so the one after the tap is
        // the tap's own.
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText(string { it.getString(R.string.alert_hold_hint) }).fetchSemanticsNodes().isEmpty()
        }

        // A tap is a hold let go at once. The clock is stopped so the framework cannot run the
        // hold to its end between the finger going down and coming up.
        rule.mainClock.autoAdvance = false
        rule.onNode(hasText(done) and hasClickAction()).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(200)
        rule.onNode(hasText(done) and hasClickAction()).performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)

        check(runBlocking { app.repository.get(tapId)?.lastDealtAt } == null) { "a tap filed the reminder away" }
        rule.onNodeWithText(tap).assertIsDisplayed()
        rule.onNodeWithText(string { it.getString(R.string.alert_hold_hint) }).assertIsDisplayed()
        shot("alert-guard-hint")
    }

    @Test
    fun aHoldKeptToItsEndAnswersWhenTheFingerLifts() {
        seed(holdId, hold)
        scenario = ActivityScenario.launch(alert(holdId))
        rule.waitUntilShown(hold)
        val done = string { it.getString(R.string.alert_done) }
        rule.holdToAnswer(done) {
            // The tick is up and the finger is still down: nothing has been written yet, and
            // the screen is still there to be looked at.
            check(runBlocking { app.repository.get(holdId)?.lastDealtAt } == null) { "the answer was given before the finger lifted" }
            rule.onNodeWithText(hold).assertIsDisplayed()
            shot("alert-guard-tick")
        }
        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { app.repository.get(holdId)?.lastDealtAt != null }
        }
        // And with nothing left to answer the screen leaves by itself.
        rule.waitUntil(timeoutMillis = 10_000) {
            runCatching { rule.onAllNodesWithText(hold).fetchSemanticsNodes().isEmpty() }.getOrDefault(true)
        }
    }

    @Test
    fun aHeldSnoozeSaysWhatItDidBeforeTheFingerLifts() {
        seed(snoozeId, snooze)
        scenario = ActivityScenario.launch(alert(snoozeId))
        rule.waitUntilShown(snooze)
        val tenMinutes = string { it.getString(R.string.snooze_ten_minutes) }
        val snoozed = string { it.getString(R.string.alert_snoozed) }
        rule.holdToAnswer(tenMinutes) {
            // "Pospuesto · 10 min" up top, with the tick, while the finger is still down.
            rule.onNodeWithText(snoozed).assertIsDisplayed()
            check(runBlocking { app.repository.get(snoozeId)?.snoozedUntil } == null)
        }
        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { app.repository.get(snoozeId)?.snoozedUntil != null }
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
