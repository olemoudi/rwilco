package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * A pause lifted on a reminder that comes back on a span (2026-11-09, a beta tester's report):
 * "cada 3 horas", paused, resumed the next day, rang yesterday's moment the second it was resumed
 * and asked for a "hecho" before the next three hours could start. What fell due during the pause
 * is not owed: the span starts again from the resume.
 *
 * Calendar: 2026-11-03 is a Tuesday.
 */
class ResumeSpanTest {

    private val every3h = Recurrence.After(3, RecurrenceUnit.HOURS, RecurrenceFrom.DEALT)
    private val created = local(2026, 11, 3, 9, 0)
    private val pausedAt = local(2026, 11, 3, 13, 0)
    private val resumedAt = local(2026, 11, 4, 10, 0)

    private fun row(vararg rules: TriggerRule, recurrence: Recurrence = every3h) = Reminder(
        id = "resume",
        text = "x",
        rules = rules.toList(),
        recurrence = recurrence,
        createdAt = created,
        updatedAt = created,
    )

    /** What `ReminderRepository.setStatus` writes on pause, for anything that is not a routine. */
    private fun Reminder.pause(at: Instant) = copy(status = Status.PAUSED, pausedAt = at, armedFor = null, armedRule = null, updatedAt = at)

    /** And on resume: the armed moment gone during the pause dropped, the resume stamped. */
    private fun Reminder.resume(at: Instant) = copy(
        status = Status.ACTIVE,
        pausedAt = null,
        resumedAt = at,
        armedFor = armedFor?.takeIf { it > at },
        armedRule = armedRule.takeIf { armedFor?.let { a -> a > at } == true },
        updatedAt = at,
    )

    /** Rung at noon and answered: the next one is due at 15:00 when the pause begins at 13:00. */
    private fun doneAtNoon(reminder: Reminder): Reminder {
        val phone = Simulation(reminder, created)
        val ring = phone.step { Simulation.Deal.Done }
        assertEquals(local(2026, 11, 3, 12, 0), ring?.at)
        return phone.reminder
    }

    @Test
    fun `cada 3 h done, paused, resumed the next day rings three hours after the resume and not at once`() {
        val resumed = doneAtNoon(row()).pause(pausedAt).resume(resumedAt)
        val wake = nextWake(resumed, resumedAt, zone, defaultTime)
        assertEquals(local(2026, 11, 4, 13, 0), wake?.at, "yesterday's 15:00 is not owed")
        assertEquals(local(2026, 11, 4, 13, 0), nextFire(resumed, resumedAt, zone, defaultTime)?.moment, "and Home counts down to the same")
        val phone = Simulation(resumed, resumedAt)
        val rings = phone.run(until = local(2026, 11, 4, 17, 0)) { Simulation.Deal.Done }
        assertEquals(listOf(local(2026, 11, 4, 13, 0), local(2026, 11, 4, 16, 0)), rings.map { it.at }, "and on from there as before")
    }

    @Test
    fun `counted from the ring it starts again from the resume too`() {
        val resumed = doneAtNoon(row(recurrence = every3h.copy(from = RecurrenceFrom.RANG))).pause(pausedAt).resume(resumedAt)
        assertEquals(local(2026, 11, 4, 13, 0), nextWake(resumed, resumedAt, zone, defaultTime)?.at)
    }

    @Test
    fun `a countdown that has had its say hands the span the same fresh start`() {
        val resumed = doneAtNoon(row(TriggerRule(Trigger.Countdown(180, created)))).pause(pausedAt).resume(resumedAt)
        assertEquals(local(2026, 11, 4, 13, 0), nextWake(resumed, resumedAt, zone, defaultTime)?.at)
        assertEquals(local(2026, 11, 4, 13, 0), nextFire(resumed, resumedAt, zone, defaultTime)?.moment)
    }

    @Test
    fun `a span in days lands a span after the resume, on its hour`() {
        val daily = Recurrence.After(1, RecurrenceUnit.DAYS, RecurrenceFrom.DEALT)
        val resumed = doneAtNoon(row(TriggerRule(Trigger.Countdown(180, created)), recurrence = daily))
            .pause(pausedAt)
            .resume(local(2026, 11, 6, 10, 0))
        val next = nextWake(resumed, local(2026, 11, 6, 10, 0), zone, defaultTime)?.at
        assertEquals(local(2026, 11, 7, DEFAULT_DAY_START.hour, DEFAULT_DAY_START.minute), next, "Wednesday's morning went by in the pause")
    }

    @Test
    fun `a pause lifted before the span is up keeps the moment it had`() {
        val resumed = doneAtNoon(row()).pause(pausedAt).resume(local(2026, 11, 3, 14, 0))
        assertEquals(local(2026, 11, 3, 15, 0), nextWake(resumed, local(2026, 11, 3, 14, 0), zone, defaultTime)?.at)
    }

    @Test
    fun `a ring left unanswered before the pause still waits for its answer and comes back a span after the resume`() {
        val phone = Simulation(row(), created)
        phone.step { Simulation.Deal.Ignore }
        val resumed = phone.reminder.pause(pausedAt).resume(resumedAt)
        assertNull(nextFire(resumed, resumedAt, zone, defaultTime), "Home keeps it where an unanswered ring belongs")
        assertEquals(local(2026, 11, 4, 13, 0), nextWake(resumed, resumedAt, zone, defaultTime)?.at)
    }

    @Test
    fun `a routine keeps its frozen count, overdue before the pause and overdue after it`() {
        // The owner's call (2026-09-07): a routine's pause freezes the count; one that was owed
        // when it was paused is owed when it comes back. Not this fix's to change.
        val routine = row(recurrence = Recurrence.Since(3, RecurrenceUnit.HOURS)).copy(lastDealtAt = local(2026, 11, 3, 9, 0))
        val owed = local(2026, 11, 3, 12, 0)
        val paused = routine.pause(pausedAt)
        val resumed = paused.copy(status = Status.ACTIVE, pausedAt = null, lastDealtAt = paused.routineAnchorAfterPause(resumedAt), resumedAt = resumedAt)
        val wake = nextWake(resumed, resumedAt, zone, defaultTime)
        assertTrue(wake != null && wake.at < resumedAt, "still owed: ${wake?.at} (it was owed at $owed)")
    }
}
