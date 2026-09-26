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
import dev.rwilco.model.Presence
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.routineDeadline
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
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

    private val garage = Trigger.Location(40.4169, -3.7035, 200, Presence.OUTSIDE, "Garaje", onCrossing = true)

    private val car = Reminder(
        id = "routine-car",
        text = "Mover el coche (prueba)",
        rules = listOf(TriggerRule(garage, resets = true)),
        recurrence = Recurrence.Since(21, RecurrenceUnit.DAYS),
        actions = emptySet(),
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
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
    fun aRoutinesCardOffersASuHoraInPlaceOfTheSecondSnooze() {
        // 0.157.0: "Hecho" is now; a routine's card also says "a su hora", counted from the
        // deadline. Three is the cap, so it takes the second snooze's place — and only there.
        AlertNotifications.post(context, car, QUIET, late = null, nudge = NetWord.LET_GO)
        Thread.sleep(600)
        val words = cardsOn(AlertNotifications.CHANNEL_NET).single().notification.words()
        assertEquals(3, words.size)
        assertEquals(listOf(context.getString(R.string.alert_done), context.getString(R.string.notif_done_on_time)), words.take(2))
        AlertNotifications.cancel(context, car.id)
        Thread.sleep(300)
        AlertNotifications.post(context, bins, QUIET, late = null, nudge = NetWord.LET_GO)
        Thread.sleep(600)
        val plain = cardsOn(AlertNotifications.CHANNEL_NET).single().notification.words()
        assertEquals("a reminder keeps both snoozes", 3, plain.size)
        assertEquals(false, context.getString(R.string.notif_done_on_time) in plain)
    }

    @Test
    fun aSuHoraFromTheShadeCountsFromTheDeadlineAndLeavesAMinutesUndo() {
        val app = context.applicationContext as dev.rwilco.RwilcoApplication
        // Weekly, written nine days ago and never done: due two days ago.
        val plants = car.copy(
            id = "shade-on-time",
            text = "Regar las plantas (prueba)",
            rules = emptyList(),
            recurrence = Recurrence.Since(7, RecurrenceUnit.DAYS),
            createdAt = Instant.now().minus(Duration.ofDays(9)),
            updatedAt = Instant.now().minus(Duration.ofDays(9)),
        )
        runBlocking { app.repository.save(plants) }
        try {
            val dayStart = runBlocking { app.settingsStore.settings.first().dayStart }
            val due = plants.routineDeadline(app.clock.zone, dayStart)!!
            context.sendBroadcast(
                Intent(context, AlertActionReceiver::class.java)
                    .setAction(AlertActionReceiver.ACTION_DONE_ON_TIME)
                    .setData(ReminderScheduler.reminderUri(plants.id)),
            )
            val about = { cardsOn(AlertNotifications.CHANNEL_NET).map { it.notification }.filter { plants.text in it.title() } }
            val deadline = System.currentTimeMillis() + 10_000
            while (about().isEmpty()) {
                check(System.currentTimeMillis() < deadline) { "no undo card came" }
                Thread.sleep(100)
            }
            assertEquals("one button, the undo", 1, about().single().actions.orEmpty().size)
            assertEquals("counted from the deadline, not from now", due, runBlocking { app.repository.get(plants.id) }!!.lastDealtAt)
        } finally {
            AlertNotifications.cancelReset(context, plants.id)
            runBlocking { app.repository.delete(plants.id) }
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

    @Test
    fun aRoutineCountedDoneByAPlaceCanBeAgreedWithBeforeItCanBeUndone() {
        // 0.131.0: the app counted this one done on its own, so the card is a question. Agreeing
        // with it used to be a swipe — the same gesture as ignoring it — next to the one button
        // that would take it back. Now "confirmar" comes first and "deshacer" after it.
        AlertNotifications.resetNotice(context, car, garage, previous = Instant.now().minus(Duration.ofDays(21)))
        Thread.sleep(600)
        val card = cardsOn(AlertNotifications.CHANNEL_NET).single().notification
        assertEquals(context.getString(R.string.notif_reset_title, car.text), card.title())
        assertEquals(
            listOf(context.getString(R.string.notif_reset_confirm), context.getString(R.string.common_undo)),
            card.words(),
        )
    }

    @Test
    fun confirmingTakesTheCardAwayAndLeavesTheCountWhereTheResetPutIt() {
        val app = context.applicationContext as dev.rwilco.RwilcoApplication
        // The count as the reset left it: what "confirmar" must not move, and what "deshacer"
        // would have rolled back to [previous].
        val counted = Instant.now()
        val previous = counted.minus(Duration.ofDays(21))
        runBlocking {
            app.repository.save(car)
            app.repository.dealtWith(car.id, counted, Status.ACTIVE, null, null)
        }
        try {
            AlertNotifications.resetNotice(context, car, garage, previous)
            val about = { cardsOn(AlertNotifications.CHANNEL_NET).map { it.notification }.filter { car.text in it.title() } }
            waitFor("no reset card came") { about().isNotEmpty() }
            context.sendBroadcast(
                Intent(context, AlertActionReceiver::class.java)
                    .setAction(AlertActionReceiver.ACTION_CONFIRM_RESET)
                    .setData(ReminderScheduler.reminderUri(car.id)),
            )
            waitFor("the card stayed") { about().isEmpty() }
            assertEquals(
                "the count stays where the reset put it",
                counted.toEpochMilli(),
                runBlocking { app.repository.get(car.id) }?.lastDealtAt?.toEpochMilli(),
            )
        } finally {
            AlertNotifications.cancelReset(context, car.id)
            runBlocking { app.repository.delete(car.id) }
        }
    }

    private fun waitFor(said: String, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!until()) {
            check(System.currentTimeMillis() < deadline) { said }
            Thread.sleep(100)
        }
    }

    private companion object {
        /** How the net always arrives: a card in the shade and nothing else. */
        val QUIET = FiringPlan(fullScreen = false, notification = true, sound = false, vibrate = false)
    }
}
