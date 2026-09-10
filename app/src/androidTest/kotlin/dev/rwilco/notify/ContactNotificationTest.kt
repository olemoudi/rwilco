package dev.rwilco.notify

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.R
import dev.rwilco.model.Closeness
import dev.rwilco.model.ContactKind
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/**
 * A contact's card as the shade really holds it: the question «¿Has llamado a Ana?», its two
 * answers, and no sound on the channel — the telling and the net's word the day after alike.
 *
 * A device test because what a notification carries, and what its channel will do, is only
 * answered by the system that shows it.
 */
@RunWith(AndroidJUnit4::class)
class ContactNotificationTest {

    @get:Rule
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)!!

    private val ana = Reminder(
        id = "contact-ana",
        text = "Ana",
        recurrence = Recurrence.Since(3, RecurrenceUnit.MONTHS),
        contactKind = ContactKind.WORK,
        contactCloseness = Closeness.CLOSE,
        actions = emptySet(),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Before
    fun clear() {
        manager.cancelAll()
        Thread.sleep(300)
    }

    private fun contactCards() = manager.activeNotifications.filter { it.notification.channelId == AlertNotifications.CHANNEL_CONTACT }

    private fun Notification.title(): String = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    @Test
    fun theTellingAsksHasLlamadoAndOffersTwoAnswersWithoutASound() {
        AlertNotifications.contact(context, ana, Duration.ofDays(92))
        Thread.sleep(600)
        val card = contactCards().single().notification
        assertEquals(context.getString(R.string.notif_contact_title, "Ana"), card.title())
        assertEquals(
            listOf(context.getString(R.string.notif_contact_done), context.getString(R.string.notif_contact_put_off)),
            card.actions.map { it.title.toString() },
        )
        val channel = manager.getNotificationChannel(AlertNotifications.CHANNEL_CONTACT)!!
        assertNull("the channel has no tone", channel.sound)
        assertFalse("nor a buzz", channel.shouldVibrate())
    }

    @Test
    fun theNetsWordIsTheSameCardUnderIcymiOverTheTelling() {
        AlertNotifications.contact(context, ana, Duration.ofDays(92))
        AlertNotifications.contact(context, ana, Duration.ofDays(93), nudge = true)
        Thread.sleep(600)
        val card = contactCards().single().notification
        val question = context.getString(R.string.notif_contact_title, "Ana")
        assertEquals(context.getString(R.string.notif_net_prefix, question), card.title())
        assertEquals("the same two answers", 2, card.actions.size)
    }
}
