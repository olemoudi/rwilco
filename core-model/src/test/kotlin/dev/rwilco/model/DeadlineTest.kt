package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The deadline on a set: which day a window means, what it does to the rules, what starts a
 * timer, and what a round that ran out becomes. The clock is the fixtures' Thursday afternoon.
 */
class DeadlineTest {

    private val home = Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa")
    private val office = Trigger.Location(40.4369, -3.7035, 150, Presence.INSIDE, "Oficina")
    private val homeDoor = home.copy(onCrossing = true)
    private val atEight = Trigger.TimeOfDay(LocalTime.of(20, 0))
    private val evening = Deadline.Window(LocalTime.of(18, 0), LocalTime.of(22, 0))

    private fun set(
        match: RuleMatch,
        vararg triggers: Trigger,
        deadline: Deadline? = evening,
        expiresAt: Instant? = null,
        recurrence: Recurrence = Recurrence.None,
        firedRules: Set<Int> = emptySet(),
    ) = Reminder(
        id = "r1",
        text = "Llamar a Marta",
        rules = triggers.map { TriggerRule(it) },
        ruleMatch = match,
        deadline = deadline,
        expiresAt = expiresAt,
        recurrence = recurrence,
        firedRules = firedRules,
        createdAt = now,
        updatedAt = now,
    )

    private fun Reminder.closeFrom(from: Instant) = windowExpiry(from, zone, defaultTime)

    @Test
    fun `a weekly rule written on a Monday gives Friday's close, not Monday's`() {
        val fridays = set(RuleMatch.ALL, Trigger.Weekday(setOf(DayOfWeek.FRIDAY)), home)
        val monday = local(2026, 8, 24, 10, 0)
        assertEquals(local(2026, 8, 28, 22, 0), fridays.closeFrom(monday), "the round is Friday's, whatever day it was written")
    }

    @Test
    fun `an hour already gone today gives tomorrow's close`() {
        val eight = set(RuleMatch.ALL, atEight, home)
        assertEquals(local(2026, 8, 27, 22, 0), eight.closeFrom(local(2026, 8, 27, 15, 0)), "written in the afternoon: tonight")
        assertEquals(local(2026, 8, 28, 22, 0), eight.closeFrom(local(2026, 8, 27, 21, 0)), "written at nine: the 20:00 it can still make is tomorrow's")
    }

    @Test
    fun `a dated rule names the day, and the fence keeps every other day out`() {
        val twelfth = set(RuleMatch.ALL, Trigger.AtDateTime(LocalDateTime.of(2026, 9, 12, 19, 0)), home)
        val close = twelfth.closeFrom(now)
        assertEquals(local(2026, 9, 12, 22, 0), close)
        val armed = twelfth.copy(expiresAt = close)
        val fence = armed.deadlineFence(zone)!!
        assertEquals(Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0), date = LocalDate.of(2026, 9, 12)), fence)
        assertFalse(fence.holdsAt(local(2026, 9, 10, 19, 0), zone), "being at home on the 10th is not the 12th")
        assertTrue(fence.holdsAt(local(2026, 9, 12, 19, 0), zone))
        assertEquals(listOf(fence), armed.ruleInSet(1, DayShape.DEFAULT, zone)!!.conditions, "the place carries the fence like every rule of the round")
    }

    @Test
    fun `two places give today's close, or tomorrow's once today's has gone`() {
        val places = set(RuleMatch.ALL, home, office)
        assertEquals(local(2026, 8, 27, 22, 0), places.closeFrom(local(2026, 8, 27, 15, 0)))
        assertEquals(local(2026, 8, 28, 22, 0), places.closeFrom(local(2026, 8, 27, 23, 0)))
    }

    @Test
    fun `a window past midnight closes the next day and belongs to the day it opened`() {
        val night = Deadline.Window(LocalTime.of(22, 0), LocalTime.of(2, 0))
        val late = set(RuleMatch.ALL, Trigger.TimeOfDay(LocalTime.of(23, 0)), home, deadline = night)
        val close = late.closeFrom(now)
        assertEquals(local(2026, 8, 28, 2, 0), close)
        assertEquals(LocalDate.of(2026, 8, 27), night.openDayOf(close!!, zone), "the small hours are Thursday's")
        assertEquals(LocalDate.of(2026, 8, 27), late.copy(expiresAt = close).deadlineFence(zone)!!.date)
    }

    @Test
    fun `a day with no hour opens where the window does`() {
        val short = Deadline.Window(LocalTime.of(16, 0), LocalTime.of(17, 0))
        val thursday = set(RuleMatch.ALL, Trigger.DayRandom(LocalDate.of(2026, 9, 3)), home, deadline = short)
        assertEquals(local(2026, 9, 3, 17, 0), thursday.closeFrom(now), "the day opens at 16:00 inside its fence, not at breakfast outside it")
    }

    @Test
    fun `the fence reaches every rule under todos and comes after the siblings under a la vez`() {
        val close = local(2026, 8, 27, 22, 0)
        val fence = evening.asCondition(date = LocalDate.of(2026, 8, 27))
        val all = set(RuleMatch.ALL, atEight, home, expiresAt = close)
        assertEquals(listOf(fence), all.ruleInSet(0, DayShape.DEFAULT, zone)!!.conditions)
        assertEquals(listOf(fence), all.ruleInSet(1, DayShape.DEFAULT, zone)!!.conditions)
        val together = set(RuleMatch.TOGETHER, atEight, home, expiresAt = close)
        assertEquals(listOf(home.asState(), fence), together.ruleInSet(0, DayShape.DEFAULT, zone)!!.conditions)
        assertEquals(emptyList<Condition>(), set(RuleMatch.ANY, atEight, home, expiresAt = close).ruleInSet(0, DayShape.DEFAULT, zone)!!.conditions, "cualquiera has nothing to give up on")
        assertEquals(emptyList<Condition>(), all.copy(expiresAt = null).ruleInSet(0, DayShape.DEFAULT, zone)!!.conditions, "no round under way, no day to fence")
    }

    @Test
    fun `a deadline means nothing with one rule, under cualquiera, or as a timer under a la vez`() {
        assertFalse(set(RuleMatch.ALL, atEight).hasDeadline)
        assertFalse(set(RuleMatch.ANY, atEight, home).hasDeadline)
        assertTrue(set(RuleMatch.ALL, atEight, home).hasDeadline)
        assertTrue(set(RuleMatch.TOGETHER, atEight, home).hasDeadline)
        assertFalse(set(RuleMatch.TOGETHER, atEight, home, deadline = Deadline.Timer(30)).hasDeadline)
        assertTrue(set(RuleMatch.ALL, atEight, home, deadline = Deadline.Timer(30)).hasDeadline)
        // "Justo el plazo" takes the rules out of the loop, and the set with them.
        val exact = Recurrence.After(1, RecurrenceUnit.DAYS, landing = SpanLanding.EXACT)
        val fresh = set(RuleMatch.ALL, atEight, home, recurrence = exact)
        assertTrue(fresh.hasDeadline, "the first round is the rules'")
        assertFalse(fresh.copy(lastDealtAt = now).hasDeadline, "and every round after is the span's")
    }

    @Test
    fun `a timer starts with a moment and not with a state`() {
        val hour = Deadline.Timer(60)
        val at = local(2026, 8, 27, 20, 0)
        val clockAndHome = set(RuleMatch.ALL, atEight, home, deadline = hour)
        assertEquals(at.plusSeconds(3600), clockAndHome.timerExpiry(0, at), "the hour starts it")
        assertNull(clockAndHome.timerExpiry(1, at), "being at home does not: it was true before anything happened")
        assertEquals(at.plusSeconds(3600), set(RuleMatch.ALL, homeDoor, atEight, deadline = hour).timerExpiry(0, at), "a doorway is a moment")
        assertNull(set(RuleMatch.ALL, Trigger.Weekday(setOf(DayOfWeek.THURSDAY)), home, deadline = hour).timerExpiry(0, at))
        assertNull(clockAndHome.copy(expiresAt = at).timerExpiry(0, at), "one already running is not restarted")
        assertNull(set(RuleMatch.TOGETHER, atEight, home, deadline = hour).timerExpiry(0, at), "no first trigger under a la vez")
        assertNull(set(RuleMatch.ALL, atEight, home).timerExpiry(0, at), "a window is not a timer")
    }

    @Test
    fun `lapsing finishes a reminder that does not come back, and starts the next round of one that does`() {
        val close = local(2026, 8, 27, 22, 0)
        val halfDone = set(RuleMatch.ALL, atEight, home, expiresAt = close, firedRules = setOf(0)).copy(armedFor = local(2026, 8, 28, 20, 0), armedRule = 0)
        assertTrue(halfDone.expiryDue(close))
        assertFalse(halfDone.expiryDue(close.minusSeconds(1)))
        val gone = halfDone.lapsed(close, zone, defaultTime)
        assertEquals(Status.DONE, gone.status)
        assertEquals(close, gone.doneAt)
        assertEquals(close, gone.lastDealtAt)
        assertEquals(close, gone.dealtThrough)
        assertEquals(emptySet<Int>(), gone.firedRules)
        assertNull(gone.expiresAt)
        assertNull(gone.armedFor, "a moment armed past the deadline is not a firing owed")
        val daily = halfDone.copy(recurrence = Recurrence.After(1, RecurrenceUnit.DAYS)).lapsed(close, zone, defaultTime)
        assertEquals(Status.ACTIVE, daily.status)
        assertEquals(close, daily.lastDealtAt, "the span counts from the deadline")
        assertEquals(local(2026, 8, 28, 22, 0), daily.expiresAt, "and tomorrow's round has tomorrow's close")
        assertEquals(emptySet<Int>(), daily.firedRules)
    }

    @Test
    fun `the editor's warning sees a rule that cannot land inside the window`() {
        val nineAm = TriggerRule(Trigger.TimeOfDay(LocalTime.of(9, 0)))
        val found = warnings(listOf(nineAm, TriggerRule(home)), now, zone, defaultTime, RuleMatch.ALL, deadline = evening)
        assertTrue(ValidationWarning.NeverFires(0) in found, "nine in the morning is never between six and ten at night: $found")
        assertTrue(ValidationWarning.NeverCompletes(0) in found)
        val fine = warnings(listOf(TriggerRule(atEight), TriggerRule(home)), now, zone, defaultTime, RuleMatch.ALL, deadline = evening)
        assertTrue(fine.none { it is ValidationWarning.NeverFires || it is ValidationWarning.NeverCompletes }, "$fine")
        val ignored = warnings(listOf(nineAm, TriggerRule(home)), now, zone, defaultTime, RuleMatch.ANY, deadline = evening)
        assertTrue(ignored.none { it is ValidationWarning.NeverFires }, "under cualquiera the deadline is not applied: $ignored")
    }

    @Test
    fun `the on-disk shape of a deadline is frozen, and one this build cannot read is none`() {
        assertEquals("""{"type":"window","from":"18:00","to":"22:00"}""", ReminderCodec.encodeDeadline(evening))
        assertEquals("""{"type":"timer","minutes":90}""", ReminderCodec.encodeDeadline(Deadline.Timer(90)))
        assertEquals(evening, ReminderCodec.decodeDeadline(ReminderCodec.encodeDeadline(evening)))
        assertEquals(Deadline.Timer(90), ReminderCodec.decodeDeadline("""{"type":"timer","minutes":90}"""))
        assertNull(ReminderCodec.decodeDeadline("""{"type":"fortnight","weeks":2}"""))
        assertNull(ReminderCodec.decodeDeadline("not json"))
        assertNull(ReminderCodec.decodeDeadline(null))
    }

    @Test
    fun `a preset carries the deadline, and one it cannot read costs the preset nothing else`() {
        val preset = Preset(id = "p1", name = "Marta", rules = listOf(TriggerRule(atEight), TriggerRule(home)), ruleMatch = RuleMatch.ALL, deadline = Deadline.Timer(30), createdAt = now)
        assertEquals(Deadline.Timer(30), preset.toReminder(id = "r1", now = now, zone = zone).deadline)
        val json = ReminderCodec.json.encodeToString(Preset.serializer(), preset)
        assertTrue(json.contains(""""deadline":{"type":"timer","minutes":30}"""), json)
        val unread = ReminderCodec.json.decodeFromString(Preset.serializer(), json.replace(""""type":"timer"""", """"type":"fortnight""""))
        assertNull(unread.deadline)
        assertEquals(preset.rules, unread.rules, "the rest of the preset survives")
        assertEquals(preset, ReminderCodec.json.decodeFromString(Preset.serializer(), json))
    }
}
