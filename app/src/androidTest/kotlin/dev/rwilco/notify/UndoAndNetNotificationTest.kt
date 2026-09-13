package dev.rwilco.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.R
import dev.rwilco.alarm.AlertActionReceiver
import dev.rwilco.alarm.ReminderScheduler
import dev.rwilco.data.ReminderEntity
import dev.rwilco.model.FiringPlan
import dev.rwilco.model.NetWord
import dev.rwilco.model.Reminder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        assertEquals("a minute, then it goes by itself", AlertNotifications.DONE_NOTICE_MS, card.timeoutAfter)
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
    fun aHechoFromTheShadeLeavesAMinutesUndo() {
        // 0.129.0: the shade's "hecho" — the button on a ring's card, a contact's and a routine's
        // question alike — leaves the undo card again, and for a minute. Found by its own words,
        // so a net card about some other reminder cannot be mistaken for it; the title's wording
        // is the receiver's locale, so only the reminder's text is matched.
        val app = context.applicationContext as dev.rwilco.RwilcoApplication
        val ficus = bins.copy(id = "shade-done", text = "Regar el ficus (prueba)")
        runBlocking { app.repository.save(ficus) }
        try {
            context.sendBroadcast(
                Intent(context, AlertActionReceiver::class.java)
                    .setAction(AlertActionReceiver.ACTION_DONE)
                    .setData(ReminderScheduler.reminderUri(ficus.id)),
            )
            val about = { cardsOn(AlertNotifications.CHANNEL_NET).map { it.notification }.filter { ficus.text in it.title() } }
            val deadline = System.currentTimeMillis() + 10_000
            while (about().isEmpty()) {
                check(System.currentTimeMillis() < deadline) { "no undo card came" }
                Thread.sleep(100)
            }
            val card = about().single()
            assertEquals("one button, the undo", 1, card.actions.orEmpty().size)
            assertEquals("a minute, then it goes by itself", AlertNotifications.DONE_NOTICE_MS, card.timeoutAfter)
        } finally {
            AlertNotifications.cancelReset(context, ficus.id)
            runBlocking { app.repository.delete(ficus.id) }
        }
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
