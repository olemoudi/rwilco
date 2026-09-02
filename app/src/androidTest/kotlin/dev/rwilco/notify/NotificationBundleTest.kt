package dev.rwilco.notify

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.model.Action
import dev.rwilco.model.Reminder
import dev.rwilco.model.firingPlan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The one line that stands for the bundle, and the alert it must never take with it.
 *
 * Cancelling a group's summary cancels the group's surviving children too. Pulling the summary
 * at "fewer than two left", which is the reading that sounds right and was written first, wiped
 * the shade of the alert somebody still had to deal with. This is the test that found it.
 */
@RunWith(AndroidJUnit4::class)
class NotificationBundleTest {

    @get:Rule
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)!!

    private fun reminder(id: String, text: String) = Reminder(
        id = id,
        text = text,
        tags = listOf("casa"),
        actions = setOf(Action.NOTIFICATION, Action.VIBRATE),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private fun post(id: String, text: String, late: Instant? = null) {
        val r = reminder(id, text)
        AlertNotifications.post(context, r, firingPlan(r.actions), late = late, fullScreen = false)
    }

    /** Everything this app has posted, summary included. */
    private fun posted() = manager.activeNotifications.size

    private fun summaries() = manager.activeNotifications.count { it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0 }

    @Before
    fun clear() {
        manager.cancelAll()
        Thread.sleep(300)
    }

    @After
    fun leaveThemUp() = Unit

    /** Children only: the summary is the app's own line, not one of the reminders. */
    private fun alerts() = manager.activeNotifications.count { it.id != 1 }

    /**
     * Not an assertion: two alerts left standing in the shade so a person can look at them.
     * Run on its own, it is how the drawer gets a screenshot.
     */
    @Test
    fun leaveTwoForLooking() {
        post("look-a", "Sacar la basura y bajar el cartón")
        post("look-b", "Llamar al dentista para la revisión", late = Instant.now().minusSeconds(4_270))
        Thread.sleep(600)
    }

    /**
     * A channel from a tone nobody rings any more is swept away when the channels are made,
     * and the two quiet channels beside it are not: the phone's list used to keep every tone
     * ever chosen, and a muted one among them was a red strip on Home nothing could clear.
     */
    @Test
    fun aChannelOfAnOldToneIsSweptAwayAndTheQuietOnesStay() {
        manager.createNotificationChannel(
            android.app.NotificationChannel("alert_v2_zzz_old_tone", "old", NotificationManager.IMPORTANCE_DEFAULT),
        )
        AlertNotifications.ensureChannels(context)
        assertEquals(null, manager.getNotificationChannel("alert_v2_zzz_old_tone"))
        assertEquals(true, manager.getNotificationChannel(AlertNotifications.CHANNEL_MISSED) != null)
        assertEquals(true, manager.getNotificationChannel(AlertNotifications.CHANNEL_NET) != null)
    }

    @Test
    fun dealingWithOneAlertLeavesTheOtherStanding() {
        post("bundle-a", "Sacar la basura")
        post("bundle-b", "Llamar al dentista para la revisión")
        Thread.sleep(500)
        assertEquals("two alerts", 2, alerts())
        assertEquals("and the line that stands for them", 1, summaries())

        // The one that used to take the other down with it.
        AlertNotifications.cancel(context, "bundle-b")
        Thread.sleep(500)
        assertEquals("the surviving alert went with the summary", 1, alerts())

        AlertNotifications.cancel(context, "bundle-a")
        Thread.sleep(500)
        assertEquals(0, alerts())
        assertEquals("nothing left to stand for", 0, summaries())
    }

    /**
     * The line left standing over an empty bundle — "1 recordatorio" and nothing else — and the
     * sweep that is rid of it.
     *
     * Built here the way the bug built it: a count taken against a list the system has not
     * caught up with. The cards are taken down and the bundle is counted in the same breath, so
     * the count is answered about two cards that are already on their way out, and the summary
     * goes back up over what is by then nothing. Nothing counts the bundle again until the next
     * reminder rings, which is why it sat there for days.
     */
    @Test
    fun aSummaryLeftStandingOverNothingIsSweptAway() {
        post("ghost-a", "Sacar la basura")
        post("ghost-b", "Llamar al dentista para la revisión")
        Thread.sleep(500)
        assertEquals(2, alerts())

        // Behind the summary's back, and then a count that knows nothing about it.
        manager.cancel(AlertNotifications.notificationId("ghost-a"))
        manager.cancel(AlertNotifications.notificationId("ghost-b"))
        AlertNotifications.cancel(context, "ghost-never-posted")
        Thread.sleep(900)

        assertEquals("the alerts went", 0, alerts())
        // **The platform does not clean this up for us** (checked on API 35): a group summary
        // whose children have all gone stays exactly where it is, which is the whole reason the
        // sweep below has to exist.
        val orphan = manager.activeNotifications.firstOrNull { it.id == 1 }
        check(orphan != null) { "the leftover this test is about never formed" }
        // And it is not us pinning it: the line carries no ongoing flag of its own and the
        // platform calls it clearable. A swipe on a summary is a dismissal of the GROUP, and a
        // group with no children left is the state the shade handles worst — so the answer is
        // never to leave one, not to make it easier to swipe.
        check(orphan.isClearable) { "the leftover summary was flagged non-clearable" }

        AlertNotifications.sweepSummary(context)
        Thread.sleep(700)
        assertEquals("the line stayed over nothing", 0, summaries())
        assertEquals(0, alerts())
    }
}
