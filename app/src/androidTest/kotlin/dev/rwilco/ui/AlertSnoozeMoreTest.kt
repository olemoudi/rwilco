package dev.rwilco.ui

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.alarm.ReminderScheduler
import dev.rwilco.model.Action
import dev.rwilco.model.MIDDAY
import dev.rwilco.model.Reminder
import dev.rwilco.model.Snooze
import dev.rwilco.model.SnoozeDay
import dev.rwilco.model.SnoozeHour
import dev.rwilco.model.SnoozeSpec
import dev.rwilco.model.key
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.ui.alert.AlertActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The alert shows the offers somebody chose, and "a otro momento" is the door to the rest (0.137.0).
 *
 * Only a device answers the two halves that matter: that an offer kept off the alert is really
 * off it and really behind the door, over a full-screen activity with its own guard; and that
 * taking it from there is a snooze like any other — written, counted, and the screen gone.
 */
@RunWith(AndroidJUnit4::class)
class AlertSnoozeMoreTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as RwilcoApplication
    private val id = "alert-more"
    private val text = "Renovar el abono (prueba de posponer)"
    private var scenario: ActivityScenario<AlertActivity>? = null

    @Before
    fun ringingNowWithNextWeekKeptOffTheAlert() = runBlocking {
        val now = app.clock.instant()
        app.settingsStore.update { it.copy(hiddenSnoozes = setOf(Snooze.NEXT_WEEK.name, Snooze.WEEKEND.name), snoozeUses = emptyMap()) }
        app.repository.save(
            Reminder(
                id = id,
                text = text,
                rules = listOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.ofInstant(now, app.clock.zone)))),
                actions = setOf(Action.FULL_SCREEN, Action.NOTIFICATION),
                createdAt = now,
                updatedAt = now,
                lastFiredAt = now,
            ),
        )
    }

    @After
    fun clean() = runBlocking {
        runCatching { scenario?.onActivity { it.finish() } }
        runCatching { scenario?.close() }
        app.repository.delete(id)
        app.settingsStore.update { it.copy(hiddenSnoozes = emptySet(), snoozeUses = emptyMap()) }
    }

    @Test
    fun anOfferKeptOffTheAlertIsBehindTheOtherDoorAndStillAnAnswer() {
        scenario = ActivityScenario.launch(Intent(context, AlertActivity::class.java).setData(ReminderScheduler.reminderUri(id)))
        rule.waitUntilShown(text)
        val nextWeek = string { it.getString(R.string.snooze_next_week) }
        val more = string { it.getString(R.string.snooze_more) }
        rule.waitUntilShown(more)
        assertTrue("kept off the alert", rule.onAllNodesWithText(nextWeek).fetchSemanticsNodes().isEmpty())

        // Held, like every answer on this screen; what it opens is a list, and a list is tapped.
        rule.holdToAnswer(more)
        rule.waitUntilShown(string { it.getString(R.string.snooze_more_title) })
        rule.waitUntilShown(nextWeek)
        // A week out, it sits low in a list that is longer than the screen since 0.160.0.
        rule.onNodeWithText(nextWeek).performScrollTo().performClick()

        rule.waitUntil(timeoutMillis = ANSWER_MS) { runBlocking { app.repository.get(id)?.snoozedUntil != null } }
        val until = runBlocking { app.repository.get(id)!!.snoozedUntil!! }
        assertTrue("a week on, give or take the seconds this took", until.isAfter(app.clock.instant().plusSeconds(6 * 86_400)))
        // And counted, which is what puts it first in that list the next time.
        rule.waitUntil(timeoutMillis = 10_000) { runBlocking { app.settingsStore.settings.first().snoozeUses[Snooze.NEXT_WEEK.name] == 1 } }
        assertEquals(1, runBlocking { app.settingsStore.settings.first().snoozeUses[Snooze.NEXT_WEEK.name] })
    }

    /**
     * The other door opens on answers the alert does not have (0.160.0): none of the alert's own
     * buttons again, and the app's suggestions — "mañana a mediodía" among them, which is a
     * snooze like any other when it is taken, and counted so that it can climb the list.
     */
    @Test
    fun theOtherDoorOffersWhatTheAlertDoesNotAndNothingItDoes() {
        scenario = ActivityScenario.launch(Intent(context, AlertActivity::class.java).setData(ReminderScheduler.reminderUri(id)))
        rule.waitUntilShown(text)
        val ten = string { it.getString(R.string.snooze_ten_minutes) }
        val more = string { it.getString(R.string.snooze_more) }
        rule.waitUntilShown(more)
        val tens = rule.onAllNodesWithText(ten, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size
        assertEquals("the alert's own button", 1, tens)

        rule.holdToAnswer(more)
        rule.waitUntilShown(string { it.getString(R.string.snooze_more_title) })
        // The lengths read as sentences there, and some are the app's suggestions.
        rule.waitUntilShown(string { it.getString(R.string.snooze_more_in, it.resources.getQuantityString(R.plurals.snooze_more_days, 2, 2)) })
        assertEquals("not said again behind the door", tens, rule.onAllNodesWithText(ten, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
        shot("alert-snooze-more")

        val midday = string { it.getString(R.string.snooze_own_tomorrow_midday) }
        rule.waitUntilShown(midday)
        rule.onNodeWithText(midday).performScrollTo().performClick()

        rule.waitUntil(timeoutMillis = ANSWER_MS) { runBlocking { app.repository.get(id)?.snoozedUntil != null } }
        val until = runBlocking { app.repository.get(id)!!.snoozedUntil!! }
        val tomorrowAtTwo = LocalDate.now(app.clock).plusDays(1).atTime(MIDDAY).atZone(app.clock.zone).toInstant()
        assertEquals(tomorrowAtTwo, until)
        val key = SnoozeSpec.On(SnoozeDay.Tomorrow, SnoozeHour.At(MIDDAY)).key
        rule.waitUntil(timeoutMillis = 10_000) { runBlocking { app.settingsStore.settings.first().snoozeUses[key] == 1 } }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_000)
        val dir = java.io.File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        /**
         * How long a row of the sheet may take to become a snooze. The row answers only once the
         * sheet has slid away (0.139.0), and that slide runs on the test's clock, one frame per
         * look — on the headless emulator a frame of this screen costs half a second, and the
         * answer took eight.
         */
        const val ANSWER_MS = 30_000L
    }

    private fun string(read: (AlertActivity) -> String): String {
        var value = ""
        scenario!!.onActivity { value = read(it) }
        return value
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
