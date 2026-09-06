package dev.rwilco.alarm

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.RwilcoApplication
import dev.rwilco.geo.PlaceWatchStore
import dev.rwilco.R
import dev.rwilco.model.Crossing
import dev.rwilco.model.GeofenceIds
import dev.rwilco.model.PlaceWatchState
import dev.rwilco.model.Presence
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.routineDone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalTime
import java.util.UUID

/**
 * A routine's question and a place's reset, through the app's own doors on a real phone: the
 * asking posts a card with its two answers, a doorway that counts as done moves the count and
 * posts the mute word with "deshacer", and the watch reports that doorway as [Crossing.RESETS]
 * — the three halves only a device can answer (see Prompt.kt).
 */
@RunWith(AndroidJUnit4::class)
class RoutinePromptDeviceTest {

    @get:Rule
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as RwilcoApplication
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    private val garage = Trigger.Location(40.4169, -3.7035, 200, Presence.OUTSIDE, "Garaje", onCrossing = true)
    private val nine = Trigger.TimeOfDay(LocalTime.of(9, 0))
    private val id = UUID.randomUUID().toString()

    @Before
    fun oneRoutine() = runBlocking {
        // The watch only spends anything with "allow all the time"; handed over, as the watch test does.
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (permission in listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            runCatching { ui.grantRuntimePermission(context.packageName, permission) }
        }
        manager.cancelAll()
        app.repository.replaceAll(emptyList())
        val now = app.clock.instant()
        // Ten days in, so the quiet after the day it was written is long over.
        app.repository.save(
            Reminder(
                id = id,
                text = "Mover el coche",
                tags = listOf("coche"),
                rules = listOf(TriggerRule(garage, resets = true), TriggerRule(nine)),
                recurrence = Recurrence.Since(21, RecurrenceUnit.DAYS),
                createdAt = now.minus(Duration.ofDays(10)),
                updatedAt = now.minus(Duration.ofDays(10)),
            ),
        )
        app.placeWatcher.sync()
    }

    @After
    fun tidy() = runBlocking {
        manager.cancelAll()
        app.repository.replaceAll(emptyList())
    }

    private fun posted(titleContains: String): Notification? {
        var found: Notification? = null
        val deadline = System.currentTimeMillis() + 5_000
        while (found == null && System.currentTimeMillis() < deadline) {
            found = manager.activeNotifications
                .firstOrNull { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.contains(titleContains) == true }
                ?.notification
            if (found == null) Thread.sleep(50)
        }
        return found
    }

    @Test
    fun theQuestionIsACardWithItsTwoAnswers() = runBlocking {
        app.firing.ask(id, ruleIndex = 1, viaPlace = false)
        val card = posted("Mover el coche")
        assertNotNull("the question was not posted", card)
        assertEquals(context.getString(R.string.routines_question, "Mover el coche"), card!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(listOf(context.getString(R.string.notif_ask_yes), context.getString(R.string.notif_ask_later)), card.actions.map { it.title.toString() })
        assertNotNull("the question is written down", app.repository.get(id)!!.askedAt)
        // "Sí, ahora" is the same door "hecho" goes through: the count starts again, the card goes.
        app.firing.dismiss(id)
        val after = app.repository.get(id)!!
        assertNotNull(after.lastDealtAt)
        assertTrue(after.routineDone(app.clock.instant(), app.clock.zone))
        assertNull(posted("Mover el coche"))
    }

    @Test
    fun aDoorwayThatCountsAsDoneMovesTheCountAndSaysSoWithAWayBack() = runBlocking {
        val key = GeofenceIds.encode(id, 0, garage)
        // The watch has seen the phone inside the garage; leaving is the crossing the rule waits for.
        app.placeWatcher.remember(key, Transition.ENTER)
        assertEquals(Crossing.RESETS, app.placeWatcher.accept(key, Transition.EXIT))
        app.firing.resetBy(id, ruleIndex = 0)
        val after = app.repository.get(id)!!
        assertNotNull("leaving the garage is the car moving", after.lastDealtAt)
        val notice = posted(context.getString(R.string.notif_reset_title, "Mover el coche"))
        assertNotNull("the mute word about it was not posted", notice)
        assertEquals(listOf(context.getString(R.string.common_undo)), notice!!.actions.map { it.title.toString() })
        // Deshacer: the count goes back to where it ran from, which was the day it was written.
        app.firing.undoReset(id, previous = null)
        assertNull(app.repository.get(id)!!.lastDealtAt)
        // Done again, and out again a moment later: the second leaving is inside the quiet
        // after the first and says nothing new.
        app.firing.resetBy(id, ruleIndex = 0)
        val first = app.repository.get(id)!!.lastDealtAt
        assertNotNull(first)
        app.firing.resetBy(id, ruleIndex = 0)
        assertEquals(first, app.repository.get(id)!!.lastDealtAt)
    }

    @Test
    fun aDoorwayThatAsksIsReportedAsAQuestionByTheWatch() = runBlocking {
        val asks = app.repository.get(id)!!.let { it.copy(rules = listOf(TriggerRule(garage), it.rules[1])) }
        app.repository.save(asks)
        app.placeWatcher.sync()
        val key = GeofenceIds.encode(id, 0, garage)
        app.placeWatcher.remember(key, Transition.ENTER)
        assertEquals(Crossing.ASKS, app.placeWatcher.accept(key, Transition.EXIT))
        app.firing.ask(id, ruleIndex = 0, viaPlace = true)
        assertNotNull(posted("Mover el coche"))
        // And the same doorway a minute later is the same question, not a second one.
        val askedAt = app.repository.get(id)!!.askedAt
        app.firing.ask(id, ruleIndex = 0, viaPlace = true)
        assertEquals(askedAt, app.repository.get(id)!!.askedAt)
        // The watch's memory is reset for the next test.
        PlaceWatchStore(context).write(PlaceWatchState())
    }
}
