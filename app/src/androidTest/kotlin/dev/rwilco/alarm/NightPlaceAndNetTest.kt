package dev.rwilco.alarm

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Action
import dev.rwilco.model.AppSettings
import dev.rwilco.model.AwakeHours
import dev.rwilco.model.FiringKind
import dev.rwilco.model.FiringPlan
import dev.rwilco.model.Presence
import dev.rwilco.model.Reminder
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.notify.AlertNotifications
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The two rules of 0.210.0, through the real doors (owner, 2026-11-18):
 *
 * - **A ring a place set off ignores the night.** "Cuando salga de casa" at four in the morning
 *   rings; a clock ring at the same hour stays hushed, as it always has.
 * - **The safety net's ICYMI only goes out when nothing about the reminder is in the shade.** With
 *   its ring's card still open the word is held back, and the moment's turn is spent all the same.
 *
 * What makes a card loud is its channel, so that is what is read: a hushed ring goes out on a
 * channel with no sound.
 */
@RunWith(AndroidJUnit4::class)
class NightPlaceAndNetTest {

    @get:Rule
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RwilcoApplication
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private lateinit var before: AppSettings

    @Before
    fun setUp() = runBlocking {
        app.repository.deleteAll()
        manager.cancelAll()
        before = app.settingsStore.settings.first()
    }

    @After
    fun tearDown() = runBlocking {
        manager.cancelAll()
        app.repository.deleteAll()
        app.settingsStore.update { before }
    }

    /** Up for one hour, three hours from now, every day: whatever the clock says, it is the middle of the night. */
    private suspend fun asleepNow() {
        val now = LocalDateTime.ofInstant(app.clock.instant(), app.clock.zone).toLocalTime()
        val wake = now.plusHours(3).withSecond(0).withNano(0)
        val sleep = wake.plusHours(1)
        app.settingsStore.update { it.copy(awake = AwakeHours(wake = wake, sleep = sleep, weekendWake = wake, weekendSleep = sleep)) }
    }

    private fun reminder(id: String, vararg rules: TriggerRule, lastFiredAgo: Duration? = null): Reminder {
        val now = app.clock.instant()
        return Reminder(
            id = id,
            text = "Coger las llaves del trastero",
            rules = rules.toList(),
            actions = setOf(Action.NOTIFICATION, Action.SOUND),
            createdAt = now.minus(Duration.ofDays(3)),
            updatedAt = now.minus(Duration.ofDays(3)),
            lastFiredAt = lastFiredAgo?.let { now.minus(it) },
        )
    }

    /** The ring's card, once the binder hop has put it in the shade. */
    private fun ringCard(id: String) = waitFor { manager.activeNotifications.firstOrNull { it.id == AlertNotifications.notificationId(id) } }

    private fun <T> waitFor(read: () -> T?): T? {
        val deadline = System.currentTimeMillis() + 5_000
        var value = read()
        while (value == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            value = read()
        }
        return value
    }

    private fun soundsOn(channelId: String): Boolean = manager.getNotificationChannel(channelId)?.sound != null

    @Test
    fun leavingHomeInTheNightRingsAndAClockRingAtTheSameHourStaysHushed() = runBlocking {
        asleepNow()
        val leaving = Trigger.Location(40.4169, -3.7035, 200, Presence.OUTSIDE, "Casa", onCrossing = true)
        app.repository.save(reminder("night-place", TriggerRule(leaving)))
        app.firing.fire("night-place", ruleIndex = 0)

        val placeCard = ringCard("night-place")
        assertNotNull("leaving home did not ring", placeCard)
        assertTrue("«cuando salga de casa» at night went out silent", soundsOn(placeCard!!.notification.channelId))

        // A clock ring in the same night: a ten-minute timer running out now, armed the way the
        // app arms it. Hushed, as before 0.210.0.
        val now = app.clock.instant()
        app.repository.save(
            reminder("night-clock", TriggerRule(Trigger.Countdown(10, startedAt = now.minus(Duration.ofSeconds(598))))),
        )
        app.scheduler.rearmAll()
        app.firing.fire("night-clock")
        val clockCard = ringCard("night-clock")
        assertNotNull("the timer did not ring at all", clockCard)
        assertFalse("a clock ring in the night made a noise", soundsOn(clockCard!!.notification.channelId))
    }
}
