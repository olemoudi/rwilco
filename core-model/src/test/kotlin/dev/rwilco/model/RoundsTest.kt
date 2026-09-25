package dev.rwilco.model

import dev.rwilco.model.Fixtures.local
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * A reminder's history read as rounds, and the streak worked out of them. The rule is the
 * owner's: a streak breaks only by not doing it — snoozing and then doing it does not.
 */
class RoundsTest {

    private fun day(d: Int, hour: Int = 9, minute: Int = 0): Instant = local(2026, 8, d, hour, minute)
    private fun e(kind: FiringKind, at: Instant, detail: String? = null) = FiringEvent(kind, at, detail = detail)
    private fun rang(at: Instant) = e(FiringKind.RANG, at)
    private fun dealt(at: Instant) = e(FiringKind.DEALT, at)
    private fun snoozed(at: Instant) = e(FiringKind.SNOOZED, at, at.plusSeconds(600).toString())

    /** A daily reminder rung and answered on each of [days]. */
    private fun answered(vararg days: Int) = days.flatMap { listOf(rang(day(it)), dealt(day(it, 9, 5))) }

    @Test
    fun `a ring answered with hecho is a round done at the first ring`() {
        val round = rounds(answered(1), RoundShape.REPEATING).single()
        assertEquals(RoundEnd.DONE, round.end)
        assertTrue(round.firstTime)
        assertTrue(round.rang)
        assertEquals(day(1, 9, 5), round.endedAt)
    }

    @Test
    fun `a snooze and its ring are one round, done but not at the first ring`() {
        val events = listOf(rang(day(1)), snoozed(day(1, 9, 1)), rang(day(1, 9, 11)), dealt(day(1, 9, 12)))
        val round = rounds(events, RoundShape.REPEATING).single()
        assertEquals(RoundEnd.DONE, round.end)
        assertEquals(1, round.snoozes)
        assertFalse(round.firstTime)
        assertTrue(round.keepsStreak, "putting it off and then doing it does not break a streak")
    }

    @Test
    fun `a ring overtaken by the next ring with no answer is a round not done`() {
        val events = listOf(rang(day(1)), rang(day(2)), dealt(day(2, 9, 5)))
        val ends = rounds(events, RoundShape.REPEATING).map { it.end }
        assertEquals(listOf(RoundEnd.NOT_DONE, RoundEnd.DONE), ends)
    }

    @Test
    fun `a snooze that rang and was left is overtaken like any ring`() {
        // Put off, the snooze rang, nobody answered that either: the next day's ring ends it.
        val events = listOf(rang(day(1)), snoozed(day(1, 9, 1)), rang(day(1, 9, 11)), rang(day(2)))
        val ends = rounds(events, RoundShape.REPEATING).map { it.end }
        assertEquals(listOf(RoundEnd.NOT_DONE, RoundEnd.OPEN), ends)
    }

    @Test
    fun `a one-off rung twice before its hecho is one round, done but not at the first ask`() {
        // A place crossed twice rings twice; it is still one thing to do — asked twice.
        val events = listOf(rang(day(1)), rang(day(2)), dealt(day(2, 9, 5)))
        val round = rounds(events, RoundShape.ONE_OFF).single()
        assertEquals(RoundEnd.DONE, round.end)
        assertEquals(2, round.rings)
        assertFalse(round.firstTime)
        assertTrue(round.keepsStreak)
    }

    @Test
    fun `a span from the hecho asked again is the same round, not a miss`() {
        // "Al llegar a casa, vuelve cada día": ignored at six, rung again at the second arrival
        // at eight, done then. The rules go on asking until the answer; that is one round.
        val events = listOf(rang(day(1, 18)), rang(day(1, 20)), dealt(day(1, 20).plusSeconds(300)))
        val all = rounds(events, RoundShape.UNTIL_DONE)
        assertEquals(listOf(RoundEnd.DONE), all.map { it.end })
        assertEquals(1, currentStreak(all))
        // It fails the way a one-off does: the net's word, and still nothing.
        val chased = listOf(rang(day(1, 18)), e(FiringKind.NET, day(2, 12), NetWord.LET_GO.name))
        assertEquals(RoundEnd.NOT_DONE, rounds(chased, RoundShape.UNTIL_DONE).single().end)
    }

    @Test
    fun `a snooze taken back does not hide the ring it was about`() {
        // Rang, put off, the snooze taken back (which writes no line), the net's word, then the
        // next day's ring: the first round was not done.
        val events = listOf(
            rang(day(1)),
            snoozed(day(1, 9, 1)),
            e(FiringKind.NET, day(1, 12), NetWord.LET_GO.name),
            rang(day(2)),
        )
        assertEquals(listOf(RoundEnd.NOT_DONE, RoundEnd.OPEN), rounds(events, RoundShape.REPEATING).map { it.end })
    }

    @Test
    fun `a hecho with nothing ringing is a round done ahead`() {
        val round = rounds(listOf(dealt(day(1))), RoundShape.REPEATING).single()
        assertTrue(round.ahead)
        assertTrue(round.firstTime)
    }

    @Test
    fun `a skipped round neither adds to the streak nor breaks it`() {
        val events = answered(1, 2) + e(FiringKind.SKIPPED, day(3, 8, 0)) + answered(4)
        val all = rounds(events, RoundShape.REPEATING)
        assertEquals(RoundEnd.SKIPPED, all[2].end)
        assertEquals(3, currentStreak(all))
        assertEquals(3, bestStreak(all))
    }

    @Test
    fun `a lapsed round neither adds nor breaks`() {
        // A deadline only lapses when nothing rang: the set never came together.
        val events = answered(1) + e(FiringKind.LAPSED, day(2, 22, 0)) + answered(3)
        val all = rounds(events, RoundShape.REPEATING)
        assertEquals(listOf(RoundEnd.DONE, RoundEnd.SKIPPED, RoundEnd.DONE), all.map { it.end })
        assertEquals(2, currentStreak(all))
    }

    @Test
    fun `the net's word about an unanswered ring counts the open round as not done until a hecho arrives`() {
        val chased = listOf(rang(day(1)), e(FiringKind.NET, day(1, 12, 0), NetWord.LET_GO.name))
        assertEquals(RoundEnd.NOT_DONE, rounds(chased, RoundShape.ONE_OFF).single().end)
        val doneAfter = rounds(chased + dealt(day(1, 13, 0)), RoundShape.ONE_OFF).single()
        assertEquals(RoundEnd.DONE, doneAfter.end, "doing it never breaks it")
        assertTrue(doneAfter.chased)
        assertFalse(doneAfter.firstTime)
        assertTrue(doneAfter.keepsStreak)
    }

    @Test
    fun `the net's other words are not the person's doing`() {
        for (word in listOf(NetWord.NEVER_RANG, NetWord.CANNOT_RING, NetWord.WAITING)) {
            val events = listOf(rang(day(1)), e(FiringKind.NET, day(1, 12, 0), word.name))
            assertEquals(RoundEnd.OPEN, rounds(events, RoundShape.ONE_OFF).single().end, word.name)
        }
    }

    @Test
    fun `a ring still waiting is open and counts neither way`() {
        val all = rounds(answered(1, 2) + rang(day(3)), RoundShape.REPEATING)
        assertEquals(RoundEnd.OPEN, all.last().end)
        assertEquals(2, currentStreak(all))
    }

    @Test
    fun `a routine done before its deadline rang is done on time`() {
        val round = rounds(listOf(dealt(day(3))), RoundShape.ROUTINE).single()
        assertTrue(round.keepsStreak)
        assertTrue(round.firstTime)
    }

    @Test
    fun `a routine whose deadline rang before the hecho is done late and breaks the streak`() {
        val events = listOf(dealt(day(1)), dealt(day(8)), rang(day(15)), dealt(day(16)))
        val all = rounds(events, RoundShape.ROUTINE)
        assertTrue(all.last().late)
        assertEquals(RoundEnd.DONE, all.last().end)
        assertEquals(0, currentStreak(all))
        assertEquals(2, bestStreak(all))
    }

    @Test
    fun `a routine overdue and still owed has its streak broken already`() {
        val all = rounds(listOf(dealt(day(1)), dealt(day(8)), rang(day(15))), RoundShape.ROUTINE)
        assertEquals(RoundEnd.NOT_DONE, all.last().end)
        assertEquals(0, currentStreak(all))
    }

    @Test
    fun `a hecho dated before the ring it answers takes that ring back`() {
        // Monday's deadline rang; on Tuesday, "lo hice el sábado" — written after the ring,
        // dated before it. It was never overdue.
        val events = listOf(dealt(day(15)), rang(day(22)), dealt(day(20, 11, 0)))
        val last = rounds(events, RoundShape.ROUTINE).last()
        assertFalse(last.late)
        assertFalse(last.rang)
        assertEquals(day(20, 11, 0), last.endedAt)
    }

    @Test
    fun `a place's reset counts as a hecho and an undone reset does not`() {
        val events = listOf(
            dealt(day(1)),
            e(FiringKind.RESET, day(10)),
            e(FiringKind.UNRESET, day(10, 9, 5)),
            e(FiringKind.RESET, day(12)),
        )
        val stats = reminderStats(events, RoundShape.ROUTINE)
        assertEquals(2, stats.done)
        assertEquals(day(12), stats.lastDone)
    }

    @Test
    fun `todavía no is not a snooze`() {
        val events = listOf(
            e(FiringKind.ASKED, day(5)),
            e(FiringKind.SNOOZED, day(5, 9, 1), LATER_DETAIL),
            dealt(day(6)),
        )
        val stats = reminderStats(events, RoundShape.ROUTINE)
        assertEquals(0, stats.snoozes)
        assertEquals(1, stats.firstTime)
    }

    @Test
    fun `contacts and reminders that ask for nothing never fail`() {
        val events = listOf(rang(day(1)), rang(day(2)), e(FiringKind.NET, day(2, 12, 0), NetWord.LET_GO.name), snoozed(day(3)), dealt(day(4)))
        val stats = reminderStats(events, RoundShape.QUIET)
        assertEquals(1, stats.done)
        assertEquals(0, stats.notDone)
        assertEquals(0, stats.snoozes)
        assertEquals(0, stats.currentStreak)
        assertEquals(0, stats.bestStreak)
    }

    @Test
    fun `the current streak runs back from the newest closed round`() {
        val events = answered(1) + rang(day(2)) + answered(3, 4, 5)
        val all = rounds(events, RoundShape.REPEATING)
        assertEquals(3, currentStreak(all))
    }

    @Test
    fun `the best streak is the longest run anywhere`() {
        val events = answered(1, 2, 3, 4) + rang(day(5)) + answered(6, 7)
        val all = rounds(events, RoundShape.REPEATING)
        assertEquals(4, bestStreak(all))
        assertEquals(2, currentStreak(all))
    }

    @Test
    fun `the numbers of a reminder come out of its rounds`() {
        val events = answered(1, 2) +
            listOf(rang(day(3)), snoozed(day(3, 9, 1)), rang(day(3, 9, 11)), dealt(day(3, 9, 12))) +
            rang(day(4)) +
            answered(5)
        val stats = reminderStats(events, RoundShape.REPEATING)
        assertEquals(4, stats.done)
        assertEquals(1, stats.notDone)
        assertEquals(1, stats.snoozes)
        assertEquals(3, stats.firstTime)
        assertEquals(1, stats.currentStreak)
        assertEquals(3, stats.bestStreak)
        assertEquals(day(1), stats.since)
        assertEquals(day(5, 9, 5), stats.lastDone)
        assertEquals(
            listOf(RoundMark.FIRST_TIME, RoundMark.FIRST_TIME, RoundMark.DONE, RoundMark.NOT_DONE, RoundMark.FIRST_TIME),
            stats.recent,
        )
    }

    @Test
    fun `the mean gap is between consecutive hechos, a dated one where it belongs`() {
        // The one said "on the 8th" was written last and still sits between the 1st and the 15th.
        val events = listOf(dealt(day(1)), dealt(day(15)), dealt(day(8)))
        assertEquals(Duration.ofDays(7), reminderStats(events, RoundShape.ROUTINE).meanGap)
        assertNull(reminderStats(listOf(dealt(day(1))), RoundShape.ROUTINE).meanGap)
    }

    @Test
    fun `the strip keeps the last twenty rounds`() {
        val events = answered(*(1..25).toList().toIntArray())
        assertEquals(RECENT_ROUNDS, reminderStats(events, RoundShape.REPEATING).recent.size)
    }

    @Test
    fun `nothing written is nothing to say`() {
        val stats = reminderStats(emptyList(), RoundShape.REPEATING)
        assertEquals(0, stats.done)
        assertNull(stats.since)
        assertNull(stats.lastDone)
    }

    @Test
    fun `a reminder is read by what it is`() {
        val ringing = Fixtures.reminder(Trigger.TimeOfDay(java.time.LocalTime.of(9, 0)))
        assertEquals(RoundShape.ONE_OFF, ringing.roundShape)
        // A to-do with nothing that can ring: its hechos count, and it is never "ahead" of anything.
        assertEquals(RoundShape.QUIET, Fixtures.reminder().roundShape)
        assertEquals(RoundShape.UNTIL_DONE, ringing.copy(recurrence = Recurrence.After(1, RecurrenceUnit.DAYS)).roundShape)
        assertEquals(RoundShape.UNTIL_DONE, Fixtures.reminder().copy(recurrence = Recurrence.After(1, RecurrenceUnit.DAYS)).roundShape)
        assertEquals(RoundShape.REPEATING, ringing.copy(recurrence = Recurrence.After(1, RecurrenceUnit.HOURS, RecurrenceFrom.RANG)).roundShape)
        assertEquals(RoundShape.REPEATING, ringing.copy(recurrence = Recurrence.ByTrigger).roundShape)
        val calendar = Recurrence.Calendar(Trigger.Repeat(startsOn = java.time.LocalDate.of(2026, 8, 1), unit = RepeatUnit.DAY))
        assertEquals(RoundShape.REPEATING, Fixtures.reminder().copy(recurrence = calendar).roundShape, "a calendar ringing its own dates")
        assertEquals(RoundShape.UNTIL_DONE, ringing.copy(recurrence = calendar).roundShape, "a calendar resting rules until the hecho")
        assertEquals(RoundShape.QUIET, ringing.copy(recurrence = Recurrence.After(1, RecurrenceUnit.DAYS), actions = emptySet()).roundShape)
        assertEquals(RoundShape.ROUTINE, ringing.copy(recurrence = Recurrence.Since(7, RecurrenceUnit.DAYS), actions = emptySet()).roundShape)
        assertEquals(RoundShape.QUIET, ringing.copy(recurrence = Recurrence.Since(90, RecurrenceUnit.DAYS), contactKind = ContactKind.WORK).roundShape)
    }
}
