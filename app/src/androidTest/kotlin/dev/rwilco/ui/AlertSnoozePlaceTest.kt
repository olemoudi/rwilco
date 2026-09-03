package dev.rwilco.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.alarm.ReminderScheduler
import dev.rwilco.model.Action
import dev.rwilco.model.Fix
import dev.rwilco.model.PlaceWatchState
import dev.rwilco.model.Presence
import dev.rwilco.model.Reminder
import dev.rwilco.model.hereRadiusM
import dev.rwilco.model.SavedPlace
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.ui.alert.AlertActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * The two place answers on the ringing screen: "al llegar a Casa" writes the doorway in and
 * the screen leaves; "al salir de aquí" draws a circle around where the watch last saw the
 * phone and does the same. A device test because the offers are read off the phone — the saved
 * places, the watch's memory, the location grant — and answered off the main thread.
 */
@RunWith(AndroidJUnit4::class)
class AlertSnoozePlaceTest {

    @get:Rule(order = 0)
    val location: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    )

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as RwilcoApplication
    private val id = "alert-place"
    private val text = "Comprar filtros (prueba de lugar)"
    private var scenario: ActivityScenario<AlertActivity>? = null

    @Before
    fun ringingNow() = runBlocking {
        val now = app.clock.instant()
        app.settingsStore.update { it.copy(savedPlaces = listOf(SavedPlace("Casa", 40.4169, -3.7035, 200))) }
        // The watch last saw the phone a minute ago, in the street, sharply: enough for "aquí".
        app.placeWatch.write(PlaceWatchState(lastFix = Fix(40.4500, -3.6900, accuracyM = 15.0, at = now.minusSeconds(60))))
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
        // The screen leaves on its own once the answer is written; finishing it by hand is
        // only for a test that stopped short of that.
        runCatching { scenario?.onActivity { it.finish() } }
        runCatching { scenario?.close() }
        app.repository.delete(id)
        app.settingsStore.update { it.copy(savedPlaces = emptyList()) }
        app.placeWatch.write(PlaceWatchState())
    }

    @Test
    fun whenIGetHomeIsAnAnswer() {
        scenario = ActivityScenario.launch(alert())
        rule.waitUntilShown(text)
        val arrive = string { it.getString(R.string.snooze_arrive_at, "Casa") }
        rule.waitUntilShown(arrive)
        rule.onNodeWithText(arrive).assertIsDisplayed()
        rule.holdToAnswer(arrive)
        rule.waitUntil(timeoutMillis = 10_000) { runBlocking { app.repository.get(id)?.snoozedToPlace != null } }
        val door = runBlocking { app.repository.get(id)!!.snoozedToPlace!! }
        assertEquals(Presence.INSIDE, door.presence)
        assertEquals("Casa", door.label)
        assertEquals(true, door.onCrossing)
        gone()
    }

    @Test
    fun whenILeaveHereIsAnAnswerDrawnAroundThePhone() {
        scenario = ActivityScenario.launch(alert())
        rule.waitUntilShown(text)
        // The offer says the circle it would draw from the watch's own last position; the
        // seeded fix is ±15 m, so that is the floor ([SNOOZE_HERE_MIN_RADIUS_M]).
        val leave = string { it.getString(R.string.snooze_leave_here, hereRadiusM(15.0)) }
        rule.waitUntilShown(leave)
        rule.holdToAnswer(leave)
        rule.waitUntil(timeoutMillis = 20_000) { runBlocking { app.repository.get(id)?.snoozedToPlace != null } }
        val here = runBlocking { app.repository.get(id)!!.snoozedToPlace!! }
        assertEquals(Presence.OUTSIDE, here.presence)
        // As small as the fix could defend: twice its doubt, never under the floor.
        assertEquals(hereRadiusM(15.0), here.radiusM)
        assertEquals(40.4500, here.lat, 0.0001)
        assertEquals(-3.6900, here.lng, 0.0001)
        gone()
    }

    /** The screen leaves on its own once the reminder is no longer owed an answer. */
    private fun gone() {
        rule.waitUntil(timeoutMillis = 10_000) {
            runCatching { rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }.getOrDefault(true)
        }
    }

    private fun alert() = Intent(context, AlertActivity::class.java).setData(ReminderScheduler.reminderUri(id))

    private fun string(read: (AlertActivity) -> String): String {
        var value = ""
        scenario!!.onActivity { value = read(it) }
        return value
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
