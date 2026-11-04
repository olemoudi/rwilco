package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * What the reliability review of the trigger-contract round found in the time, snooze and
 * recurrence core (2026-11-04), each one a failing test when it was found and a regression test
 * since its fix (0.205.0). The letters and numbers are the review's own.
 */
class ReviewFindingsTest {

    private fun t(hour: Int, minute: Int = 0): LocalTime = LocalTime.of(hour, minute)

    private val home = Trigger.Location(40.4169, -3.7035, radiusM = 200, presence = Presence.INSIDE, label = "Casa")
    private val homeState = Condition.AtPlace(40.4169, -3.7035, 200, "Casa", inside = true)

    private fun row(vararg rules: TriggerRule, recurrence: Recurrence = Recurrence.None, match: RuleMatch = RuleMatch.ANY, createdAt: Instant) =
        Reminder(id = "r1", text = "x", rules = rules.toList(), recurrence = recurrence, ruleMatch = match, createdAt = createdAt, updatedAt = createdAt)

    @Test
    fun `P1 a hecho before a late day start answers the evening it rang on`() {
        val created = local(2026, 8, 27, 12, 0)
        val phone = Simulation(row(TriggerRule(Trigger.TimeOfDay(t(22))), recurrence = Recurrence.After(1, RecurrenceUnit.DAYS), createdAt = created), created, dayStart = t(10))
        assertEquals(local(2026, 8, 27, 22, 0), phone.step()!!.at)
        phone.now = local(2026, 8, 28, 9, 30)
        phone.deal(Simulation.Deal.Done)
        assertEquals(Wake(local(2026, 8, 28, 22, 0), 0), phone.arm())
    }

    @Test
    fun `P26 a stretch dropped at its place condition is not handed back as a moment already past`() {
        val written = local(2026, 8, 27, 17, 30)
        val r = row(TriggerRule(Trigger.Interval(t(17), t(19)), listOf(homeState)), createdAt = written)
        assertEquals(Wake(local(2026, 8, 27, 17, 31), 0), nextWake(r, written, zone, defaultTime))
        val later = local(2026, 8, 27, 17, 31).plusSeconds(2)
        val again = nextWake(r, later, zone, defaultTime)
        assertTrue(again == null || again.at > later, "re-armed for $again at $later")
    }

    @Test
    fun `P26b under a la vez the same`() {
        val written = local(2026, 8, 27, 17, 30)
        val r = row(TriggerRule(Trigger.Interval(t(17), t(19))), TriggerRule(home), match = RuleMatch.TOGETHER, createdAt = written)
        val later = local(2026, 8, 27, 17, 45)
        val again = nextWake(r, later, zone, defaultTime)
        assertTrue(again == null || again.at > later, "re-armed for $again at $later")
    }

    @Test
    fun `P2 a stretch rung at its writing still has a net`() {
        val written = local(2026, 8, 27, 17, 30)
        val r = row(TriggerRule(Trigger.Interval(t(17), t(19))), createdAt = written)
            .copy(lastFiredAt = local(2026, 8, 27, 17, 31), lastFiredRule = 0)
        val control = row(TriggerRule(Trigger.Interval(t(17), t(19))), createdAt = local(2026, 8, 27, 16, 0))
            .copy(lastFiredAt = local(2026, 8, 27, 17, 0), lastFiredRule = 0)
        assertNotNull(control.netDue(local(2026, 8, 27, 17, 1), zone, defaultTime, SafetyNetSettings()))
        assertNotNull(r.netDue(local(2026, 8, 27, 17, 32), zone, defaultTime, SafetyNetSettings()))
    }

    @Test
    fun `P3 a set under todos desde el hecho comes back at its hour`() {
        val created = local(2026, 8, 27, 20, 0)
        val r = row(TriggerRule(home), TriggerRule(Trigger.TimeOfDay(t(21))), recurrence = Recurrence.After(1, RecurrenceUnit.DAYS), match = RuleMatch.ALL, createdAt = created)
        val phone = Simulation(r, created)
        phone.arrive(0)
        assertEquals(local(2026, 8, 27, 21, 0), phone.step()!!.at)
        val rings = phone.run(local(2026, 8, 30, 23, 0))
        assertTrue(rings.all { it.local(zone).toLocalTime() == t(21) }, "$rings")
    }

    @Test
    fun `P16 todos desde que suena keeps its plazo in the next round`() {
        val door = home.copy(onCrossing = true)
        val created = local(2026, 8, 24, 12, 0)
        val base = row(TriggerRule(door), TriggerRule(Trigger.TimeOfDay(t(21), setOf(DayOfWeek.FRIDAY))), recurrence = Recurrence.After(1, RecurrenceUnit.DAYS, RecurrenceFrom.RANG), match = RuleMatch.ALL, createdAt = created)
            .copy(deadline = Deadline.Window(t(18), t(23)))
        val r = base.copy(expiresAt = base.roundExpiry(created, zone, defaultTime))
        assertEquals(local(2026, 8, 28, 23, 0), r.expiresAt)
        val phone = Simulation(r, created)
        phone.now = local(2026, 8, 28, 19, 0)
        phone.arrive(0)
        assertEquals(local(2026, 8, 28, 21, 0), phone.step()!!.at)
        // Next round: home on Wednesday, not on Friday.
        phone.now = local(2026, 9, 2, 19, 0)
        phone.arrive(0)
        val rings = phone.run(local(2026, 9, 4, 23, 30))
        assertTrue(rings.isEmpty(), "rang on a Wednesday arrival: $rings, expires=${phone.reminder.expiresAt}")
    }

    @Test
    fun `P15 a doorway crossed before the day does not complete the set at the day's opening`() {
        val door = home.copy(onCrossing = true)
        val saturday = local(2026, 8, 29, 15, 0)
        val r = row(TriggerRule(Trigger.Weekday(setOf(DayOfWeek.FRIDAY))), TriggerRule(door), match = RuleMatch.ALL, createdAt = saturday)
        val phone = Simulation(r, saturday)
        phone.now = local(2026, 8, 29, 18, 0)
        phone.arrive(1)
        val rings = phone.run(local(2026, 9, 4, 23, 0))
        assertTrue(rings.isEmpty(), "$rings")
    }

    @Test
    fun `P44 a draw fenced to dates more than 400 days ahead is not never`() {
        val now = local(2026, 11, 4, 10, 0)
        val rule = TriggerRule(Trigger.Random(1, Period.DAY, t(10), t(20)), listOf(Condition.DateRange(LocalDate.of(2028, 3, 1), LocalDate.of(2028, 3, 5))))
        assertNotNull(nextFireOfRule(rule, "r1", now, zone, defaultTime))
        assertTrue(warnings(listOf(rule), now, zone, defaultTime).none { it is ValidationWarning.NeverFires })
    }

    @Test
    fun `P44b weekly draw 15 months ahead`() {
        val now = local(2026, 11, 4, 10, 0)
        val rule = TriggerRule(Trigger.Random(2, Period.WEEK, t(10), t(20)), listOf(Condition.DateRange(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 20))))
        assertNotNull(nextFireOfRule(rule, "r1", now, zone, defaultTime))
    }

    @Test
    fun `P31 a preset folds by its own match`() {
        val rules = listOf(TriggerRule(Trigger.AtTime(t(9), setOf(DayOfWeek.MONDAY))), TriggerRule(home))
        val created = local(2026, 8, 1, 12, 0)
        val preset = Preset(id = "p", name = "x", rules = rules, ruleMatch = RuleMatch.ALL, createdAt = created)
        val reminder = row(*rules.toTypedArray(), match = RuleMatch.ALL, createdAt = created)
        assertEquals(reminder.foldRepeats(zone).rules, preset.foldRepeats(zone).rules)
    }

    @Test
    fun `P32 a question asked on some rounds is not never`() {
        val span = Recurrence.Since(1, RecurrenceUnit.DAYS, hour = RecurrenceHour.At(t(20)))
        val routine = row(TriggerRule(Trigger.TimeOfDay(t(13))), recurrence = span, createdAt = local(2026, 8, 20, 12, 0))
        val early = routine.copy(lastDealtAt = local(2026, 8, 27, 10, 0))
        val late = routine.copy(lastDealtAt = local(2026, 8, 27, 21, 0))
        assertEquals(emptyList<Int>(), early.questionsNeverAsked(zone, defaultTime))
        assertEquals(emptyList<Int>(), late.questionsNeverAsked(zone, defaultTime))
    }

    @Test
    fun `P50 a legacy monthly weekday comes back on its date unanswered`() {
        val r = row(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 9, 7, 20, 0))), recurrence = Recurrence.MonthlyWeekday(1, DayOfWeek.MONDAY), createdAt = local(2026, 9, 1, 12, 0))
            .copy(lastFiredAt = local(2026, 9, 7, 20, 0), lastFiredRule = 0)
        assertNotNull(nextWake(r, local(2026, 9, 8, 10, 0), zone, defaultTime))
    }

    @Test
    fun `Q14 a state place answered after midnight under misma hora still rests`() {
        val r = row(TriggerRule(home), recurrence = Recurrence.After(1, RecurrenceUnit.DAYS, hour = RecurrenceHour.Same), createdAt = local(2026, 8, 20, 12, 0))
            .copy(lastFiredAt = local(2026, 8, 27, 23, 0), lastFiredRule = 0, lastDealtAt = local(2026, 8, 28, 0, 15))
        val rest = r.restUntil(zone, DEFAULT_DAY_START)
        assertTrue(rest != null && rest > local(2026, 8, 28, 12, 0), "rest $rest")
    }

    @Test
    fun `Q6 los sabados written on friday night opens with saturday's waking hours`() {
        val written = local(2026, 8, 29, 0, 30)
        val r = row(TriggerRule(Trigger.Weekday(setOf(DayOfWeek.SATURDAY))), createdAt = written)
        assertEquals(Wake(local(2026, 8, 29, 10, 0), 0), nextWake(r, written, zone, defaultTime))
    }

    @Test
    fun `R1 a calendar with a state place asks again on its next date unanswered`() {
        val weekly = Recurrence.Calendar(Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 31), unit = RepeatUnit.WEEK, time = t(9), days = setOf(DayOfWeek.MONDAY)))
        val r = row(TriggerRule(home), recurrence = weekly, createdAt = local(2026, 8, 30, 12, 0))
            .copy(lastFiredAt = local(2026, 8, 31, 19, 0), lastFiredRule = 0)
        val nextMonday = local(2026, 9, 7, 10, 0)
        val wake = nextWake(r, nextMonday, zone, defaultTime)
        assertTrue(wake != null || !r.presenceAlreadyRang(home, 0, nextMonday, zone, DEFAULT_DAY_START), "silent: wake=$wake")
    }

    @Test
    fun `R3 a custom tomorrow at the day start hour is refused as already offered, but lands elsewhere at the weekend`() {
        val settings = AppSettings()
        val spec = SnoozeSpec.On(SnoozeDay.Tomorrow, SnoozeHour.At(settings.dayStart))
        val friday = local(2026, 8, 28, 21, 0)
        val terms = settings.snoozeTerms
        val refused = settings.customSnoozeRefusal(spec) == CustomSnoozeRefusal.ALREADY_OFFERED
        assertTrue(!refused || spec.until(friday, zone, terms) == Snooze.TOMORROW_MORNING.until(friday, zone, terms), "refused, yet ${spec.until(friday, zone, terms)} vs ${Snooze.TOMORROW_MORNING.until(friday, zone, terms)}")
    }

    @Test
    fun `P82 desde que suena 24 h on the spring-forward day`() {
        val created = local(2027, 3, 26, 12, 0)
        val r = row(TriggerRule(Trigger.TimeOfDay(t(9))), recurrence = Recurrence.After(24, RecurrenceUnit.HOURS, RecurrenceFrom.RANG), createdAt = created)
        val phone = Simulation(r, created)
        val rings = phone.run(local(2027, 3, 30, 12, 0))
        assertEquals(listOf(27, 28, 29, 30), rings.map { it.local(zone).dayOfMonth }, "${rings.map { it.local(zone) }}")
    }
}
