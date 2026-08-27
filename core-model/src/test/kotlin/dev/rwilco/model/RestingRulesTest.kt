package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

/**
 * When rules start speaking again after a "hecho".
 *
 * An anchored recurrence on a reminder that has rules of its own is a *rest*, not a ring: it
 * says which day the reminder comes back on, and the rules say when in that day. The two were
 * one for a while — the rest ended at the hour "the next day" starts at — and that hour then
 * stood in front of every rule due earlier in the morning, which is a reminder that quietly
 * never rings again.
 *
 * August 2026: the 26th is a Wednesday and the 27th a Thursday, both working days.
 */
class RestingRulesTest {

    /** Nine in the morning, the default, and deliberately later than the windows below. */
    private val dayStart: LocalTime = LocalTime.of(9, 0)

    private val work = Trigger.Location(40.4735, -3.6829, 200, Presence.INSIDE, "Ciudad BBVA")

    private fun daily(vararg triggers: Trigger, dealt: Instant, fired: Instant, match: RuleMatch = RuleMatch.TOGETHER) = Reminder(
        id = "r1",
        text = "Registra inicio jornada",
        rules = triggers.map { TriggerRule(it) },
        ruleMatch = match,
        recurrence = Recurrence.After(1, RecurrenceUnit.DAYS),
        createdAt = local(2026, 8, 20, 9, 0),
        updatedAt = local(2026, 8, 20, 9, 0),
        lastDealtAt = dealt,
        lastFiredAt = fired,
    )

    @Test
    fun `a morning window is not swallowed by the hour the next day starts at`() {
        // "Al llegar a Ciudad BBVA, a la vez que entre las siete y las ocho, y al día siguiente
        // desde que lo marcas hecho." Dealt with on arrival at half seven on Wednesday.
        val reminder = daily(
            work,
            Trigger.Interval(LocalTime.of(7, 0), LocalTime.of(8, 0), WEEKDAYS),
            dealt = local(2026, 8, 26, 7, 30),
            fired = local(2026, 8, 26, 7, 0),
        )
        val midnight = local(2026, 8, 27, 0, 0)
        assertEquals(
            local(2026, 8, 27, 0, 0),
            reminder.restUntil(zone, dayStart),
            "the rules come back with the day, not at the hour the day is said to start",
        )
        assertEquals(
            local(2026, 8, 27, 7, 0),
            nextWake(reminder, midnight, zone, defaultTime, dayStart)?.at,
            "this morning's window, seven hours off — not tomorrow's",
        )
        // And Home says what it will actually do: ring on arrival, no earlier than seven.
        val next = nextFire(reminder, midnight, zone, defaultTime, dayStart)
        assertEquals(NextFire.WhenAt(work), next, "the place is the ring; the window is its hours")
    }

    @Test
    fun `a daily hour equal to the start of the day does not skip a day`() {
        // The same fault, in the plainest shape there is: "todos los días a las nueve", dealt
        // with at five past, with nine as the hour the next day starts at. The rest used to end
        // exactly on the moment, which is not *after* it, so tomorrow's nine went missing.
        val reminder = daily(
            Trigger.AtTime(LocalTime.of(9, 0), DayOfWeek.entries.toSet()),
            dealt = local(2026, 8, 26, 9, 5),
            fired = local(2026, 8, 26, 9, 0),
            match = RuleMatch.ANY,
        )
        assertEquals(
            local(2026, 8, 27, 9, 0),
            nextWake(reminder, local(2026, 8, 26, 9, 6), zone, defaultTime, dayStart)?.at,
            "nine tomorrow, which is the whole arrangement",
        )
    }

    @Test
    fun `a reminder with nothing but a place still rests until the day starts`() {
        // Nothing here names an hour, so there is nothing for the rest to stand in front of —
        // and without the day's start hour "the next day" would begin one minute past midnight,
        // which is the same evening to anybody who was out.
        val bins = daily(
            Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa"),
            dealt = local(2026, 8, 26, 19, 30),
            fired = local(2026, 8, 26, 19, 0),
            match = RuleMatch.ANY,
        )
        assertEquals(local(2026, 8, 27, 9, 0), bins.restUntil(zone, dayStart))
    }

    @Test
    fun `a rest measured in hours is exact and keeps its minutes`() {
        val pills = Reminder(
            id = "r2",
            text = "Pastillas",
            rules = listOf(TriggerRule(Trigger.AtTime(LocalTime.of(8, 0), DayOfWeek.entries.toSet()))),
            recurrence = Recurrence.After(6, RecurrenceUnit.HOURS),
            createdAt = local(2026, 8, 20, 9, 0),
            updatedAt = local(2026, 8, 20, 9, 0),
            lastDealtAt = local(2026, 8, 26, 8, 5),
            lastFiredAt = local(2026, 8, 26, 8, 0),
        )
        assertEquals(local(2026, 8, 26, 14, 5), pills.restUntil(zone, dayStart), "six hours, to the minute")
    }

    @Test
    fun `a window that opens after the day starts is left where it was`() {
        // Nothing is pulled forward for its own sake: a rule due in the evening is due in the
        // evening either way, and the rest ending at midnight changes nothing about it.
        val evening = daily(
            Trigger.Interval(LocalTime.of(19, 0), LocalTime.of(21, 30), WEEKDAYS),
            dealt = local(2026, 8, 26, 19, 30),
            fired = local(2026, 8, 26, 19, 0),
            match = RuleMatch.ANY,
        )
        assertEquals(
            local(2026, 8, 27, 19, 0),
            nextWake(evening, local(2026, 8, 27, 0, 0), zone, defaultTime, dayStart)?.at,
        )
    }

    private companion object {
        val WEEKDAYS = setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        )
    }
}
