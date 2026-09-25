package dev.rwilco.model

import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** Every reminder's rounds together: the Hechos screen's numbers, and the milestones they prove. */
class GlobalStatsTest {

    // Thursday 2026-08-27, 15:00.
    private val now = Fixtures.now
    private fun day(d: Int, hour: Int = 9): Instant = local(2026, 8, d, hour, 0)
    private fun rang(at: Instant) = FiringEvent(FiringKind.RANG, at)
    private fun dealt(at: Instant) = FiringEvent(FiringKind.DEALT, at)
    private fun subject(id: String, shape: RoundShape = RoundShape.REPEATING, status: Status = Status.ACTIVE) = StatsSubject(id, "text $id", shape, status)

    /** Rung and answered at nine on each of [days] of August. */
    private fun daily(days: IntRange) = days.flatMap { listOf(rang(day(it)), dealt(day(it, 10))) }

    @Test
    fun `hechos are counted by the day they were done, dated ones included`() {
        val history = mapOf(
            "a" to daily(20..27),
            // A routine told on the 27th it was done on the 25th: it counts on the 25th.
            "b" to listOf(dealt(day(10)), dealt(day(25))),
        )
        val subjects = mapOf("a" to subject("a"), "b" to subject("b", RoundShape.ROUTINE))
        val stats = globalStats(history, subjects, now, zone)
        assertEquals(DONE_CHART_DAYS, stats.byDay.size)
        assertEquals(1, stats.today)
        assertEquals(2, stats.byDay[DONE_CHART_DAYS - 3], "the 25th: one of each")
        assertEquals(8, stats.thisWeek, "21st to 27th: seven of a, one of b")
        assertEquals(1, stats.lastWeek, "14th to 20th: the 20th of a; the 10th is out")
        assertEquals(10, stats.hechos)
    }

    @Test
    fun `a running streak is named from three, longest first, and only while it is going`() {
        val history = mapOf(
            "long" to daily(1..27),
            "short" to daily(26..27),
            "mid" to daily(20..27),
            "paused" to daily(1..27),
        )
        val subjects = mapOf(
            "long" to subject("long"),
            "short" to subject("short"),
            "mid" to subject("mid"),
            "paused" to subject("paused", status = Status.PAUSED),
        )
        val streaks = globalStats(history, subjects, now, zone).streaks
        assertEquals(listOf("long" to 27, "mid" to 8), streaks.map { it.subject.id to it.count })
    }

    @Test
    fun `a reminder that never failed needs five closed rounds`() {
        val history = mapOf(
            "five" to daily(23..27),
            "four" to daily(24..27),
            "missed" to daily(20..25) + rang(day(26)) + daily(27..27),
            // A contact is never a debt, and never on this list either.
            "contact" to listOf(dealt(day(1)), dealt(day(5)), dealt(day(9)), dealt(day(13)), dealt(day(17)), dealt(day(21))),
        )
        val subjects = mapOf(
            "five" to subject("five"),
            "four" to subject("four"),
            "missed" to subject("missed"),
            "contact" to subject("contact", RoundShape.QUIET),
        )
        assertEquals(listOf("five"), globalStats(history, subjects, now, zone).neverFail.map { it.subject.id })
    }

    @Test
    fun `the first-time rate waits for ten hechos in the month`() {
        val nine = mapOf("a" to daily(19..27))
        assertNull(globalStats(nine, mapOf("a" to subject("a")), now, zone).firstTime)
        val snoozed = listOf(rang(day(18)), FiringEvent(FiringKind.SNOOZED, day(18).plusSeconds(60), detail = "x"), rang(day(18).plusSeconds(600)), dealt(day(18, 11)))
        val ten = mapOf("a" to snoozed + daily(19..27))
        assertEquals(9 to 10, globalStats(ten, mapOf("a" to subject("a")), now, zone).firstTime)
    }

    @Test
    fun `a history with no reminder behind it is left out`() {
        val stats = globalStats(mapOf("gone" to daily(20..27)), emptyMap(), now, zone)
        assertEquals(0, stats.hechos)
        assertTrue(stats.tallies.isEmpty())
    }

    // Achievements.

    private fun unlocked(history: Map<String, List<FiringEvent>>, subjects: Map<String, StatsSubject>) =
        achievements(globalStats(history, subjects, now, zone).tallies, now, zone)

    @Test
    fun `an unlock carries the date the tier was crossed`() {
        val all = unlocked(mapOf("a" to daily(1..27)), mapOf("a" to subject("a")))
        val seven = all.single { it.family == AchievementFamily.STREAK && it.tier == 7 }
        assertEquals(LocalDate.of(2026, 8, 7), seven.on)
        assertEquals("a", seven.subjectId)
        assertEquals("text a", seven.about)
        assertTrue(all.none { it.family == AchievementFamily.STREAK && it.tier == 30 })
    }

    @Test
    fun `fifty hechos is the fiftieth, whichever reminder it was`() {
        val history = mapOf("a" to daily(1..27), "b" to daily(1..27))
        val fifty = unlocked(history, mapOf("a" to subject("a"), "b" to subject("b"))).single { it.family == AchievementFamily.HECHOS }
        assertEquals(50, fifty.tier)
        // Two a day: the 49th and 50th are both on the 25th.
        assertEquals(LocalDate.of(2026, 8, 25), fifty.on)
    }

    @Test
    fun `a perfect week needs five rounds and none missed, and only a finished week counts`() {
        // Aug 2026: the 3rd, 10th, 17th and 24th are Mondays; the week of the 24th is still under way.
        val history = mapOf(
            // Every day of three weeks and this one, except the Wednesday of the second.
            "a" to daily(3..11) + rang(day(12)) + daily(13..27),
        )
        val weeks = unlocked(history, mapOf("a" to subject("a"))).filter { it.family == AchievementFamily.PERFECT_WEEKS }
        assertEquals(listOf(1), weeks.map { it.tier })
        assertEquals(LocalDate.of(2026, 8, 9), weeks.single().on)
        val four = unlocked(mapOf("a" to daily(1..27)), mapOf("a" to subject("a"))).filter { it.family == AchievementFamily.PERFECT_WEEKS }
        assertEquals(listOf(1), four.map { it.tier }, "three finished weeks: the 1st of August is a Saturday, so its week has two rounds")
    }

    @Test
    fun `done ahead counts reminders, not routines, which are always ahead when on time`() {
        val ahead = (1..10).map { dealt(day(it)) }
        val one = unlocked(mapOf("r" to ahead), mapOf("r" to subject("r")))
        assertEquals(listOf(10), one.filter { it.family == AchievementFamily.AHEAD }.map { it.tier })
        val routine = unlocked(mapOf("r" to ahead), mapOf("r" to subject("r", RoundShape.ROUTINE)))
        assertTrue(routine.none { it.family == AchievementFamily.AHEAD })
    }

    @Test
    fun `a kept unlock survives the history that proved it being trimmed`() {
        val kept = listOf(Unlocked(AchievementFamily.HECHOS, 50, LocalDate.of(2026, 3, 1)))
        assertEquals(kept, mergeUnlocked(kept, emptyList()))
        // Found again later with a later date: the first date stands, and nothing needs writing.
        val again = listOf(Unlocked(AchievementFamily.HECHOS, 50, LocalDate.of(2026, 8, 1)))
        assertTrue(mergeUnlocked(kept, again) === kept)
    }

    @Test
    fun `merging adds what is new and keeps the earlier date`() {
        val kept = listOf(Unlocked(AchievementFamily.STREAK, 7, LocalDate.of(2026, 8, 20), "a", "text a"))
        val derived = listOf(
            Unlocked(AchievementFamily.STREAK, 7, LocalDate.of(2026, 8, 7), "a", "text a"),
            Unlocked(AchievementFamily.STREAK, 7, LocalDate.of(2026, 8, 9), "b", "text b"),
        )
        val merged = mergeUnlocked(kept, derived)
        assertEquals(listOf(LocalDate.of(2026, 8, 7) to "a", LocalDate.of(2026, 8, 9) to "b"), merged.map { it.on to it.subjectId })
    }

    @Test
    fun `the next goal is the nearest milestone still ahead`() {
        val history = mapOf("a" to daily(1..27))
        val stats = globalStats(history, mapOf("a" to subject("a")), now, zone)
        val goal = nextGoal(stats, achievements(stats.tallies, now, zone))
        // 27 hechos: 23 to fifty; 27 in a row: 3 to thirty.
        assertEquals(Goal(AchievementFamily.STREAK, 30, 3, subject("a")), goal)
    }

    @Test
    fun `hechos per day ignore what falls outside the fortnight`() {
        val today = LocalDate.of(2026, 8, 27)
        val counts = countByDay(listOf(today, today, today.minusDays(13), today.minusDays(14), today.plusDays(1)), today)
        assertEquals(2, counts.last())
        assertEquals(1, counts.first())
        assertEquals(3, counts.sum())
    }
}
