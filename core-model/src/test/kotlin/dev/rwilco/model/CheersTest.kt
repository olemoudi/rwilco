package dev.rwilco.model

import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

/**
 * The encouragement: only true and good things, every one with a number or a reminder's words
 * in it, and never the same thing twice in a row.
 */
class CheersTest {

    // Thursday 2026-08-27, 15:00.
    private val now = Fixtures.now
    private fun day(d: Int, hour: Int = 9): Instant = local(2026, 8, d, hour, 0)
    private fun rang(at: Instant) = FiringEvent(FiringKind.RANG, at)
    private fun dealt(at: Instant) = FiringEvent(FiringKind.DEALT, at)
    private fun daily(days: IntRange) = days.flatMap { listOf(rang(day(it)), dealt(day(it, 10))) }
    private fun subject(id: String) = StatsSubject(id, "text $id", RoundShape.REPEATING, Status.ACTIVE)
    private val variants = CheerKind.entries.associateWith { 6 }

    private fun candidates(history: Map<String, List<FiringEvent>>, unlocked: List<Unlocked> = emptyList()): List<Cheer> {
        val stats = globalStats(history, history.keys.associateWith(::subject), now, zone)
        return cheers(stats, unlocked, now, zone)
    }

    @Test
    fun `nothing true to say is silence`() {
        assertTrue(candidates(mapOf("a" to daily(26..27))).isEmpty(), "two in a row, two this week: nothing worth a line")
        assertNull(pickCheer(emptyList(), emptyList(), now, variants, seed = 1))
    }

    @Test
    fun `a number is only offered from two up`() {
        val all = candidates(mapOf("a" to daily(1..27), "b" to daily(20..27)))
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { cheer -> cheer.numbers.all { it >= 2 } }, all.toString())
    }

    @Test
    fun `every line is about a number or a reminder`() {
        val all = candidates(mapOf("a" to daily(1..27)), listOf(Unlocked(AchievementFamily.HECHOS, 50, LocalDate.of(2026, 8, 26))))
        assertTrue(all.all { it.numbers.isNotEmpty() || it.subject != null || it.achievement != null })
    }

    @Test
    fun `a streak longer than any before it is a best, not just a streak`() {
        val history = mapOf("a" to daily(1..4) + rang(day(5)) + daily(6..27))
        val kinds = candidates(history).filter { it.subjectId == "a" }.map { it.kind }
        assertTrue(CheerKind.BEST_STREAK in kinds, kinds.toString())
        assertTrue(CheerKind.STREAK !in kinds)
        // With no earlier run to beat, it is only a streak.
        val first = candidates(mapOf("a" to daily(1..27))).filter { it.subjectId == "a" }.map { it.kind }
        assertTrue(CheerKind.STREAK in first && CheerKind.BEST_STREAK !in first, first.toString())
    }

    @Test
    fun `a week up on the last says by how much`() {
        val history = mapOf("a" to daily(18..20) + daily(21..27) + daily(21..27).map { FiringEvent(it.kind, it.at.plusSeconds(60)) })
        val up = candidates(history).single { it.kind == CheerKind.WEEK_UP }
        // Fourteen from the 21st to the 27th, three the week before (the 18th to the 20th).
        assertEquals(listOf(14, 11), up.numbers)
    }

    @Test
    fun `an achievement is news for three days`() {
        val fresh = Unlocked(AchievementFamily.HECHOS, 50, LocalDate.of(2026, 8, 25))
        val old = Unlocked(AchievementFamily.HECHOS, 100, LocalDate.of(2026, 8, 24))
        val kinds = candidates(emptyMap(), listOf(fresh, old)).filter { it.kind == CheerKind.ACHIEVEMENT }
        assertEquals(listOf(fresh), kinds.map { it.achievement })
    }

    @Test
    fun `the same fact is not said twice in a week`() {
        val cheer = Cheer(CheerKind.WEEK_COUNT, "week:12", 20, listOf(12))
        val said = CheerShown(cheer.key, cheer.kind, 0, now.minus(Duration.ofDays(6)))
        assertNull(pickCheer(listOf(cheer), listOf(said), now, variants, seed = 1))
        val longAgo = said.copy(at = now.minus(Duration.ofDays(8)))
        assertEquals(cheer, pickCheer(listOf(cheer), listOf(longAgo), now, variants, seed = 1)?.cheer)
    }

    @Test
    fun `the same reminder is left alone for a day and a half`() {
        val cheer = Cheer(CheerKind.STREAK, "streak:a:9", 40, listOf(9), "a", "text a")
        val yesterday = CheerShown("streak:a:8", CheerKind.STREAK, 0, now.minus(Duration.ofHours(20)), subjectId = "a")
        assertNull(pickCheer(listOf(cheer), listOf(yesterday), now, variants, seed = 1))
        assertEquals(cheer, pickCheer(listOf(cheer), listOf(yesterday.copy(at = now.minus(Duration.ofHours(40)))), now, variants, seed = 1)?.cheer)
    }

    @Test
    fun `the kind said last gives way when there is anything else`() {
        val week = Cheer(CheerKind.WEEK_COUNT, "week:12", 1000, listOf(12))
        val today = Cheer(CheerKind.TODAY_COUNT, "today:x:4", 1, listOf(4))
        val last = CheerShown("week:9", CheerKind.WEEK_COUNT, 0, now.minus(Duration.ofDays(8)))
        repeat(20) { seed ->
            assertEquals(CheerKind.TODAY_COUNT, pickCheer(listOf(week, today), listOf(last), now, variants, seed.toLong())?.cheer?.kind)
        }
        assertEquals(CheerKind.WEEK_COUNT, pickCheer(listOf(week), listOf(last), now, variants, 1)?.cheer?.kind)
    }

    @Test
    fun `a variant is never the one used last`() {
        val cheer = Cheer(CheerKind.WEEK_COUNT, "week:12", 20, listOf(12))
        repeat(50) { seed ->
            val last = seed % 6
            val said = CheerShown("week:3", CheerKind.WEEK_COUNT, last, now.minus(Duration.ofDays(8)))
            val pick = pickCheer(listOf(cheer), listOf(said), now, variants, seed.toLong())!!
            assertNotEquals(last, pick.variant)
            assertTrue(pick.variant in 0 until 6)
        }
    }

    @Test
    fun `something waiting for an answer is not a moment for encouragement`() {
        val later = Fixtures.reminder(Trigger.AtDateTime(java.time.LocalDateTime.of(2026, 8, 28, 9, 0)))
        assertTrue(calmForCheer(listOf(later), now))
        val ringing = later.copy(id = "ringing", lastFiredAt = now.minusSeconds(60))
        assertTrue(!calmForCheer(listOf(later, ringing), now))
        // Its moment gone without ringing is overdue, not owed: one old card must not silence it for weeks.
        val gone = Fixtures.reminder(Trigger.AtDateTime(java.time.LocalDateTime.of(2026, 8, 20, 9, 0)), id = "gone")
        assertTrue(calmForCheer(listOf(later, gone), now))
    }

    @Test
    fun `a slot keeps its line while it still holds`() {
        val a = Cheer(CheerKind.WEEK_COUNT, "week:12", 20, listOf(12))
        val b = Cheer(CheerKind.TODAY_COUNT, "today:x:4", 20, listOf(4))
        val said = CheerShown(a.key, a.kind, 3, now.minus(Duration.ofMinutes(30)), slot = "2026-08-27:AFTERNOON")
        assertEquals(CheerPick(a, 3), pickCheer(listOf(a, b), listOf(said), now, variants, 7, slot = "2026-08-27:AFTERNOON"))
        // The week moved on by a "hecho": the same line in the same words, with the new number.
        val moved = a.copy(key = "week:13", numbers = listOf(13))
        assertEquals(CheerPick(moved, 3), pickCheer(listOf(moved, b), listOf(said), now, variants, 7, slot = "2026-08-27:AFTERNOON"))
        // No longer true at all: a fresh one, not the stale line.
        assertEquals(b, pickCheer(listOf(b), listOf(said), now, variants, 7, slot = "2026-08-27:AFTERNOON")?.cheer)
    }

    @Test
    fun `a kind said in the last day gives way to one that was not`() {
        val week = Cheer(CheerKind.WEEK_COUNT, "week:14", 1000, listOf(14))
        val today = Cheer(CheerKind.TODAY_COUNT, "today:x:4", 1, listOf(4))
        val streak = Cheer(CheerKind.STREAK, "streak:a:5", 1, listOf(5), "a", "text a")
        // The week said this morning, a streak after it: the week still waits.
        val said = listOf(
            CheerShown("week:12", CheerKind.WEEK_COUNT, 0, now.minus(Duration.ofHours(6))),
            CheerShown("streak:b:4", CheerKind.STREAK, 0, now.minus(Duration.ofHours(2)), subjectId = "b"),
        )
        repeat(20) { seed ->
            assertEquals(CheerKind.TODAY_COUNT, pickCheer(listOf(week, today, streak), said, now, variants, seed.toLong())?.cheer?.kind)
        }
    }

    @Test
    fun `a week down on the last is not a good week to mention`() {
        // Twenty-one the week before, seven this one: nothing about the week.
        val history = mapOf(
            "a" to daily(14..20), "b" to daily(14..20), "c" to daily(14..20),
            "d" to daily(21..27),
        )
        val kinds = candidates(history).map { it.kind }
        assertTrue(CheerKind.WEEK_COUNT !in kinds && CheerKind.WEEK_UP !in kinds, kinds.toString())
    }

    @Test
    fun `the same slot draws the same line however often it is asked`() {
        val all = (3..8).map { Cheer(CheerKind.STREAK, "streak:$it:5", 40, listOf(5), "$it", "text $it") }
        val seed = slotSeed("2026-08-27:AFTERNOON")
        assertEquals(pickCheer(all, emptyList(), now, variants, seed), pickCheer(all, emptyList(), now, variants, seed))
    }

    @Test
    fun `a kind with no phrasing is never said`() {
        val cheer = Cheer(CheerKind.AHEAD, "ahead:3", 25, listOf(3))
        assertNull(pickCheer(listOf(cheer), emptyList(), now, variants - CheerKind.AHEAD, 1))
    }

    @Test
    fun `before the morning starts it is still last night's evening`() {
        val parts = DayParts(LocalTime.of(9, 0), LocalTime.of(17, 0), LocalTime.of(20, 0))
        // The person's own hours: at three the afternoon, which starts at five, has not begun.
        assertEquals("2026-08-27:MORNING", daySlot(now, zone, parts))
        assertEquals("2026-08-27:MORNING", daySlot(local(2026, 8, 27, 9, 0), zone, parts))
        assertEquals("2026-08-27:AFTERNOON", daySlot(local(2026, 8, 27, 18, 0), zone, parts))
        assertEquals("2026-08-26:EVENING", daySlot(local(2026, 8, 27, 3, 0), zone, parts))
        assertEquals("2026-08-27:EVENING", daySlot(local(2026, 8, 27, 23, 0), zone, parts))
    }

    @Test
    fun `hours out of order are read in clock order`() {
        // A night worker: the morning at 22:00, the afternoon at 04:00, the evening at 10:00.
        val parts = DayParts(LocalTime.of(22, 0), LocalTime.of(4, 0), LocalTime.of(10, 0))
        assertEquals("2026-08-27:EVENING", daySlot(now, zone, parts))
        assertEquals("2026-08-26:MORNING", daySlot(local(2026, 8, 27, 2, 0), zone, parts))
    }

    @Test
    fun `the next word lands two or three days on inside the waking hours`() {
        val shape = DayShape.DEFAULT
        // Saturday or Sunday; a Saturday night runs past midnight, so the day is the waking day's.
        val windows = listOf(LocalDate.of(2026, 8, 29), LocalDate.of(2026, 8, 30)).map(shape::awakeOn)
        repeat(40) { seed ->
            val at = nextCheerAt(now, zone, shape, Random(seed)).atZone(zone).toLocalDateTime()
            assertTrue(windows.any { at >= it.from.plusHours(1) && at < it.to.minusHours(1) }, "$at outside $windows")
        }
    }

    @Test
    fun `a retry in the small hours waits for the morning, not for the day after`() {
        // Saturday 03:00: Friday's night is over, Saturday's day opens at ten, plus the margin.
        val at = cheerRetryAt(local(2026, 8, 29, 3, 0), zone, DayShape.DEFAULT, Random(1))
        assertEquals(local(2026, 8, 29, 11, 0), at)
        // Saturday 00:10 is still Friday's night, and ninety minutes on it is past its margin.
        assertEquals(local(2026, 8, 29, 11, 0), cheerRetryAt(local(2026, 8, 29, 0, 10), zone, DayShape.DEFAULT, Random(1)))
        // Thursday 07:00, before waking: the same morning.
        assertEquals(local(2026, 8, 27, 9, 0), cheerRetryAt(local(2026, 8, 27, 7, 0), zone, DayShape.DEFAULT, Random(1)))
    }

    @Test
    fun `a retry stays within today when there is time`() {
        assertEquals(now.plus(CHEER_RETRY), cheerRetryAt(now, zone, DayShape.DEFAULT, Random(1)))
        // 21:45 on a Thursday: bedtime 23:30 less the hour's margin leaves no room for ninety minutes.
        val late = cheerRetryAt(local(2026, 8, 27, 21, 45), zone, DayShape.DEFAULT, Random(1)).atZone(zone).toLocalDateTime()
        val friday = DayShape.DEFAULT.awakeOn(LocalDate.of(2026, 8, 28))
        assertTrue(late >= friday.from.plusHours(1) && late < friday.to.minusHours(1), "$late outside $friday")
    }

    @Test
    fun `a long reminder is cut at a word inside the quotes`() {
        assertEquals("Regar las plantas", shortSubject("Regar las plantas"))
        val long = "Preguntarle a Marta por el presupuesto de la reforma del baño"
        val cut = shortSubject(long)
        assertTrue(cut.length <= SUBJECT_MAX, cut)
        assertTrue(cut.endsWith("…"), cut)
        assertTrue(long.startsWith(cut.removeSuffix("…")), cut)
        assertEquals("a b", shortSubject("  a \n b  "))
    }

    @Test
    fun `said lines are remembered up to forty`() {
        val many = (1..45).fold(emptyList<CheerShown>()) { list, i -> list.remembering(CheerShown("k$i", CheerKind.WEEK_COUNT, 0, now)) }
        assertEquals(CHEERS_REMEMBERED, many.size)
        assertEquals("k45", many.last().key)
    }
}
