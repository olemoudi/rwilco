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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Days, hours and stretches put together, held to what they say (the trigger-contract audit,
 * 2026-11-04).
 *
 * Calendar: 2026-08-27 is a Thursday, 08-28 a Friday, 08-29 a Saturday; 2026-11-04 a Wednesday.
 */
class CombinationContractTest {

    private fun t(hour: Int, minute: Int = 0): LocalTime = LocalTime.of(hour, minute)

    private fun row(vararg rules: TriggerRule, match: RuleMatch = RuleMatch.ANY, createdAt: java.time.Instant = now) =
        Reminder(id = "r1", text = "x", rules = rules.toList(), ruleMatch = match, createdAt = createdAt, updatedAt = createdAt)

    @Test
    fun `todos with a weekday and an hour, written on a saturday, rings on friday at the hour`() {
        val saturday = local(2026, 8, 29, 15, 0)
        val r = row(TriggerRule(Trigger.Weekday(setOf(DayOfWeek.FRIDAY))), TriggerRule(Trigger.TimeOfDay(t(20))), match = RuleMatch.ALL, createdAt = saturday)
        val phone = Simulation(r, saturday)
        val rings = phone.run(local(2026, 9, 5, 23, 0))
        assertEquals(listOf(local(2026, 9, 4, 20, 0)), rings.map { it.at }, "Friday at eight in the evening, not at its opening")
    }

    @Test
    fun `todos with a stretch of the day, a tick from yesterday's evening does not count today`() {
        val r = row(TriggerRule(Trigger.Interval(t(18), t(22))), TriggerRule(Trigger.TimeOfDay(t(20))), match = RuleMatch.ALL)
        // Yesterday's 20:00 was written down; today's stretch opens at 18:00.
        val ticked = r.copy(firedRules = setOf(1))
        assertEquals(FiringOutcome.Wait(setOf(0)), outcomeOfFiring(ticked, 0, local(2026, 8, 28, 18, 0), zone))
        // Inside today's stretch the 20:00 completes it.
        assertEquals(FiringOutcome.Ring, outcomeOfFiring(r.copy(firedRules = setOf(0)), 1, local(2026, 8, 28, 20, 0), zone))
        // A stretch ticked on Friday is over by Saturday, and stops counting, as leaving does.
        val fridays = row(TriggerRule(Trigger.Interval(t(18), t(22), setOf(DayOfWeek.FRIDAY))), TriggerRule(Trigger.TimeOfDay(t(20))), match = RuleMatch.ALL)
        assertEquals(FiringOutcome.Wait(setOf(1)), outcomeOfFiring(fridays.copy(firedRules = setOf(0)), 1, local(2026, 8, 29, 20, 0), zone))
    }

    @Test
    fun `todos with a date and a weekday keeps the date that cannot come again`() {
        val r = row(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 25, 10, 0))), TriggerRule(Trigger.Weekday(setOf(DayOfWeek.FRIDAY))), match = RuleMatch.ALL)
        assertEquals(FiringOutcome.Ring, outcomeOfFiring(r.copy(firedRules = setOf(0)), 1, local(2026, 8, 28, 8, 0), zone))
    }

    @Test
    fun `a date range with hours of its own opens at them`() {
        val today = local(2026, 11, 4, 10, 0)
        val fenced = listOf(TriggerRule(Trigger.DateRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 15)), listOf(Condition.TimeWindow(t(18), t(22)))))
        assertTrue(warnings(fenced, today, zone, defaultTime).none { it is ValidationWarning.NeverFires })
        assertEquals(Wake(local(2026, 11, 4, 18, 0), 0), nextWake(row(*fenced.toTypedArray(), createdAt = today), today, zone, defaultTime))
        // "Del 10 al 14, a la vez de 18 a 22" is no longer "never", so it is not read as "cualquiera".
        val pair = listOf(TriggerRule(Trigger.DateRange(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 14))), TriggerRule(Trigger.Interval(t(18), t(22))))
        assertTrue(warnings(pair, today, zone, defaultTime, RuleMatch.TOGETHER).none { it is ValidationWarning.NeverFires })
        assertEquals(local(2026, 11, 10, 18, 0), (nextFire(row(*pair.toTypedArray(), match = RuleMatch.TOGETHER, createdAt = today), today, zone, defaultTime) as NextFire.Scheduled).at)
    }

    @Test
    fun `a stretch saved while it is open rings in what is left of it`() {
        val halfFive = local(2026, 8, 27, 17, 30)
        val evening = row(TriggerRule(Trigger.Interval(t(17), t(19))), createdAt = halfFive)
        assertEquals(Wake(local(2026, 8, 27, 17, 31), 0), nextWake(evening, halfFive, zone, defaultTime))
        val friday = local(2026, 8, 28, 10, 0)
        val fridays = row(TriggerRule(Trigger.Weekday(setOf(DayOfWeek.FRIDAY))), createdAt = friday)
        assertEquals(Wake(local(2026, 8, 28, 10, 1), 0), nextWake(fridays, friday, zone, defaultTime))
        // A range on its last day, written after its hour, is not "ya ha pasado".
        val endsToday = row(TriggerRule(Trigger.DateRange(LocalDate.of(2026, 8, 25), LocalDate.of(2026, 8, 28))), createdAt = friday)
        assertEquals(Wake(local(2026, 8, 28, 10, 1), 0), nextWake(endsToday, friday, zone, defaultTime))
        assertTrue(warnings(endsToday.rules, friday, zone, defaultTime).none { it is ValidationWarning.InPast })
        // Once it has rung, the stretch is spent and the next opening is the next one.
        val rang = evening.copy(lastFiredAt = local(2026, 8, 27, 17, 31), lastFiredRule = 0)
        assertEquals(Wake(local(2026, 8, 28, 17, 0), 0), nextWake(rang, local(2026, 8, 27, 17, 32), zone, defaultTime))
        // Written in the small hours, the stretch waits for the waking hours: Friday opens at eight.
        val night = row(TriggerRule(Trigger.Weekday(setOf(DayOfWeek.FRIDAY))), createdAt = local(2026, 8, 28, 2, 0))
        assertEquals(Wake(local(2026, 8, 28, 8, 0), 0), nextWake(night, local(2026, 8, 28, 2, 0), zone, defaultTime))
    }

    @Test
    fun `resumed after its deadline closed, a reminder starts a fresh round`() {
        val paused = local(2026, 8, 27, 15, 0)
        val r = row(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 19, 0))), TriggerRule(Trigger.Location(40.4, -3.7, 200, Presence.INSIDE, "Casa")), match = RuleMatch.ALL, createdAt = paused)
            .copy(deadline = Deadline.Window(t(18), t(22)), expiresAt = local(2026, 8, 28, 22, 0), status = Status.PAUSED, firedRules = setOf(1))
        val back = local(2026, 8, 30, 10, 0)
        val round = r.roundOnResume(back, zone, defaultTime)!!
        assertEquals(emptySet<Int>(), round.firedRules)
        assertTrue(round.expiresAt == null || round.expiresAt!! > back, "never a deadline already gone: ${round.expiresAt}")
        // A round still open is left alone.
        assertNull(r.copy(expiresAt = local(2026, 8, 31, 22, 0)).roundOnResume(back, zone, defaultTime))
    }
}
