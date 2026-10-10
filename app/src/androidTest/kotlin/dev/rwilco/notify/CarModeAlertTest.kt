package dev.rwilco.notify

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Action
import dev.rwilco.model.Reminder
import dev.rwilco.model.firingPlan
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Android Auto (0.174.0): a phone in car mode never takes its own screen. Reported from the phone:
 * a full screen rung on the road waited on the lock, and was answered seven seconds after the car
 * was left by the hand taking the phone off the mount. The decision is a pure function with its
 * own JVM test; what only a device can answer is whether the mode the car puts the phone in is
 * the one the presenter reads, and whether the card it leaves asks for no screen.
 */
@RunWith(AndroidJUnit4::class)
class CarModeAlertTest {

    @get:Rule
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as RwilcoApplication
    private val manager = context.getSystemService(NotificationManager::class.java)!!

    /** The shell's switch, which is the one Android Auto flips: `UiModeManager.enableCarMode`. */
    private fun carMode(on: Boolean) {
        instrumentation.uiAutomation.executeShellCommand("cmd uimode car ${if (on) "yes" else "no"}").close()
        waitFor { (context.inCarMode() == on).takeIf { it } }
    }

    @After
    fun leaveTheCar() = carMode(false)

    @Test
    fun inTheCarAFullScreenRingIsACardThatAsksForNoScreen() {
        runBlocking { app.diagStore.clear() }
        carMode(true)
        val reminder = Reminder(
            id = "car-mode-a",
            text = "Comprar pan (prueba)",
            actions = setOf(Action.FULL_SCREEN, Action.NOTIFICATION),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        AlertPresenter.show(context, reminder, firingPlan(reminder.actions), late = null)

        val id = AlertNotifications.notificationId(reminder.id)
        val card: Notification = waitFor { manager.activeNotifications.firstOrNull { it.id == id }?.notification }
        assertEquals("a card asking for the screen from the car", null, card.fullScreenIntent)
        val line = waitFor {
            runBlocking { app.diagStore.read() }.notes
                .firstOrNull { it.tag == "show" && it.text.startsWith("r=${reminder.id.take(8)}") }?.text
        }
        check(line.contains("BANNER") && line.contains("car=y")) { "the report said: $line" }
        AlertNotifications.cancel(context, reminder.id)
    }

    /** What [read] gives once it gives anything, within five seconds. */
    private fun <T : Any> waitFor(read: () -> T?): T {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            read()?.let { return it }
            check(System.currentTimeMillis() < deadline) { "nothing came within five seconds" }
            Thread.sleep(100)
        }
    }
}
