package dev.rwilco.model

import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RandomDrawTest {

    private val thursday = LocalDate.of(2026, 8, 27)
    private val daily = Trigger.Random(3, Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0), emptySet())
    private val weekly = Trigger.Random(
        2, Period.WEEK, LocalTime.of(10, 0), LocalTime.of(20, 0),
        setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
    )

    private fun local(date: LocalDate, hour: Int, minute: Int) =
        LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant()

    @Test
    fun `the generator and the hash are pinned to the bit`() {
        // Golden values from the reference implementation; a "harmless" refactor that changes
        // any of these reshuffles every random reminder on every phone.
        assertEquals(3130785207451900356L, RandomDraw.fnv1a64("golden"))
        val rng = RandomDraw.SplitMix64(0)
        assertEquals(listOf(-2152535657050944081L, 7960286522194355700L, 487617019471545679L), List(3) { rng.nextLong() })
    }

    @Test
    fun `period indexes are epoch days and Monday-start weeks`() {
        assertEquals(20692L, RandomDraw.periodIndex(thursday, Period.DAY))
        assertEquals(2956L, RandomDraw.periodIndex(thursday, Period.WEEK))
        assertEquals(LocalDate.of(2026, 8, 24), RandomDraw.weekStart(2956L))
        // Sunday belongs to the week that started the previous Monday.
        assertEquals(2956L, RandomDraw.periodIndex(LocalDate.of(2026, 8, 30), Period.WEEK))
        assertEquals(2957L, RandomDraw.periodIndex(LocalDate.of(2026, 8, 31), Period.WEEK))
    }

    @Test
    fun `daily draws are the golden values`() {
        val draws = RandomDraw.draws(daily, "golden", 20692L, zone)
        assertEquals(listOf(local(thursday, 14, 4), local(thursday, 14, 57), local(thursday, 14, 59)), draws)
        assertEquals(
            listOf(local(thursday, 10, 37), local(thursday, 16, 2), local(thursday, 16, 31)),
            RandomDraw.draws(daily, "golden-2", 20692L, zone),
        )
    }

    @Test
    fun `weekly draws are the golden values`() {
        val draws = RandomDraw.draws(weekly, "golden", 2956L, zone)
        assertEquals(listOf(local(LocalDate.of(2026, 8, 24), 11, 9), local(LocalDate.of(2026, 8, 26), 12, 0)), draws)
    }

    @Test
    fun `draws stay inside the window and eligible days over many periods`() {
        for (index in 20692L until 20692L + 400) {
            val draws = RandomDraw.draws(daily, "any", index, zone)
            assertEquals(3, draws.size)
            assertEquals(draws.sorted(), draws)
            assertEquals(draws.toSet().size, draws.size, "two draws on the same minute")
            for (at in draws) {
                val time = at.atZone(zone).toLocalTime()
                assertTrue(time >= LocalTime.of(10, 0) && time < LocalTime.of(20, 0), "$time outside the window")
            }
        }
        for (week in 2956L until 2956L + 60) {
            for (at in RandomDraw.draws(weekly, "any", week, zone)) {
                assertTrue(at.atZone(zone).dayOfWeek in weekly.days)
            }
        }
    }

    @Test
    fun `an ineligible day draws nothing and a different reminder draws differently`() {
        val sundaysOnly = daily.copy(days = setOf(DayOfWeek.SUNDAY))
        assertTrue(RandomDraw.draws(sundaysOnly, "golden", 20692L, zone).isEmpty())
        assertEquals(3, RandomDraw.draws(sundaysOnly, "golden", 20695L, zone).size)
        assertNotEquals(RandomDraw.draws(daily, "a", 20692L, zone), RandomDraw.draws(daily, "b", 20692L, zone))
        assertEquals(RandomDraw.draws(daily, "a", 20692L, zone), RandomDraw.draws(daily, "a", 20692L, zone))
    }

    @Test
    fun `a window that ends where it starts draws nothing`() {
        assertTrue(RandomDraw.draws(daily.copy(from = LocalTime.of(12, 0), to = LocalTime.of(12, 0)), "x", 20692L, zone).isEmpty())
    }

    @Test
    fun `a window that ends before it starts crosses midnight and draws into the small hours`() {
        // Every other window in the model reads "22:00 to 02:00" as an evening that runs past
        // midnight; this one read it as minus twenty hours and drew nothing, for ever.
        val evening = daily.copy(from = LocalTime.of(22, 0), to = LocalTime.of(2, 0))
        assertEquals(240, RandomDraw.windowMinutes(evening))
        for (index in 20692L until 20692L + 60) {
            val draws = RandomDraw.draws(evening, "x", index, zone)
            assertEquals(3, draws.size)
            val opened = LocalDate.ofEpochDay(index).atTime(22, 0).atZone(zone).toInstant()
            val closes = LocalDate.ofEpochDay(index).plusDays(1).atTime(2, 0).atZone(zone).toInstant()
            for (at in draws) assertTrue(at >= opened && at < closes, "$at outside the evening that opened on ${LocalDate.ofEpochDay(index)}")
        }
        // The days are judged by the evening the window opened on: a Friday-only window draws
        // on Friday nights, small hours of Saturday included.
        val fridays = evening.copy(days = setOf(DayOfWeek.FRIDAY))
        val friday = LocalDate.of(2026, 8, 28)
        assertTrue(RandomDraw.draws(fridays, "x", friday.toEpochDay(), zone).isNotEmpty())
        assertTrue(RandomDraw.draws(fridays, "x", friday.plusDays(1).toEpochDay(), zone).isEmpty())
    }


    @Test
    fun `a day with no fences is the draw it always was`() {
        // Golden values captured before the draw learnt about fences. A reminder with none must
        // ring at the very minute it did on the last build, or every "a cualquier hora" on every
        // phone moves on the update.
        val shape = DayShape.DEFAULT
        fun drawn(date: LocalDate) = RandomDraw.inDay("golden", date, shape.awakeOn(date), zone).atZone(zone).toLocalDateTime()
        assertEquals(LocalDateTime.of(2026, 8, 27, 15, 57), drawn(LocalDate.of(2026, 8, 27)))
        // A Friday is drawn into the small hours of Saturday, as it always was.
        assertEquals(LocalDateTime.of(2026, 8, 29, 1, 7), drawn(LocalDate.of(2026, 8, 28)))
        assertEquals(LocalDateTime.of(2026, 8, 29, 18, 34), drawn(LocalDate.of(2026, 8, 29)))
        assertEquals(LocalDateTime.of(2026, 9, 3, 22, 55), drawn(LocalDate.of(2026, 9, 3)))
        val lunch = DayWindow(LocalTime.of(14, 0), LocalTime.of(16, 0))
        assertEquals(
            LocalDateTime.of(2026, 8, 27, 15, 14),
            RandomDraw.inDay("r1", thursday, lunch.on(thursday), zone).atZone(zone).toLocalDateTime(),
        )
        // The same with an empty fence list spelled out: not a different path.
        assertEquals(
            RandomDraw.inDay("r1", thursday, lunch.on(thursday), zone),
            RandomDraw.inDay("r1", thursday, lunch.on(thursday), zone, emptyList()),
        )
    }

    @Test
    fun `a fenced draw lands inside the fence, and a fence no minute clears falls back to the plain draw`() {
        val shape = DayShape.DEFAULT
        val teatime = listOf(Condition.TimeWindow(LocalTime.of(16, 0), LocalTime.of(17, 0)))
        // Many days and many reminders: it is the fence doing the work, not one lucky minute.
        for (day in 0L until 60L) {
            val date = thursday.plusDays(day)
            for (id in listOf("a", "b", "c")) {
                val at = RandomDraw.inDay(id, date, shape.awakeOn(date), zone, teatime).atZone(zone).toLocalDateTime()
                assertTrue(at >= date.atTime(16, 0) && at < date.atTime(17, 0), "$id drew $at on $date")
            }
        }
        // A fence that wraps midnight, on a Friday whose waking hours run past it too.
        val night = listOf(Condition.TimeWindow(LocalTime.of(23, 0), LocalTime.of(6, 0)))
        val friday = LocalDate.of(2026, 8, 28)
        val late = RandomDraw.inDay("a", friday, shape.awakeOn(friday), zone, night).atZone(zone).toLocalDateTime()
        assertTrue(late >= friday.atTime(23, 0) && late < friday.plusDays(1).atTime(1, 30), "drew $late")
        // A fence on another day of the week allows no minute of this one: the plain draw comes
        // back — the same minute as with no fence at all — for the caller's walk to reject.
        val mondays = listOf(Condition.TimeWindow(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, setOf(DayOfWeek.MONDAY)))
        assertEquals(
            RandomDraw.inDay("a", thursday, shape.awakeOn(thursday), zone),
            RandomDraw.inDay("a", thursday, shape.awakeOn(thursday), zone, mondays),
        )
        assertFalse(mondays.first().holdsAt(RandomDraw.inDay("a", thursday, shape.awakeOn(thursday), zone, mondays), zone))
        // A window too short to draw from is its own start, fences or not.
        val minute = AwakeWindow(thursday.atTime(16, 0), thursday.atTime(16, 1))
        assertEquals(thursday.atTime(16, 0).atZone(zone).toInstant(), RandomDraw.inDay("a", thursday, minute, zone, teatime))
    }
}
