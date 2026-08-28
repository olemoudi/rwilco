package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The odd shapes, wound forward through a year of alarms: every ring on the day and inside the
 * hour it was asked for, across the year end and both clock changes, dealt with or ignored.
 *
 * Madrid puts its clocks forward on 2026-03-29 and 2027-03-28 and back on 2026-10-25.
 */
class FiringAuditTest {

    private fun reminder(
        vararg rules: TriggerRule,
        recurrence: Recurrence = Recurrence.None,
        match: RuleMatch = RuleMatch.ANY,
        createdAt: Instant = now,
        id: String = "r1",
    ) = Reminder(id = id, text = "x", rules = rules.toList(), recurrence = recurrence, ruleMatch = match, createdAt = createdAt, updatedAt = createdAt)

    private fun firstFridayCalendar(time: LocalTime? = null, window: DayWindow? = null, fences: List<Condition> = emptyList()) =
        Recurrence.Calendar(
            Trigger.Repeat(startsOn = LocalDate.of(2026, 9, 1), unit = RepeatUnit.MONTH, monthly = MonthlyOn.Nth(1, DayOfWeek.FRIDAY), time = time, window = window),
            fences,
        )

    private val firstFridays = listOf(
        LocalDate.of(2026, 9, 4), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 11, 6), LocalDate.of(2026, 12, 4),
        LocalDate.of(2027, 1, 1), LocalDate.of(2027, 2, 5), LocalDate.of(2027, 3, 5), LocalDate.of(2027, 4, 2),
        LocalDate.of(2027, 5, 7), LocalDate.of(2027, 6, 4), LocalDate.of(2027, 7, 2), LocalDate.of(2027, 8, 6),
        LocalDate.of(2027, 9, 3), LocalDate.of(2027, 10, 1),
    )

    private fun assertFirstFridaysAtTeatime(rings: List<Simulation.Ring>, what: String) {
        assertEquals(firstFridays, rings.map { it.local(zone).toLocalDate() }, "$what: one ring per first Friday, and no other day")
        for (ring in rings) {
            val time = ring.local(zone).toLocalTime()
            assertTrue(time >= LocalTime.of(16, 0) && time < LocalTime.of(17, 0), "$what: ${ring.local(zone)} is outside 16–17")
        }
    }

    @Test
    fun `the first friday of every month between four and five, said three ways, rings on every first friday between four and five`() {
        val until = local(2027, 10, 27, 0, 0)
        val spellings = mapOf(
            "at 16:00" to firstFridayCalendar(time = LocalTime.of(16, 0)),
            "in the window 16–17" to firstFridayCalendar(window = DayWindow(LocalTime.of(16, 0), LocalTime.of(17, 0))),
            // The one that used to ring once a year: no hour, and a fence the whole-day draw
            // cleared one month in fifteen.
            "no hour, and only between 16 and 17" to firstFridayCalendar(fences = listOf(Condition.TimeWindow(LocalTime.of(16, 0), LocalTime.of(17, 0)))),
        )
        for ((what, calendar) in spellings) {
            assertFirstFridaysAtTeatime(Simulation(reminder(recurrence = calendar), now).run(until), "$what, ignored")
            assertFirstFridaysAtTeatime(Simulation(reminder(recurrence = calendar), now).run(until) { Simulation.Deal.Done }, "$what, dealt with")
        }
        // The exact one is exactly that, either side of the clock change and the year end.
        val exact = Simulation(reminder(recurrence = spellings.getValue("at 16:00")), now).run(until)
        assertTrue(exact.all { it.local(zone).toLocalTime() == LocalTime.of(16, 0) })
        assertEquals(Instant.parse("2026-10-02T14:00:00Z"), exact[1].at, "summer time")
        assertEquals(Instant.parse("2026-11-06T15:00:00Z"), exact[2].at, "winter time")
        assertEquals(Instant.parse("2027-01-01T15:00:00Z"), exact[4].at, "new year's day is a Friday")
    }

    @Test
    fun `a fifth friday a month does not have is its last one`() {
        val fifth = Trigger.Repeat(startsOn = LocalDate.of(2026, 9, 1), unit = RepeatUnit.MONTH, monthly = MonthlyOn.Nth(5, DayOfWeek.FRIDAY), time = LocalTime.of(16, 0))
        // September has four Fridays, October five, November four.
        assertEquals(listOf(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 27)), fifth.occurrences().take(3).toList())
        val rings = Simulation(reminder(recurrence = Recurrence.Calendar(fifth)), now).run(local(2026, 12, 1, 0, 0))
        assertEquals(listOf(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 27)), rings.map { it.local(zone).toLocalDate() })
    }

    @Test
    fun `a window on fridays that comes back on the first friday rings the next friday first, then rests to first fridays`() {
        // Documented: the triggers say when it rings the FIRST time and the recurrence when it
        // comes back. Written on a Thursday, "de 16 a 17 los viernes" rings tomorrow, and only
        // once dealt with does the calendar say which Friday is next.
        val window = TriggerRule(Trigger.Interval(LocalTime.of(16, 0), LocalTime.of(17, 0), setOf(DayOfWeek.FRIDAY)))
        val dealt = Simulation(reminder(window, recurrence = firstFridayCalendar(time = LocalTime.of(16, 0))), now)
            .run(local(2026, 11, 30, 0, 0)) { Simulation.Deal.Done }
        assertEquals(
            listOf(LocalDate.of(2026, 8, 28), LocalDate.of(2026, 9, 4), LocalDate.of(2026, 10, 2), LocalDate.of(2026, 11, 6)),
            dealt.map { it.local(zone).toLocalDate() },
        )
        assertTrue(dealt.all { it.local(zone).toLocalTime() == LocalTime.of(16, 0) })
        // Ignored, nothing rests, and the window speaks every Friday.
        val ignored = Simulation(reminder(window, recurrence = firstFridayCalendar(time = LocalTime.of(16, 0))), now).run(local(2026, 9, 30, 0, 0))
        assertEquals(listOf(28, 4, 11, 18, 25), ignored.map { it.local(zone).dayOfMonth })
    }

    @Test
    fun `today at any hour, written in the afternoon, rings this evening`() {
        val at = now.plusSeconds(20)
        val today = LocalDate.of(2026, 8, 27)
        for (id in listOf("a", "b", "c", "d", "e", "f")) {
            val rules = settleDays(listOf(TriggerRule(Trigger.DayRandom(today))), at, zone, DayShape.DEFAULT)
            val ring = Simulation(reminder(rules.single(), createdAt = at, id = id), at).step()
            val local = ring?.local(zone)
            assertTrue(local != null && local.toLocalDate() == today && local.toLocalTime() >= LocalTime.of(15, 1), "$id rang at $local")
        }
    }

    @Test
    fun `a day at any hour with a fence draws inside the fence, and a fence on another day is said out loud`() {
        val thursday = LocalDate.of(2026, 9, 3)
        val teatime = Condition.TimeWindow(LocalTime.of(16, 0), LocalTime.of(17, 0))
        for (id in listOf("a", "b", "c", "d", "e", "f")) {
            val ring = Simulation(reminder(TriggerRule(Trigger.DayRandom(thursday), listOf(teatime)), id = id), now).step()
            val local = ring?.local(zone)
            assertTrue(local != null && local.toLocalDate() == thursday && local.toLocalTime() >= LocalTime.of(16, 0) && local.toLocalTime() < LocalTime.of(17, 0), "$id: $local")
        }
        val mondays = TriggerRule(Trigger.DayRandom(thursday), listOf(Condition.TimeWindow(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, setOf(DayOfWeek.MONDAY))))
        assertNull(Simulation(reminder(mondays), now).arm(), "a Thursday that has to be a Monday never rings")
        assertTrue(warnings(listOf(mondays), now, zone, defaultTime).any { it is ValidationWarning.NeverFires })
    }

    @Test
    fun `a fence that wraps midnight is kept every night, across the clock change`() {
        val night = Condition.TimeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0))
        val nightly = Recurrence.Calendar(Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 28), unit = RepeatUnit.DAY), listOf(night))
        val rings = Simulation(reminder(recurrence = nightly), now).run(local(2026, 11, 2, 12, 0))
        // One a night from the 28th of August to the 1st of November, the clock change included.
        val nights = Duration.between(LocalDate.of(2026, 8, 28).atStartOfDay(zone), LocalDate.of(2026, 11, 2).atStartOfDay(zone)).toDays()
        assertEquals(nights, rings.size.toLong())
        for (ring in rings) {
            assertTrue(night.holdsAt(ring.at, zone), "${ring.local(zone)} is not night")
        }
        assertEquals(rings.sortedBy { it.at }, rings)

        // The window trigger of the same hours, on Fridays: opens at ten every Friday, and the
        // Friday after the clocks go back is seven days and one hour later on the line.
        val fridays = TriggerRule(Trigger.Interval(LocalTime.of(22, 0), LocalTime.of(6, 0), setOf(DayOfWeek.FRIDAY)))
        val weekly = Simulation(reminder(fridays, recurrence = Recurrence.ByTrigger), local(2026, 10, 20, 12, 0)).run(local(2026, 11, 3, 0, 0))
        assertEquals(listOf(Instant.parse("2026-10-23T20:00:00Z"), Instant.parse("2026-10-30T21:00:00Z")), weekly.map { it.at })
        assertTrue(weekly.all { it.local(zone).toLocalTime() == LocalTime.of(22, 0) })
    }

    @Test
    fun `a random window with a fence on mondays rings only on mondays, its own number of times`() {
        val random = Trigger.Random(3, Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0))
        val onlyMondays = Condition.TimeWindow(LocalTime.of(10, 0), LocalTime.of(20, 0), setOf(DayOfWeek.MONDAY))
        val rings = Simulation(reminder(TriggerRule(random, listOf(onlyMondays)), recurrence = Recurrence.ByTrigger), now).run(local(2026, 9, 20, 0, 0))
        assertEquals(listOf(31, 31, 31, 7, 7, 7, 14, 14, 14), rings.map { it.local(zone).dayOfMonth })
        assertTrue(rings.all { it.local(zone).dayOfWeek == DayOfWeek.MONDAY })
        assertTrue(rings.all { it.local(zone).toLocalTime() >= LocalTime.of(10, 0) && it.local(zone).toLocalTime() < LocalTime.of(20, 0) })
    }

    @Test
    fun `a counted daily series started long ago still rings tomorrow`() {
        val counted = Trigger.Repeat(startsOn = LocalDate.of(2024, 12, 1), unit = RepeatUnit.DAY, time = LocalTime.of(9, 0), ends = RepeatEnd.After(999))
        val next = Simulation(reminder(recurrence = Recurrence.Calendar(counted)), now).arm()
        assertEquals(local(2026, 8, 28, 9, 0), next?.at)
    }

    @Test
    fun `a set slept through across the clock change or the year end completes late, not never`() {
        for ((first, second, morning) in listOf(
            Triple(local(2026, 10, 24, 21, 0), local(2026, 10, 25, 9, 0), local(2026, 10, 25, 10, 0)),
            Triple(local(2026, 12, 31, 23, 0), local(2027, 1, 1, 9, 0), local(2027, 1, 1, 10, 0)),
        )) {
            val set = reminder(
                TriggerRule(Trigger.AtDateTime(first.atZone(zone).toLocalDateTime())),
                TriggerRule(Trigger.AtDateTime(second.atZone(zone).toLocalDateTime())),
                match = RuleMatch.ALL,
                createdAt = first.minusSeconds(3600 * 9),
            )
            val phone = Simulation(set, first.minusSeconds(3600 * 9))
            assertEquals(Wake(first, 0), phone.arm(), "the earliest is armed")
            val rings = phone.sleepUntil(morning)
            assertEquals(1, rings.size, "one ring, for the set as a whole")
            assertEquals(morning, rings.single().rangFor, "recorded as rung now")
            assertEquals(second, rings.single().late, "for the moment that completed it")
            assertEquals(1, rings.single().ruleIndex)
            assertEquals(setOf(0, 1), phone.reminder.firedRules)
            assertNull(phone.arm(), "nothing left to arm")
        }
    }

    @Test
    fun `under all, a day at any hour fenced to an hour opens at that hour and completes the set`() {
        val thursday = LocalDate.of(2026, 9, 3)
        val fenced = TriggerRule(Trigger.DayRandom(thursday), listOf(Condition.TimeWindow(LocalTime.of(16, 0), LocalTime.of(17, 0))))
        val appointment = TriggerRule(Trigger.AtDateTime(LocalDate.of(2026, 9, 2).atTime(10, 0)))
        val all = Simulation(reminder(fenced, appointment, match = RuleMatch.ALL), now)
        val rings = all.run(local(2026, 9, 30, 0, 0))
        assertEquals(listOf(Wake(local(2026, 9, 2, 10, 0), 1)), all.noted, "the appointment is noted")
        assertEquals(listOf(local(2026, 9, 3, 16, 0)), rings.map { it.at }, "the gate opens at four and completes the set")
        // Under "cualquiera" the same day is a draw, inside its fence.
        val any = Simulation(reminder(fenced, appointment), now).run(local(2026, 9, 30, 0, 0))
        assertEquals(2, any.size)
        assertEquals(local(2026, 9, 2, 10, 0), any[0].at)
        val drawn = any[1].local(zone)
        assertTrue(drawn.toLocalDate() == thursday && drawn.toLocalTime() >= LocalTime.of(16, 0) && drawn.toLocalTime() < LocalTime.of(17, 0), "$drawn")
    }
}
