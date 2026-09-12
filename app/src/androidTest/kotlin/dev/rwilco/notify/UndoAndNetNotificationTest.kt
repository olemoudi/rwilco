package dev.rwilco.notify

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.R
import dev.rwilco.data.ReminderEntity
import dev.rwilco.model.FiringPlan
import dev.rwilco.model.NetWord
import dev.rwilco.model.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * The two cards that used to say least: the one that takes a "hecho" back, and the net's word
 * about a reminder that cannot ring at all.
 *
 * A device test because what a notification carries — which actions, in which order, under which
 * words — is only answered by the system that shows it.
 */
@RunWith(AndroidJUnit4::class)
class UndoAndNetNotificationTest {

    @get:Rule
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)!!

    private val bins = Reminder(
        id = "plain-bins",
        text = "Sacar la basura",
        actions = emptySet(),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private val row = ReminderEntity(
        id = bins.id,
        text = bins.text,
        tags = "",
        triggers = "[]",
        actions = "[]",
        status = "ACTIVE",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
        doneAt = null,
    )

    @Before
    fun clear() {
        manager.cancelAll()
        Thread.sleep(300)
    }

    private fun cardsOn(channel: String) = manager.activeNotifications.filter { it.notification.channelId == channel }

    private fun Notification.title(): String = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun Notification.words(): List<String> = actions.orEmpty().map { it.title.toString() }

    @Test
    fun aPlainHechoCanBeTakenBackFromTheCardItLeaves() {
        AlertNotifications.doneNotice(context, bins, row)
        Thread.sleep(600)
        val card = cardsOn(AlertNotifications.CHANNEL_NET).single().notification
        assertEquals(context.getString(R.string.notif_done_title_plain, bins.text), card.title())
        assertEquals(listOf(context.getString(R.string.common_undo)), card.words())
        assertTrue("it does not sit in the shade for ever", card.timeoutAfter > 0)
    }

    @Test
    fun withoutARowToPutBackTheCardCarriesNoButton() {
        // A row too long to carry is posted without the undo rather than risking the binder: the
        // word still lands, and "Hechos" still has the reminder.
        AlertNotifications.doneNotice(context, bins, null)
        Thread.sleep(600)
        val card = cardsOn(AlertNotifications.CHANNEL_NET).single().notification
        assertEquals(emptyList<String>(), card.words())
    }

    @Test
    fun aReminderThatCanNeverRingSaysSoAndOffersTheFormInsteadOfASnooze() {
        AlertNotifications.post(context, bins, QUIET, late = null, nudge = NetWord.CANNOT_RING)
        Thread.sleep(600)
        val card = cardsOn(AlertNotifications.CHANNEL_NET).single().notification
        assertEquals(context.getString(R.string.notif_net_prefix_cannot, bins.text), card.title())
        assertEquals(
            context.getString(R.string.notif_net_subtext_cannot),
            card.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString(),
        )
        assertEquals(
            listOf(context.getString(R.string.alert_done), context.getString(R.string.notif_net_fix)),
            card.words(),
        )
    }

    @Test
    fun oneWaitingAtAPlaceOffersToLiftTheWait() {
        AlertNotifications.post(context, bins, QUIET, late = null, nudge = NetWord.WAITING)
        Thread.sleep(600)
        val card = cardsOn(AlertNotifications.CHANNEL_NET).single().notification
        assertEquals(
            listOf(context.getString(R.string.alert_done), context.getString(R.string.home_cancel_snooze)),
            card.words(),
        )
    }

    private companion object {
        /** How the net always arrives: a card in the shade and nothing else. */
        val QUIET = FiringPlan(fullScreen = false, notification = true, sound = false, vibrate = false)
    }
}
