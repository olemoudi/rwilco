package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalTime

/**
 * A set with a deadline, wound through the phone in memory: the round that completes rings,
 * the one that does not is let go without a sound, and the person's own answers outrank it.
 */
class DeadlineJourneyTest {

    private val home = Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa")
    private val atEight = Trigger.TimeOfDay(LocalTime.of(20, 0))
    private val evening = Deadline.Window(LocalTime.of(18, 0), LocalTime.of(22, 0))
    private val thursdayAfternoon = local(2026, 8, 27, 15, 0)

    /** Written at [at], with the window close a save writes (EditorViewModel.save). */
    private fun written(match: RuleMatch, deadline: Deadline, recurrence: Recurrence = Recurrence.None, at: Instant = thursdayAfternoon): Reminder {
        val row = Reminder(
            id = "r1",
            text = "Llamar a Marta",
            rules = listOf(TriggerRule(atEight), TriggerRule(home)),
            ruleMatch = match,
            deadline = deadline,
            recurrence = recurrence,
            createdAt = at,
            updatedAt = at,
        )
        return row.copy(expiresAt = row.roundExpiry(at, zone, defaultTime))
    }

    @Test
    fun `a window set that completes rings, and one that does not is let go at the close`() {
        val done = Simulation(written(RuleMatch.ALL, evening), thursdayAfternoon)
        done.now = local(2026, 8, 27, 19, 0)
        assertNull(done.arrive(1), "being at home is half the set")
        assertEquals(listOf(1), done.noted.map { it.ruleIndex })
        val rings = done.run(local(2026, 8, 28, 12, 0))
        assertEquals(listOf(local(2026, 8, 27, 20, 0)), rings.map { it.rangFor }, "eight o'clock completes it")
        assertTrue(done.lapses.isEmpty())
        assertNull(done.reminder.expiresAt, "a set that rang is not given up on")

        val alone = Simulation(written(RuleMatch.ALL, evening), thursdayAfternoon)
        assertTrue(alone.run(local(2026, 8, 28, 12, 0)).isEmpty(), "nobody came home")
        assertEquals(listOf(local(2026, 8, 27, 20, 0)), alone.noted.map { it.at }, "the hour was noted and waited on")
        assertEquals(listOf(local(2026, 8, 27, 22, 0)), alone.lapses)
        assertEquals(Status.DONE, alone.reminder.status)
        assertEquals(local(2026, 8, 27, 22, 0), alone.reminder.doneAt)
    }

    @Test
    fun `an arrival before the window opens does not count`() {
        val sim = Simulation(written(RuleMatch.ALL, evening), thursdayAfternoon)
        sim.now = local(2026, 8, 27, 17, 0)
        assertNull(sim.arrive(1))
        assertTrue(sim.noted.isEmpty(), "five in the afternoon is outside six to ten")
        sim.now = local(2026, 8, 27, 18, 30)
        assertNull(sim.arrive(1))
        assertEquals(listOf(1), sim.noted.map { it.ruleIndex })
    }

    @Test
    fun `a lapse on a reminder that comes back every day starts the next day's round`() {
        val sim = Simulation(written(RuleMatch.ALL, evening, Recurrence.After(1, RecurrenceUnit.DAYS)), thursdayAfternoon)
        assertTrue(sim.run(local(2026, 8, 29, 12, 0)).isEmpty())
        assertEquals(listOf(local(2026, 8, 27, 22, 0), local(2026, 8, 28, 22, 0)), sim.lapses)
        assertEquals(Status.ACTIVE, sim.reminder.status)
        assertEquals(local(2026, 8, 29, 22, 0), sim.reminder.expiresAt, "Saturday's round is under way")
        assertEquals(local(2026, 8, 28, 22, 0), sim.reminder.lastDealtAt, "the span counts from the last deadline")
    }

    @Test
    fun `a timer runs from the clock's moment, and being at home starts nothing`() {
        val hour = Deadline.Timer(60)
        val late = Simulation(written(RuleMatch.ALL, hour), thursdayAfternoon)
        assertNull(late.reminder.expiresAt, "nothing has happened yet")
        assertTrue(late.run(local(2026, 8, 28, 12, 0)).isEmpty())
        assertEquals(listOf(local(2026, 8, 27, 20, 0)), late.noted.map { it.at })
        assertEquals(listOf(local(2026, 8, 27, 21, 0)), late.lapses, "an hour after eight, nobody home")
        assertEquals(Status.DONE, late.reminder.status)

        val inTime = Simulation(written(RuleMatch.ALL, hour), thursdayAfternoon)
        assertTrue(inTime.run(local(2026, 8, 27, 20, 30)).isEmpty())
        assertEquals(local(2026, 8, 27, 21, 0), inTime.reminder.expiresAt, "the clock started at eight")
        inTime.now = local(2026, 8, 27, 20, 30)
        val ring = inTime.arrive(1)
        assertEquals(local(2026, 8, 27, 20, 30), ring?.rangFor, "home by half past: it rings")
        assertNull(inTime.reminder.expiresAt)

        val early = Simulation(written(RuleMatch.ALL, hour), thursdayAfternoon)
        early.now = local(2026, 8, 27, 15, 30)
        assertNull(early.arrive(1))
        assertNull(early.reminder.expiresAt, "being at home at half past three starts no clock")
        assertEquals(listOf(local(2026, 8, 27, 20, 0)), early.run(local(2026, 8, 28, 12, 0)).map { it.rangFor }, "and eight o'clock completes the set")
    }

    @Test
    fun `a phone asleep across the deadline lets the round go on waking`() {
        val sim = Simulation(written(RuleMatch.ALL, evening), thursdayAfternoon)
        assertTrue(sim.sleepUntil(local(2026, 8, 28, 9, 0)).isEmpty())
        assertEquals(listOf(0), sim.noted.map { it.ruleIndex }, "the hour it slept through is noted for what it was")
        assertEquals(listOf(local(2026, 8, 27, 22, 0)), sim.lapses)
        assertEquals(Status.DONE, sim.reminder.status)
        assertNull(sim.reminder.armedFor, "nothing is owed on a round that is over")
    }

    @Test
    fun `a snooze outranks the deadline`() {
        val sim = Simulation(written(RuleMatch.ALL, evening), thursdayAfternoon)
        sim.now = local(2026, 8, 27, 21, 50)
        sim.deal(Simulation.Deal.Later(Snooze.TEN_MINUTES))
        val rings = sim.run(local(2026, 8, 28, 12, 0))
        assertTrue(sim.lapses.isEmpty(), "the person said 'not now, then', and that is the answer")
        assertEquals(listOf(local(2026, 8, 27, 22, 0)), rings.map { it.rangFor }, "the snooze rings as it always did")
        assertEquals(Status.ACTIVE, sim.reminder.status)
    }

    @Test
    fun `a la vez with a window gives the set one evening`() {
        // Eleven at night is never between six and ten, so the folded rule has no moment and
        // the round's day is the day it was written: at ten the set is let go.
        val elevenAndHome = written(RuleMatch.TOGETHER, evening).let { row ->
            row.copy(rules = listOf(TriggerRule(Trigger.TimeOfDay(LocalTime.of(23, 0))), TriggerRule(home))).let { it.copy(expiresAt = it.roundExpiry(thursdayAfternoon, zone, defaultTime)) }
        }
        val sim = Simulation(elevenAndHome, thursdayAfternoon)
        assertEquals(local(2026, 8, 27, 22, 0), sim.reminder.expiresAt)
        assertTrue(sim.run(local(2026, 8, 28, 12, 0)).isEmpty())
        assertEquals(listOf(local(2026, 8, 27, 22, 0)), sim.lapses)
        assertEquals(Status.DONE, sim.reminder.status)
        // Eight at night is, and the phone — which this harness never doubts — is at home.
        val eightAndHome = Simulation(written(RuleMatch.TOGETHER, evening), thursdayAfternoon)
        assertEquals(listOf(local(2026, 8, 27, 20, 0)), eightAndHome.run(local(2026, 8, 28, 12, 0)).map { it.rangFor })
        assertTrue(eightAndHome.lapses.isEmpty())
    }
}
