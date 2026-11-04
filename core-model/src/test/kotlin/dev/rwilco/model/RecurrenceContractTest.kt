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
 * What "Vuelve" promises, held to it (the trigger-contract audit, 2026-11-04): when a reminder
 * comes back, it comes back as what it says — its rules asked again, on its own dates, from the
 * day it rang, at the hour it was written for — and a question a routine can never ask is said.
 *
 * Calendar: 2026-08-27 is a Thursday, 08-31 a Monday.
 */
class RecurrenceContractTest {

    private val home = Trigger.Location(40.4169, -3.7035, radiusM = 200, presence = Presence.INSIDE, label = "Casa")

    private fun t(hour: Int, minute: Int = 0): LocalTime = LocalTime.of(hour, minute)

    private fun row(vararg rules: TriggerRule, recurrence: Recurrence, match: RuleMatch = RuleMatch.ANY, createdAt: java.time.Instant = now) =
        Reminder(id = "r1", text = "x", rules = rules.toList(), recurrence = recurrence, ruleMatch = match, createdAt = createdAt, updatedAt = createdAt)

    @Test
    fun `under todos and desde que suena, the next round asks every rule again`() {
        // "A las 21:00, y mientras estoy en casa, todos, cada día desde que suena", rung and not
        // answered. The ring ticked every rule, nothing was left to ask after the rest, and the
        // span rang alone at nine every morning wherever the phone was.
        val r = row(TriggerRule(home), TriggerRule(Trigger.TimeOfDay(t(21))), recurrence = Recurrence.After(1, RecurrenceUnit.DAYS, RecurrenceFrom.RANG), match = RuleMatch.ALL)
        assertEquals(emptySet<Int>(), r.ticksOnRing, "the ring ends the round")
        assertEquals(setOf(0, 1), r.copy(recurrence = Recurrence.After(1, RecurrenceUnit.DAYS)).ticksOnRing, "«desde el hecho» waits for it, complete")
        val phone = Simulation(r, local(2026, 8, 27, 20, 0))
        phone.arrive(0)
        val first = phone.step()!!
        assertEquals(local(2026, 8, 27, 21, 0), first.at)
        assertEquals(emptySet<Int>(), phone.reminder.firedRules)
        // The next day the 21:00 is a note again, not a ring at nine: the place has to be true too.
        assertEquals(Wake(local(2026, 8, 28, 21, 0), 1), phone.arm())
        val rings = phone.run(local(2026, 8, 30, 23, 0))
        assertTrue(rings.isEmpty(), "never at home again, never rings: $rings")
    }

    @Test
    fun `a calendar with a dated rule, rung and not answered, comes back on its next date`() {
        val monthly = Recurrence.Calendar(Trigger.Repeat(startsOn = LocalDate.of(2026, 11, 26), unit = RepeatUnit.MONTH, time = t(20), monthly = MonthlyOn.Day(26)))
        val r = row(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 26, 20, 0))), recurrence = monthly, createdAt = local(2026, 11, 20, 12, 0))
            .copy(lastFiredAt = local(2026, 11, 26, 20, 0), lastFiredRule = 0)
        val after = local(2026, 11, 27, 10, 0)
        assertEquals(Wake(local(2026, 12, 26, 20, 0), null), nextWake(r, after, zone, defaultTime))
        assertNull(nextFire(r, after, zone, defaultTime), "Home still has it as unanswered")
    }

    @Test
    fun `a calendar beside a rule that keeps speaking leaves the rule to it`() {
        // "De 16 a 17 los viernes, y vuelve el primer viernes": ignored, the window speaks every
        // Friday, as it always did; the calendar only rests it after a "hecho".
        val firstFriday = Recurrence.Calendar(Trigger.Repeat(startsOn = LocalDate.of(2026, 9, 1), unit = RepeatUnit.MONTH, time = t(16), monthly = MonthlyOn.Nth(1, DayOfWeek.FRIDAY)))
        val r = row(TriggerRule(Trigger.Interval(t(16), t(17), setOf(DayOfWeek.FRIDAY))), recurrence = firstFriday)
            .copy(lastFiredAt = local(2026, 8, 28, 16, 0), lastFiredRule = 0)
        assertEquals(Wake(local(2026, 9, 4, 16, 0), 0), nextWake(r, local(2026, 8, 28, 16, 5), zone, defaultTime))
    }

    @Test
    fun `a hecho after midnight answers the evening it rang on`() {
        val phone = Simulation(row(TriggerRule(Trigger.TimeOfDay(t(22))), recurrence = Recurrence.After(1, RecurrenceUnit.DAYS)), now)
        assertEquals(local(2026, 8, 27, 22, 0), phone.step()!!.at)
        phone.now = local(2026, 8, 28, 0, 15)
        phone.deal(Simulation.Deal.Done)
        assertEquals(Wake(local(2026, 8, 28, 22, 0), 0), phone.arm(), "the 28th's evening still rings")
    }

    @Test
    fun `a weekly monday answered on tuesday morning is next monday's`() {
        val created = local(2026, 8, 30, 12, 0)
        val monday = row(TriggerRule(Trigger.TimeOfDay(t(22), setOf(DayOfWeek.MONDAY))), recurrence = Recurrence.After(1, RecurrenceUnit.WEEKS), createdAt = created)
        val phone = Simulation(monday, created)
        assertEquals(local(2026, 8, 31, 22, 0), phone.step()!!.at)
        phone.now = local(2026, 9, 1, 7, 0)
        phone.deal(Simulation.Deal.Done)
        assertEquals(Wake(local(2026, 9, 7, 22, 0), 0), phone.arm())
    }

    @Test
    fun `a hecho in hours still counts from the hecho`() {
        // "Cada 6 h desde el hecho" is six hours between doses: nothing about the ring's day.
        val phone = Simulation(row(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 22, 0))), recurrence = Recurrence.After(6, RecurrenceUnit.HOURS)), now)
        phone.step()
        phone.now = local(2026, 8, 28, 0, 15)
        phone.deal(Simulation.Deal.Done)
        assertEquals(Wake(local(2026, 8, 28, 6, 15), null), phone.arm())
    }

    @Test
    fun `a span picked under a dated rule takes the rule's hour`() {
        val dated = listOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 26, 20, 0)), listOf(Condition.AtPlace(40.4, -3.7, 200, "Casa", inside = true))))
        assertEquals(
            Recurrence.After(1, RecurrenceUnit.MONTHS, hour = RecurrenceHour.At(t(20))),
            Recurrence.After(1, RecurrenceUnit.MONTHS).adoptingHourOf(dated),
        )
        // An hour somebody chose stays; hours are exact; a rule that keeps speaking says its own.
        val chosen = Recurrence.After(1, RecurrenceUnit.MONTHS, hour = RecurrenceHour.Same)
        assertEquals(chosen, chosen.adoptingHourOf(dated))
        val hours = Recurrence.After(6, RecurrenceUnit.HOURS)
        assertEquals(hours, hours.adoptingHourOf(dated))
        val daily = listOf(TriggerRule(Trigger.TimeOfDay(t(20))))
        assertEquals(Recurrence.After(1, RecurrenceUnit.DAYS), Recurrence.After(1, RecurrenceUnit.DAYS).adoptingHourOf(daily))
        // Two dates at two hours name no one hour.
        val two = dated + TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 27, 9, 0)))
        assertEquals(Recurrence.After(1, RecurrenceUnit.MONTHS), Recurrence.After(1, RecurrenceUnit.MONTHS).adoptingHourOf(two))
    }

    @Test
    fun `a daily routine's question after its deadline hour is never asked, and is said`() {
        // "Cada día, plazo a las 20:00": the questions open at 14:00 and the deadline rings at
        // 20:00, so a question at 21:00 is never put.
        val span = Recurrence.Since(1, RecurrenceUnit.DAYS, hour = RecurrenceHour.At(t(20)))
        val routine = row(TriggerRule(Trigger.TimeOfDay(t(21))), TriggerRule(Trigger.TimeOfDay(t(18))), recurrence = span)
        assertEquals(listOf(0), routine.questionsNeverAsked(zone, defaultTime))
        // At 18:00 it is asked; and a three-week routine has room for any hour of the day.
        assertEquals(emptyList<Int>(), routine.copy(recurrence = Recurrence.Since(21, RecurrenceUnit.DAYS, hour = RecurrenceHour.At(t(20)))).questionsNeverAsked(zone, defaultTime))
        // A question on some days only is not "never": it depends on the day the count starts.
        val mondays = row(TriggerRule(Trigger.TimeOfDay(t(21), setOf(DayOfWeek.MONDAY))), recurrence = span)
        assertEquals(emptyList<Int>(), mondays.questionsNeverAsked(zone, defaultTime))
    }
}
