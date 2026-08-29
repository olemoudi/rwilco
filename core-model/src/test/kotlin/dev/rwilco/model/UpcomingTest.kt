package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.reminder
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class UpcomingTest {

    private val defaultTime = LocalTime.of(9, 0)
    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0): Instant =
        LocalDateTime.of(2026, month, day, hour, minute).atZone(zone).toInstant()
    private fun moments(reminder: Reminder) = upcomingMoments(reminder, now, zone, defaultTime).map { (it as NextFire.Scheduled).at }

    @Test
    fun `a daily hour gives the next three mornings`() {
        val daily = reminder(Trigger.TimeOfDay(LocalTime.of(9, 0)))
        assertEquals(listOf(at(8, 28, 9), at(8, 29, 9), at(8, 30, 9)), moments(daily))
    }

    @Test
    fun `a single moment is one line, and a spent one is none`() {
        val once = reminder(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30)))
        assertEquals(listOf(at(8, 27, 21, 30)), moments(once))
        assertTrue(upcomingMoments(once.copy(lastFiredAt = at(8, 27, 21, 30)), now, zone, defaultTime).isEmpty())
    }

    @Test
    fun `a set that needs all of its rules stops at the ring`() {
        val both = reminder(
            Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 18, 0)),
            Trigger.TimeOfDay(LocalTime.of(9, 0)),
        ).copy(ruleMatch = RuleMatch.ALL)
        assertEquals(1, upcomingMoments(both, now, zone, defaultTime).size)
    }

    @Test
    fun `a random window is shown once and never the draw after it`() {
        val random = reminder(Trigger.Random(1, Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0), emptySet()))
        val upcoming = upcomingMoments(random, now, zone, defaultTime)
        assertEquals(1, upcoming.size)
        assertTrue(upcoming.single() is NextFire.Sometime)
    }

    @Test
    fun `a calendar with no rules walks its own dates`() {
        val mondays = reminder().copy(
            recurrence = Recurrence.Calendar(
                Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 27), every = 1, unit = RepeatUnit.WEEK, time = LocalTime.of(9, 0), days = setOf(DayOfWeek.MONDAY)),
            ),
        )
        assertEquals(listOf(at(8, 31, 9), at(9, 7, 9), at(9, 14, 9)), moments(mondays))
    }

    @Test
    fun `a span from the hecho has one moment to give, and a place none`() {
        val everySixHours = reminder().copy(recurrence = Recurrence.After(6, RecurrenceUnit.HOURS))
        assertEquals(1, upcomingMoments(everySixHours, now, zone, defaultTime).size)
        val place = reminder(Trigger.Location(40.4, -3.7, 200, Presence.INSIDE, "Casa"))
        assertTrue(upcomingMoments(place, now, zone, defaultTime).isEmpty())
    }
}
