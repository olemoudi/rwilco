package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * "Todos" for events can only mean the last of them to happen, so what the model has to get
 * right is: which one is armed next (each in turn, to be noticed), which one rings (the last),
 * and what a firing means before the set is complete (a note, not a ring).
 */
class RuleMatchTest {

    private val atSix = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 18, 0))
    private val atNine = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 0))
    private val home = Trigger.Location(40.4, -3.7, 200, Transition.ENTER, "Casa")

    private fun reminder(vararg triggers: Trigger, match: RuleMatch, fired: Set<Int> = emptySet()) =
        Fixtures.reminder(*triggers).copy(ruleMatch = match, firedRules = fired)

    @Test
    fun `any rings with the first, all rings with the last`() {
        val any = reminder(atSix, atNine, match = RuleMatch.ANY)
        val all = reminder(atSix, atNine, match = RuleMatch.ALL)
        assertEquals(local(2026, 8, 27, 18, 0), (nextFire(any, now, zone, defaultTime) as NextFire.Scheduled).at)
        assertEquals(local(2026, 8, 27, 21, 0), (nextFire(all, now, zone, defaultTime) as NextFire.Scheduled).at)
    }

    @Test
    fun `all wakes at each moment in turn, because each has to be noticed`() {
        val all = reminder(atSix, atNine, match = RuleMatch.ALL)
        assertEquals(Wake(local(2026, 8, 27, 18, 0), 0), nextWake(all, now, zone, defaultTime))
        // Six o'clock has happened and been written down: the next thing to wake for is nine.
        val afterSix = all.copy(firedRules = setOf(0))
        assertEquals(Wake(local(2026, 8, 27, 21, 0), 1), nextWake(afterSix, now, zone, defaultTime))
    }

    @Test
    fun `what is already ticked off is not waited on again`() {
        val all = reminder(atSix, atNine, match = RuleMatch.ALL, fired = setOf(1))
        assertEquals(listOf(0), all.pendingRules())
        assertEquals(local(2026, 8, 27, 18, 0), (nextFire(all, now, zone, defaultTime) as NextFire.Scheduled).at)
    }

    @Test
    fun `a place among them means there is no date to give`() {
        val all = reminder(atNine, home, match = RuleMatch.ALL)
        val next = nextFire(all, now, zone, defaultTime)
        assertEquals(NextFire.WhenAt(home), next, "the ring waits on the place, whenever that is")
    }

    @Test
    fun `a rule that can never happen again is a set that never completes`() {
        val gone = Trigger.AtDateTime(LocalDateTime.of(2020, 1, 1, 9, 0))
        assertNull(nextFire(reminder(gone, atNine, match = RuleMatch.ALL), now, zone, defaultTime))
        // Under ANY the other one still rings it.
        assertEquals(
            local(2026, 8, 27, 21, 0),
            (nextFire(reminder(gone, atNine, match = RuleMatch.ANY), now, zone, defaultTime) as NextFire.Scheduled).at,
        )
    }

    @Test
    fun `one rule is one rule, whatever the toggle says`() {
        val all = reminder(atNine, match = RuleMatch.ALL)
        assertEquals(local(2026, 8, 27, 21, 0), (nextFire(all, now, zone, defaultTime) as NextFire.Scheduled).at)
        assertEquals(FiringOutcome.Ring, outcomeOfFiring(all, ruleIndex = 0))
    }

    @Test
    fun `under all a moment is a note until it is the last one`() {
        val all = reminder(atSix, atNine, home, match = RuleMatch.ALL)
        assertEquals(FiringOutcome.Wait(setOf(0)), outcomeOfFiring(all, ruleIndex = 0))
        assertEquals(FiringOutcome.Wait(setOf(1, 2)), outcomeOfFiring(all.copy(firedRules = setOf(2)), ruleIndex = 1))
        assertEquals(FiringOutcome.Ring, outcomeOfFiring(all.copy(firedRules = setOf(0, 2)), ruleIndex = 1))
    }

    @Test
    fun `under any every moment rings, and so does a snooze under either`() {
        val any = reminder(atSix, atNine, match = RuleMatch.ANY)
        assertEquals(FiringOutcome.Ring, outcomeOfFiring(any, ruleIndex = 0))
        assertEquals(FiringOutcome.Ring, outcomeOfFiring(reminder(atSix, atNine, match = RuleMatch.ALL), ruleIndex = null))
    }

    @Test
    fun `an index from a rule that has since been deleted does not block the set`() {
        // Edited from three rules down to two while the third was already ticked off.
        val all = reminder(atSix, atNine, match = RuleMatch.ALL, fired = setOf(2))
        assertEquals(listOf(0, 1), all.pendingRules())
        assertEquals(FiringOutcome.Wait(setOf(0)), outcomeOfFiring(all, ruleIndex = 0))
        assertEquals(FiringOutcome.Ring, outcomeOfFiring(all.copy(firedRules = setOf(0, 2)), ruleIndex = 1))
    }

    @Test
    fun `dealing with a firing starts the round again`() {
        val everyDay = Trigger.AtTime(java.time.LocalTime.of(9, 0), java.time.DayOfWeek.entries.toSet())
        val all = reminder(everyDay, home, match = RuleMatch.ALL, fired = setOf(0, 1)).copy(recurrence = Recurrence.ByTrigger)
        assertEquals(Status.ACTIVE, statusAfterDismissal(all, now, zone, defaultTime))
        // And without being asked to keep going, dealing with it is the end of it.
        assertEquals(Status.DONE, statusAfterDismissal(all.copy(recurrence = Recurrence.None), now, zone, defaultTime))
    }

    @Test
    fun `the snooze still outranks everything`() {
        val all = reminder(atSix, atNine, match = RuleMatch.ALL).copy(snoozedUntil = local(2026, 8, 27, 16, 0))
        assertEquals(Wake(local(2026, 8, 27, 16, 0), null), nextWake(all, now, zone, defaultTime))
    }

    @Test
    fun `a whole round under all, wound forward and started again`() {
        // "Cuando llegue a casa y sean más de las nueve": the two halves happen hours apart and
        // in either order, the phone may be restarted in between, and only the second one rings.
        // Every step below is what some receiver actually does — the point is the sequence.
        // The hour has to be a repeating one, not a date: a one-shot moment that has passed can
        // never complete the set again, and the app is right to call that finished (below).
        val everyNight = Trigger.AtTime(LocalTime.of(21, 0), DayOfWeek.entries.toSet())
        var reminder = reminder(home, everyNight, match = RuleMatch.ALL).copy(recurrence = Recurrence.ByTrigger)
        val arrived = local(2026, 8, 27, 18, 30)

        // Nothing is armed for the place; the alarm is for nine o'clock, which is rule 1.
        assertEquals(Wake(local(2026, 8, 27, 21, 0), 1), nextWake(reminder, now, zone, defaultTime))

        // Home at half past six. A note, not a ring — and the alarm for nine stays exactly where
        // it was, because the set is not complete.
        assertEquals(FiringOutcome.Wait(setOf(0)), outcomeOfFiring(reminder, ruleIndex = 0))
        reminder = reminder.copy(firedRules = setOf(0))
        assertEquals(Wake(local(2026, 8, 27, 21, 0), 1), nextWake(reminder, arrived, zone, defaultTime))

        // The phone restarts. Re-arming reads the same row and must reach the same conclusion:
        // what was written down survives, so the evening is not started over.
        assertEquals(Wake(local(2026, 8, 27, 21, 0), 1), nextWake(reminder, local(2026, 8, 27, 20, 0), zone, defaultTime))

        // Nine o'clock: the last of them, so this one rings.
        assertEquals(FiringOutcome.Ring, outcomeOfFiring(reminder, ruleIndex = 1))
        reminder = reminder.copy(firedRules = setOf(0, 1), lastFiredAt = local(2026, 8, 27, 21, 0))
        assertNull(nextWake(reminder, local(2026, 8, 27, 21, 0), zone, defaultTime), "a completed set waits for a person")

        // Dealt with. The round is over, and next week starts from nothing again — half of it
        // having happened last Thursday is not a head start.
        val dealtAt = local(2026, 8, 27, 21, 5)
        assertEquals(
            Status.ACTIVE,
            statusAfterDismissal(reminder, dealtAt, zone, defaultTime),
            "a place can always come round again, and this one was asked to",
        )
        reminder = reminder.copy(firedRules = emptySet(), lastDealtAt = dealtAt, snoozedUntil = null)
        assertEquals(
            local(2026, 8, 28, 21, 0),
            nextWake(reminder, dealtAt, zone, defaultTime)?.at,
            "tomorrow's nine, with the arriving home to be earned all over again",
        )
    }

    @Test
    fun `a round whose other half can never happen again is finished, however it was asked to repeat`() {
        // "Al llegar a casa, y el martes a las nueve" — the Tuesday is gone. Asking it to repeat
        // cannot conjure a second Tuesday, and leaving it active would leave a reminder waiting
        // for a set nothing can complete.
        val spent = reminder(home, atNine, match = RuleMatch.ALL, fired = setOf(0, 1))
            .copy(recurrence = Recurrence.ByTrigger, lastFiredAt = local(2026, 8, 27, 21, 0))

        assertEquals(Status.DONE, statusAfterDismissal(spent, local(2026, 8, 27, 21, 5), zone, defaultTime))
    }

    @Test
    fun `dealing with half a round is still the end of the round`() {
        // The alert screen can be reached from the notification of the FIRST rule under ALL —
        // there is no ring, but there is a row on Home with a "hecho" on it. Tapping it has to
        // clear what was written down, or the reminder comes back already half satisfied.
        val half = reminder(atSix, atNine, match = RuleMatch.ALL, fired = setOf(0))
        assertEquals(Wake(local(2026, 8, 27, 21, 0), 1), nextWake(half, now, zone, defaultTime))

        val cleared = half.copy(firedRules = emptySet())
        assertEquals(Wake(local(2026, 8, 27, 18, 0), 0), nextWake(cleared, now, zone, defaultTime))
    }
}
