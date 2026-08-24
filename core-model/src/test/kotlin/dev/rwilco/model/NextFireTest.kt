package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.reminder
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class NextFireTest {

    private fun next(vararg triggers: Trigger, at: Instant = now, status: Status = Status.ACTIVE) =
        nextFire(reminder(*triggers, status = status), at, zone, defaultTime)

    private fun scheduledAt(vararg triggers: Trigger, at: Instant = now): Instant =
        (next(*triggers, at = at) as NextFire.Scheduled).at

    @Test
    fun `a date-time in the future is scheduled and one in the past is nothing`() {
        val tonight = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30))
        assertEquals(local(2026, 8, 27, 21, 30), scheduledAt(tonight))
        assertNull(next(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 14, 59))))
        assertNull(next(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 15, 0))), "now itself has passed")
    }

    @Test
    fun `a date-only trigger rings at the default time`() {
        assertEquals(local(2026, 8, 28, 9, 0), scheduledAt(Trigger.OnDate(LocalDate.of(2026, 8, 28))))
        // Today at 09:00 is already behind a 15:00 clock.
        assertNull(next(Trigger.OnDate(LocalDate.of(2026, 8, 27))))
    }

    @Test
    fun `a repeating time picks today when still ahead and the next marked day otherwise`() {
        val everyDay = DayOfWeek.entries.toSet()
        assertEquals(local(2026, 8, 27, 18, 0), scheduledAt(Trigger.AtTime(LocalTime.of(18, 0), everyDay)))
        assertEquals(local(2026, 8, 28, 7, 30), scheduledAt(Trigger.AtTime(LocalTime.of(7, 30), everyDay)))
        // Thursday 15:00, Mondays only -> Monday 31st.
        assertEquals(local(2026, 8, 31, 7, 30), scheduledAt(Trigger.AtTime(LocalTime.of(7, 30), setOf(DayOfWeek.MONDAY))))
        // Thursdays only, 07:30 already gone today -> next Thursday.
        assertEquals(local(2026, 9, 3, 7, 30), scheduledAt(Trigger.AtTime(LocalTime.of(7, 30), setOf(DayOfWeek.THURSDAY))))
        assertNull(next(Trigger.AtTime(LocalTime.of(7, 30), emptySet())))
    }

    @Test
    fun `a place waits and a random trigger becomes a draw inside its window`() {
        val place = Trigger.Location(40.4, -3.7, 200, Transition.ENTER, "Casa")
        assertEquals(NextFire.WhenAt(place), next(place))

        val random = Trigger.Random(3, Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0), emptySet())
        val sometime = next(random) as NextFire.Sometime
        assertTrue(sometime.at > now)
        assertTrue(sometime.at >= sometime.windowStart && sometime.at < sometime.windowEnd)
        assertEquals(local(2026, 8, 27, 10, 0), sometime.windowStart)
        assertEquals(local(2026, 8, 27, 20, 0), sometime.windowEnd)
    }

    @Test
    fun `a random draw rolls over to the next eligible day`() {
        // All of today's draws are behind a 21:00 clock; Sundays only skips Friday and Saturday.
        val sundays = Trigger.Random(1, Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0), setOf(DayOfWeek.SUNDAY))
        val sometime = next(sundays, at = local(2026, 8, 27, 21, 0)) as NextFire.Sometime
        assertEquals(LocalDate.of(2026, 8, 30), sometime.at.atZone(zone).toLocalDate())
    }

    @Test
    fun `the earliest definite moment wins and a draw beats a place`() {
        val tonight = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30))
        val tomorrow = Trigger.OnDate(LocalDate.of(2026, 8, 28))
        val place = Trigger.Location(40.4, -3.7, 200, Transition.ENTER, "Casa")
        val random = Trigger.Random(1, Period.DAY, LocalTime.of(16, 0), LocalTime.of(20, 0), emptySet())
        assertEquals(tonight, next(tomorrow, place, random, tonight)!!.trigger)
        assertEquals(random, next(place, random)!!.trigger)
        assertEquals(place, next(place)!!.trigger)
    }

    @Test
    fun `paused and done reminders have no next fire`() {
        val tonight = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30))
        assertNull(next(tonight, status = Status.PAUSED))
        assertNull(next(tonight, status = Status.DONE))
    }

    @Test
    fun `a wall time inside the spring-forward gap moves forward, one in the autumn overlap takes its first occurrence`() {
        // Madrid, 2026-03-29: 02:00 CET jumps to 03:00 CEST. 02:30 does not exist and resolves
        // to 03:30 CEST = 01:30Z.
        val gap = Trigger.AtDateTime(LocalDateTime.of(2026, 3, 29, 2, 30))
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), scheduledAt(gap, at = Instant.parse("2026-03-29T00:00:00Z")))
        // 2026-10-25: 03:00 CEST falls back to 02:00 CET. 02:30 happens twice; the first is +02:00 = 00:30Z.
        val overlap = Trigger.AtDateTime(LocalDateTime.of(2026, 10, 25, 2, 30))
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), scheduledAt(overlap, at = Instant.parse("2026-10-24T23:00:00Z")))
    }
}

/** What conditions do to the moment a rule picks. */
class RuleNextFireTest {

    private val zone = Fixtures.zone
    private val defaultTime = Fixtures.defaultTime
    private val now = Fixtures.now
    private val everyDayAtNine = Trigger.AtTime(LocalTime.of(9, 0), DayOfWeek.entries.toSet())

    private fun at(rule: TriggerRule): Instant? =
        (nextFireOfRule(rule, "r1", now, zone, defaultTime) as? NextFire.Scheduled)?.at

    @Test
    fun `a rule with no conditions is just its trigger`() {
        assertEquals(Fixtures.local(2026, 8, 28, 9, 0), at(TriggerRule(everyDayAtNine)))
    }

    @Test
    fun `a condition skips the moments it does not hold at`() {
        // Thursday 15:00; nine o'clock on weekdays, but only on Mondays.
        val mondaysOnly = TriggerRule(everyDayAtNine, listOf(Condition.TimeWindow(LocalTime.of(8, 0), LocalTime.of(10, 0), setOf(DayOfWeek.MONDAY))))
        assertEquals(Fixtures.local(2026, 8, 31, 9, 0), at(mondaysOnly))
    }

    @Test
    fun `a rule that can never hold answers never instead of looping`() {
        val impossible = TriggerRule(everyDayAtNine, listOf(Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))))
        assertNull(at(impossible))
    }

    @Test
    fun `a place is judged when it happens, so conditions leave it alone`() {
        val place = Trigger.Location(40.4, -3.7, 200, Transition.ENTER, "Casa")
        val rule = TriggerRule(place, listOf(Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))))
        assertEquals(NextFire.WhenAt(place), nextFireOfRule(rule, "r1", now, zone, defaultTime))
    }

    @Test
    fun `the earliest rule wins across a reminder`() {
        val reminder = Fixtures.reminder(everyDayAtNine).copy(
            rules = listOf(
                TriggerRule(everyDayAtNine, listOf(Condition.TimeWindow(LocalTime.of(8, 0), LocalTime.of(10, 0), setOf(DayOfWeek.MONDAY)))),
                TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30))),
            ),
        )
        assertEquals(
            Fixtures.local(2026, 8, 27, 21, 30),
            (nextFire(reminder, now, zone, defaultTime) as NextFire.Scheduled).at,
        )
    }
}
