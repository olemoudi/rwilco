package dev.rwilco.model

import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalTime

/** now is Thursday 2026-08-27 at 15:00 in Madrid. */
class SnoozeTest {

    private val friday = DayOfWeek.FRIDAY
    private val halfPastEight = LocalTime.of(20, 30)

    private fun until(snooze: Snooze, from: java.time.Instant = now, day: DayOfWeek = friday, time: LocalTime = halfPastEight) =
        snooze.until(from, zone, day, time)

    @Test
    fun `the short ones are plain arithmetic`() {
        assertEquals(local(2026, 8, 27, 15, 10), until(Snooze.TEN_MINUTES))
        assertEquals(local(2026, 8, 27, 17, 0), until(Snooze.TWO_HOURS))
    }

    @Test
    fun `tomorrow is the same time tomorrow, not the default time`() {
        assertEquals(local(2026, 8, 28, 15, 0), until(Snooze.TOMORROW))
    }

    @Test
    fun `next week is this day and hour, seven days on`() {
        assertEquals(local(2026, 9, 3, 15, 0), until(Snooze.NEXT_WEEK))
    }

    @Test
    fun `the weekend is the next friday evening, as configured`() {
        assertEquals(local(2026, 8, 28, 20, 30), until(Snooze.WEEKEND))
        // Somebody whose weekend starts on Saturday morning gets Saturday morning.
        assertEquals(
            local(2026, 8, 29, 10, 0),
            until(Snooze.WEEKEND, day = DayOfWeek.SATURDAY, time = LocalTime.of(10, 0)),
        )
    }

    @Test
    fun `on the weekend day itself it is tonight, unless tonight has passed`() {
        val fridayAfternoon = local(2026, 8, 28, 15, 0)
        assertEquals(local(2026, 8, 28, 20, 30), until(Snooze.WEEKEND, from = fridayAfternoon))
        val fridayNight = local(2026, 8, 28, 23, 0)
        assertEquals(local(2026, 9, 4, 20, 30), until(Snooze.WEEKEND, from = fridayNight))
    }

    @Test
    fun `a wall-clock snooze keeps its hour across a clock change`() {
        // Sunday 25 October 2026 is when Madrid goes back an hour; 24 hours later is not the
        // same time of day, and "tomorrow" has to mean the time of day.
        val dayBefore = local(2026, 10, 24, 22, 0)
        assertEquals(local(2026, 10, 25, 22, 0), until(Snooze.TOMORROW, from = dayBefore))
    }
}
