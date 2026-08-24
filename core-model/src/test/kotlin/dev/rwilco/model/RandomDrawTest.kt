package dev.rwilco.model

import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `an empty or inverted window draws nothing`() {
        assertTrue(RandomDraw.draws(daily.copy(from = LocalTime.of(12, 0), to = LocalTime.of(12, 0)), "x", 20692L, zone).isEmpty())
        assertTrue(RandomDraw.draws(daily.copy(from = LocalTime.of(13, 0), to = LocalTime.of(12, 0)), "x", 20692L, zone).isEmpty())
    }
}
