package dev.rwilco.alarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.model.Presence
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/**
 * Nothing rings twice on its own.
 *
 * A reminder that has rung and not been dealt with sits on Home as overdue — that is the app
 * telling the truth. What it must never do is ring again by itself, and the paths that could
 * make it are the ones nobody watches: the safety-net worker every six hours, the re-arm after
 * a reboot, a stray alarm delivered by a system that had already been told to forget it. This
 * drives all of them against a timer, which is the case with the shortest fuse.
 */
@RunWith(AndroidJUnit4::class)
class FiringOnceTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RwilcoApplication
    private val id = "fire-once"

    @Before
    fun empty() {
        runBlocking { app.repository.deleteAll() }
    }

    /**
     * Save a ten-minute timer whose moment is exactly now, and arm it the way the app does on
     * every change. Arming is what makes a firing legitimate, so a test that skips it is
     * testing a door nobody comes through.
     */
    private suspend fun saveAndArm(recurrence: Recurrence = Recurrence.None) {
        app.repository.save(timer(recurrence))
        app.scheduler.rearmAll()
    }

    /**
     * A ten-minute timer with two seconds left: far enough ahead to be armed, close enough that
     * the alarm is arriving now — which is the state a real one is in when it goes off.
     */
    private fun timer(recurrence: Recurrence = Recurrence.None): Reminder {
        val now = app.clock.instant()
        return Reminder(
            id = id,
            text = "Sacar el pan del horno",
            rules = listOf(TriggerRule(Trigger.Countdown(10, startedAt = now.minus(Duration.ofSeconds(598))))),
            recurrence = recurrence,
            createdAt = now.minus(Duration.ofMinutes(10)),
            updatedAt = now.minus(Duration.ofMinutes(10)),
        )
    }

    @Test
    fun a_timer_that_has_rung_and_been_left_alone_never_rings_again() = runBlocking {
        saveAndArm()
        app.firing.fire(id)
        val rang = app.repository.get(id)!!.lastFiredAt
        assertNotNull("it should have rung once", rang)

        // Everything that runs on its own afterwards, several times over: the safety net, the
        // re-arm after a reboot, and a stray alarm delivery for the moment already dealt with.
        repeat(3) {
            app.firing.rearmAndCatchUp()
            app.scheduler.rearmAll()
            app.firing.fire(id)
        }

        assertEquals("it rang more than once", rang, app.repository.get(id)!!.lastFiredAt)
        assertEquals(Status.ACTIVE, app.repository.get(id)!!.status)
    }

    @Test
    fun a_spent_timer_has_no_alarm_left_armed() = runBlocking {
        saveAndArm()
        app.firing.fire(id)
        app.scheduler.rearmAll()
        assertNull("a spent timer must leave nothing armed", app.repository.get(id)!!.armedFor)
    }

    @Test
    fun dealing_with_a_timer_finishes_it_and_it_stays_finished() = runBlocking {
        saveAndArm()
        app.firing.fire(id)
        app.firing.dismiss(id)
        assertEquals(Status.DONE, app.repository.get(id)!!.status)

        repeat(3) {
            app.firing.rearmAndCatchUp()
            app.firing.fire(id)
        }
        assertEquals("a finished timer came back", Status.DONE, app.repository.get(id)!!.status)
    }

    @Test
    fun a_timer_with_a_recurrence_comes_back_once_and_only_when_it_is_dealt_with() = runBlocking {
        saveAndArm(Recurrence.After(6, RecurrenceUnit.HOURS))
        app.firing.fire(id)
        val rang = app.repository.get(id)!!.lastFiredAt

        // Not dealt with: nothing new is armed and nothing rings again.
        repeat(2) { app.firing.rearmAndCatchUp() }
        assertEquals(rang, app.repository.get(id)!!.lastFiredAt)

        // Dealt with: it stays, and the next moment is six hours from the dealing.
        val before = app.clock.instant()
        app.firing.dismiss(id)
        val after = app.repository.get(id)!!
        assertEquals(Status.ACTIVE, after.status)
        assertNotNull(after.lastDealtAt)
        val armed = after.armedFor
        assertNotNull("a recurrence should have armed the next one", armed)
        val gap = Duration.between(before, armed)
        assertTrue("six hours from the dealing, not $gap", gap.toMinutes() in 350..370)
    }

    /**
     * The one that got away until 0.7.5.
     *
     * A recurrence counts from the moment it was DEALT WITH, so a reminder that rings and is
     * ignored has an anchor that does not move. Its moment stayed "next", the scheduler armed it
     * again, and an alarm in the past arrives at once — which is not a reminder ringing twice
     * but a reminder ringing until somebody makes it stop. This drives the second round, which
     * is the one nothing was watching.
     */
    @Test
    fun a_recurrence_rung_a_second_time_and_ignored_does_not_ring_a_third() = runBlocking {
        saveAndArm(Recurrence.After(6, RecurrenceUnit.HOURS))
        app.firing.fire(id)
        app.firing.dismiss(id)

        // Dealt with, so it came back. Wind its next moment onto the doorstep and let it ring.
        val dealt = app.repository.get(id)!!
        val due = app.clock.instant().minus(Duration.ofSeconds(1))
        app.repository.save(dealt.copy(lastDealtAt = due.minus(Duration.ofHours(6))))
        app.scheduler.rearmAll()
        app.firing.fire(id)
        val rangAgain = app.repository.get(id)!!.lastFiredAt
        assertNotNull("the recurrence should have come back round", rangAgain)

        // And now nobody answers. Everything that runs on its own must leave it alone.
        repeat(3) {
            app.firing.rearmAndCatchUp()
            app.scheduler.rearmAll()
            app.firing.fire(id)
        }
        assertEquals("it rang on its own after the second round", rangAgain, app.repository.get(id)!!.lastFiredAt)
        assertNull("a moment that has rung must leave nothing armed", app.repository.get(id)!!.armedFor)
        assertEquals(Status.ACTIVE, app.repository.get(id)!!.status)
    }

    /**
     * A place is the one firing with no armed moment of its own. Under "cualquiera" the armed
     * moment belongs to whatever ELSE the reminder is waiting for — and recording the arrival
     * against it would mark that appointment spent before it ever came.
     */
    @Test
    fun arriving_somewhere_does_not_spend_an_appointment_that_has_not_happened() = runBlocking {
        val now = app.clock.instant()
        val appointment = now.plus(Duration.ofHours(5))
        app.repository.save(
            Reminder(
                id = id,
                text = "Llamar a Marta",
                rules = listOf(
                    TriggerRule(Trigger.Location(40.4168, -3.7038, 200, Presence.INSIDE, "Casa")),
                    TriggerRule(Trigger.AtDateTime(LocalDateTime.ofInstant(appointment, app.clock.zone))),
                ),
                createdAt = now,
                updatedAt = now,
            ),
        )
        app.scheduler.rearmAll()
        assertNotNull("the appointment should be armed", app.repository.get(id)!!.armedFor)

        // Home, hours before the appointment.
        app.firing.fire(id, ruleIndex = 0)
        app.scheduler.rearmAll()

        val after = app.repository.get(id)!!
        assertNotNull("the appointment lost its alarm to the arrival", after.armedFor)
        assertTrue(
            "the ring was recorded against the appointment instead of the arrival",
            after.lastFiredAt!! < appointment,
        )
    }

    @Test
    fun a_reminder_paused_after_ringing_does_not_ring_while_paused() = runBlocking {
        saveAndArm(Recurrence.After(1, RecurrenceUnit.HOURS))
        app.firing.fire(id)
        app.repository.setStatus(id, Status.PAUSED)
        val rang = app.repository.get(id)!!.lastFiredAt

        repeat(2) {
            app.firing.rearmAndCatchUp()
            app.firing.fire(id)
        }
        assertEquals("a paused reminder rang", rang, app.repository.get(id)!!.lastFiredAt)
        assertNull(app.repository.get(id)!!.armedFor)
    }

    private fun assertNotNull(message: String, value: Instant?) = assertTrue(message, value != null)
}
