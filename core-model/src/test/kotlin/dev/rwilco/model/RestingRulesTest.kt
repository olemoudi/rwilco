package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
    private val home = Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa")

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

    /**
     * The same boundary for a rest in hours, which is exact and so lands *on* the rule's moment:
     * "a las 9, y vuelve cada 24 h desde que suena" rang at nine, rested until nine the next day,
     * and nine is not *after* nine — so it rang every other day. The ring is written down when the
     * alarm arrives, a moment past the hour, which put the rest past the moment as well.
     */
    @Test
    fun `a rest in hours does not swallow the rule's moment it lands on`() {
        val daily = Trigger.AtTime(LocalTime.of(9, 0), DayOfWeek.entries.toSet())
        val pills = Reminder(
            id = "r3",
            text = "Pastillas",
            rules = listOf(TriggerRule(daily)),
            recurrence = Recurrence.After(24, RecurrenceUnit.HOURS, from = RecurrenceFrom.RANG),
            createdAt = local(2026, 8, 20, 9, 0),
            updatedAt = local(2026, 8, 20, 9, 0),
            lastFiredAt = local(2026, 8, 26, 9, 0).plusMillis(800),
        )
        assertEquals(local(2026, 8, 27, 9, 0), nextWake(pills, local(2026, 8, 26, 9, 1), zone, defaultTime, dayStart)?.at, "nine tomorrow, not the day after")

        val twice = pills.copy(
            rules = listOf(TriggerRule(Trigger.AtTime(LocalTime.of(8, 0), DayOfWeek.entries.toSet())), TriggerRule(Trigger.AtTime(LocalTime.of(20, 0), DayOfWeek.entries.toSet()))),
            ruleMatch = RuleMatch.ANY,
            recurrence = Recurrence.After(12, RecurrenceUnit.HOURS, from = RecurrenceFrom.RANG),
            lastFiredAt = local(2026, 8, 26, 8, 0).plusMillis(400),
        )
        assertEquals(local(2026, 8, 26, 20, 0), nextWake(twice, local(2026, 8, 26, 8, 1), zone, defaultTime, dayStart)?.at, "the evening one, every evening")
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

    /** Whether rule [index]'s circle is being watched for real at [at]: not resting, not waiting for its hours. */
    private fun Reminder.watchingAt(index: Int, at: Instant): Boolean =
        watchedCircles(at, zone, defaultTime, dayStart = dayStart).any { it.ruleIndex == index && !it.resting && it.opensAt == null }

    @Test
    fun `a window written as «sólo si» on the place says the hour as well`() {
        // The same sentence as the first test, written with the other control: "al llegar al
        // trabajo, y sólo si es de siete a nueve". The rest only asked the trigger, and a place
        // names no hour, so it ended at nine — when the window had just closed.
        val reminder = daily(
            work,
            dealt = local(2026, 8, 26, 7, 30),
            fired = local(2026, 8, 26, 7, 30),
            match = RuleMatch.ANY,
        ).let { it.copy(rules = listOf(TriggerRule(work, listOf(Condition.TimeWindow(LocalTime.of(7, 0), LocalTime.of(9, 0), WEEKDAYS))))) }
        assertEquals(local(2026, 8, 27, 0, 0), reminder.restUntil(zone, dayStart))
        assertTrue(reminder.watchingAt(0, local(2026, 8, 27, 7, 30)), "Thursday's arrival at half seven is watched for")
    }

    @Test
    fun `the hour of the last «hecho» only says which day once a window says the hour`() {
        // The owner's medicine (2026-11-10): at work on weekdays from eight to eleven, or at home
        // at weekends from eight to ten in the evening, and back the next day "a la misma hora".
        // Dealt with at noon, the rest ran to Thursday noon and Thursday's morning was lost.
        val medicine = Reminder(
            id = "med",
            text = "Medicina",
            rules = listOf(
                TriggerRule(work, listOf(Condition.TimeWindow(LocalTime.of(8, 0), LocalTime.of(11, 0), WEEKDAYS))),
                TriggerRule(home, listOf(Condition.TimeWindow(LocalTime.of(20, 0), LocalTime.of(22, 0), WEEKEND))),
            ),
            ruleMatch = RuleMatch.ANY,
            recurrence = Recurrence.After(1, RecurrenceUnit.DAYS, hour = RecurrenceHour.Same),
            createdAt = local(2026, 8, 20, 9, 0),
            updatedAt = local(2026, 8, 20, 9, 0),
            lastDealtAt = local(2026, 8, 26, 12, 0),
            lastFiredAt = local(2026, 8, 26, 8, 30),
        )
        assertEquals(local(2026, 8, 27, 0, 0), medicine.restUntil(zone, dayStart))
        assertTrue(medicine.watchingAt(0, local(2026, 8, 27, 8, 30)), "work on Thursday morning, as on the first day")

        // And the weekend: dealt with at home on Saturday night, back on Sunday's evening.
        val sunday = medicine.copy(lastDealtAt = local(2026, 8, 29, 21, 0), lastFiredAt = local(2026, 8, 29, 20, 30))
        assertEquals(local(2026, 8, 30, 0, 0), sunday.restUntil(zone, dayStart))
        assertTrue(sunday.watchingAt(1, local(2026, 8, 30, 20, 30)), "home on Sunday evening")
    }

    @Test
    fun `a window still open past midnight belongs to the night that was dealt with`() {
        // "En casa, y sólo si es de diez a dos": a moment past midnight is the window that opened
        // the evening before (Condition.TimeWindow.holdsAt). Ending at midnight, the rest handed
        // the rules the same night back and it rang again at home ten minutes later.
        val night = Condition.TimeWindow(LocalTime.of(22, 0), LocalTime.of(2, 0))
        val bedtime = daily(home, dealt = local(2026, 8, 29, 23, 0), fired = local(2026, 8, 29, 22, 30), match = RuleMatch.ANY)
            .let { it.copy(rules = listOf(TriggerRule(home, listOf(night)))) }
        assertEquals(local(2026, 8, 30, 2, 0), bedtime.restUntil(zone, dayStart))
        assertFalse(bedtime.watchingAt(0, local(2026, 8, 30, 0, 30)), "not the same night again")
        assertTrue(bedtime.watchingAt(0, local(2026, 8, 30, 22, 30)), "the next night")

        // The "a la vez" shape had it too: the interval folds into the place as the same window.
        val together = daily(home, Trigger.Interval(LocalTime.of(22, 0), LocalTime.of(2, 0)), dealt = local(2026, 8, 29, 23, 0), fired = local(2026, 8, 29, 22, 30))
        assertEquals(local(2026, 8, 30, 2, 0), together.restUntil(zone, dayStart))
    }

    @Test
    fun `a place fenced to days and no hours still rests until the day starts`() {
        // "Y sólo los lunes" names a day, not an hour: nothing for the rest to stand in front of.
        val mondays = daily(home, dealt = local(2026, 8, 24, 19, 30), fired = local(2026, 8, 24, 19, 0), match = RuleMatch.ANY)
            .let { it.copy(rules = listOf(TriggerRule(home, listOf(Condition.OnDays(setOf(DayOfWeek.MONDAY)))))) }
        assertEquals(local(2026, 8, 25, 9, 0), mondays.restUntil(zone, dayStart))
    }

    private companion object {
        val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val WEEKDAYS = setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        )
    }
}
