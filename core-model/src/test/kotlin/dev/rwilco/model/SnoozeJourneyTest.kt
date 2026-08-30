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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * "Más tarde", followed through: the reminder rings again at the moment the button names, as
 * the ring it was — no rule behind it, nothing re-judged — and what it was going to do next is
 * still what it does next.
 */
class SnoozeJourneyTest {

    private fun reminder(vararg rules: TriggerRule, recurrence: Recurrence = Recurrence.None, match: RuleMatch = RuleMatch.ANY, createdAt: Instant = now) =
        Reminder(id = "r1", text = "x", rules = rules.toList(), recurrence = recurrence, ruleMatch = match, createdAt = createdAt, updatedAt = createdAt)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) = TriggerRule(Trigger.AtDateTime(LocalDate.of(year, month, day).atTime(hour, minute)))

    @Test
    fun `ten minutes later a one-shot rings again as itself, and is then owed an answer`() {
        val phone = Simulation(reminder(at(2026, 8, 27, 21, 30)), now)
        val first = phone.step { Simulation.Deal.Later(Snooze.TEN_MINUTES) }!!
        assertEquals(local(2026, 8, 27, 21, 30), first.at)
        assertEquals(0, first.ruleIndex)
        assertEquals(local(2026, 8, 27, 21, 40), phone.reminder.armedFor, "the snooze is what is armed")
        val again = phone.step()!!
        assertEquals(local(2026, 8, 27, 21, 40), again.at)
        assertNull(again.ruleIndex, "a snooze's moment is the ring itself, with no rule behind it")
        assertEquals(local(2026, 8, 27, 21, 40), again.rangFor)
        assertNull(phone.reminder.snoozedUntil, "the snooze is spent")
        assertNull(phone.arm(), "a moment that has been has nothing left")
        assertNull(nextFire(phone.reminder, phone.now, zone, defaultTime), "overdue, on Home")
        assertTrue(phone.reminder.awaitingAnswer(phone.now))
    }

    @Test
    fun `two hours later on a daily calendar rings at eleven, and the next one is tomorrow's nine`() {
        val daily = Recurrence.Calendar(Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 28), unit = RepeatUnit.DAY, time = LocalTime.of(9, 0)))
        val phone = Simulation(reminder(recurrence = daily), now)
        phone.step { Simulation.Deal.Later(Snooze.TWO_HOURS) }
        val again = phone.step()!!
        assertEquals(local(2026, 8, 28, 11, 0), again.at)
        assertNull(again.ruleIndex)
        assertEquals(Wake(local(2026, 8, 29, 9, 0), null), phone.arm(), "not today's nine again, and not a day skipped")
        assertEquals(listOf(local(2026, 8, 28, 9, 0), local(2026, 8, 28, 11, 0), local(2026, 8, 29, 9, 0)), phone.run(local(2026, 8, 29, 12, 0)).let { phone.rings.map { it.at } })
    }

    @Test
    fun `counted from dealing with it, the six hours start at the hecho and not at the snooze`() {
        val phone = Simulation(reminder(at(2026, 8, 28, 8, 0), recurrence = Recurrence.After(6, RecurrenceUnit.HOURS)), now)
        phone.step { Simulation.Deal.Later(Snooze.TWO_HOURS) }
        val again = phone.step { Simulation.Deal.Done }!!
        assertEquals(local(2026, 8, 28, 10, 0), again.at)
        assertEquals(Status.ACTIVE, phone.reminder.status, "asked to come back, so it stays")
        assertEquals(Wake(local(2026, 8, 28, 16, 0), null), phone.arm(), "six hours after the hecho at ten")
    }

    @Test
    fun `counted from the ringing, the six hours start at the last ring, which a snooze moves`() {
        // Pinned rather than chosen: the snoozed ring re-stamps lastFiredAt, and that is the
        // anchor "desde que suena" counts from — so rung at eight, put off to ten and dealt with
        // then, it comes back at four, not at two. Fourteen would need a snooze that does not
        // count as a ring, which nobody has asked for; what RANG promises is the case below.
        val rang = Recurrence.After(6, RecurrenceUnit.HOURS, RecurrenceFrom.RANG)
        val snoozed = Simulation(reminder(at(2026, 8, 28, 8, 0), recurrence = rang), now)
        snoozed.step { Simulation.Deal.Later(Snooze.TWO_HOURS) }
        snoozed.step { Simulation.Deal.Done }
        assertEquals(Wake(local(2026, 8, 28, 16, 0), null), snoozed.arm())
        // Answered late without a snooze, the rhythm holds: rung at eight, dealt with at half
        // past nine, back at two.
        val late = Simulation(reminder(at(2026, 8, 28, 8, 0), recurrence = rang), now)
        late.step()
        late.now = local(2026, 8, 28, 9, 30)
        late.deal(Simulation.Deal.Done)
        assertEquals(Wake(local(2026, 8, 28, 14, 0), null), late.arm())
    }

    @Test
    fun `tomorrow across the clock change is the same hour tomorrow, twenty-five hours on`() {
        val phone = Simulation(reminder(at(2026, 10, 24, 21, 0), createdAt = local(2026, 10, 24, 12, 0)), local(2026, 10, 24, 12, 0))
        val first = phone.step { Simulation.Deal.Later(Snooze.TOMORROW) }!!
        val again = phone.step()!!
        assertEquals(LocalTime.of(21, 0), again.local(zone).toLocalTime())
        assertEquals(LocalDate.of(2026, 10, 25), again.local(zone).toLocalDate())
        assertEquals(Duration.ofHours(25), Duration.between(first.at, again.at))
    }

    @Test
    fun `the weekend, asked for on a friday night, is the next friday evening`() {
        val phone = Simulation(reminder(at(2026, 8, 28, 21, 0)), now)
        phone.step { Simulation.Deal.Later(Snooze.WEEKEND) }
        assertEquals(local(2026, 9, 4, 20, 30), phone.step()!!.at)
        // And next week is this day and hour, seven days on.
        val week = Simulation(reminder(at(2026, 8, 28, 21, 0)), now)
        week.step { Simulation.Deal.Later(Snooze.NEXT_WEEK) }
        assertEquals(local(2026, 9, 4, 21, 0), week.step()!!.at)
    }

    @Test
    fun `a set that rang and was put off keeps what it had ticked off until it is dealt with`() {
        val phone = Simulation(reminder(at(2026, 8, 28, 9, 0), at(2026, 8, 28, 12, 0), match = RuleMatch.ALL), now)
        assertNull(phone.step(), "the first is a note")
        assertEquals(setOf(0), phone.reminder.firedRules)
        val ring = phone.step { Simulation.Deal.Later(Snooze.TEN_MINUTES) }!!
        assertEquals(1, ring.ruleIndex)
        assertEquals(setOf(0, 1), phone.reminder.firedRules, "a snooze writes nothing but the snooze")
        val again = phone.step { Simulation.Deal.Done }!!
        assertEquals(local(2026, 8, 28, 12, 10), again.at)
        assertNull(again.ruleIndex)
        assertEquals(emptySet<Int>(), phone.reminder.firedRules, "dealt with, the round is over")
        assertEquals(Status.DONE, phone.reminder.status)
    }

    @Test
    fun `a snooze the phone slept through is caught up`() {
        val phone = Simulation(reminder(at(2026, 8, 28, 9, 0)), now)
        phone.step { Simulation.Deal.Later(Snooze.TWO_HOURS) }
        assertEquals(local(2026, 8, 28, 11, 0), phone.reminder.armedFor)
        val rings = phone.sleepUntil(local(2026, 8, 28, 12, 0))
        assertEquals(1, rings.size)
        assertEquals(local(2026, 8, 28, 11, 0), rings.single().late, "for the snooze's own moment")
        assertEquals(local(2026, 8, 28, 12, 0), rings.single().rangFor, "recorded as rung now")
        assertNull(rings.single().ruleIndex)
    }

    // --- put off until a place ---

    private val home = Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa", onCrossing = true)
    private val here = hereCircle(Fix(40.45, -3.69, 20.0, now), "aquí")

    @Test
    fun `put off until home, a one-shot arms nothing, rings on the doorway, and is then owed an answer`() {
        val phone = Simulation(reminder(at(2026, 8, 27, 21, 30)), now)
        val first = phone.step { Simulation.Deal.Elsewhere(home) }!!
        assertEquals(local(2026, 8, 27, 21, 30), first.at)
        assertEquals(home, phone.reminder.snoozedToPlace)
        assertNull(phone.reminder.snoozedUntil, "a place and a clock are never both set")
        assertNull(phone.arm(), "nothing on the clock: the circle is the alarm")
        assertNull(phone.reminder.armedFor)
        assertNull(phone.step(), "and so no alarm ever arrives")
        assertEquals(NextFire.WhenAt(home, snoozed = true), nextFire(phone.reminder, phone.now, zone, defaultTime))
        assertFalse(phone.reminder.awaitingAnswer(phone.now), "put off is an answer")

        phone.now = local(2026, 8, 27, 23, 10)
        assertNull(phone.cross(Transition.EXIT), "leaving is not the crossing it waits for")
        val again = phone.cross(Transition.ENTER)!!
        assertEquals(local(2026, 8, 27, 23, 10), again.at)
        assertEquals(local(2026, 8, 27, 23, 10), again.rangFor, "an arrival rings for the moment it happened")
        assertNull(again.ruleIndex, "no rule behind it")
        assertNull(phone.reminder.snoozedToPlace, "the place is spent")
        assertTrue(phone.reminder.awaitingAnswer(phone.now))
        assertNull(phone.cross(Transition.ENTER), "and a second arrival is nothing")
        phone.deal(Simulation.Deal.Done)
        assertEquals(Status.DONE, phone.reminder.status)
    }

    @Test
    fun `put off until leaving here, only the leaving rings it`() {
        val phone = Simulation(reminder(at(2026, 8, 27, 21, 30)), now)
        phone.step { Simulation.Deal.Elsewhere(here) }
        assertNull(phone.cross(Transition.ENTER), "still here")
        phone.now = local(2026, 8, 27, 22, 0)
        val rang = phone.cross(Transition.EXIT)!!
        assertEquals(local(2026, 8, 27, 22, 0), rang.at)
        assertNull(phone.reminder.snoozedToPlace)
    }

    @Test
    fun `on a daily calendar the arrival rings, and the hecho hands back tomorrow's nine`() {
        val daily = Recurrence.Calendar(Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 28), unit = RepeatUnit.DAY, time = LocalTime.of(9, 0)))
        val phone = Simulation(reminder(recurrence = daily), now)
        phone.step { Simulation.Deal.Elsewhere(home) }
        assertNull(phone.arm(), "the calendar's next moment is outranked while it waits at the door")
        phone.now = local(2026, 8, 28, 18, 30)
        val rang = phone.cross(Transition.ENTER) { Simulation.Deal.Done }!!
        assertEquals(local(2026, 8, 28, 18, 30), rang.at)
        assertEquals(Wake(local(2026, 8, 29, 9, 0), null), phone.arm(), "and the series goes on from its own dates")
        assertEquals(Status.ACTIVE, phone.reminder.status)
    }

    @Test
    fun `a phone off for two days owes nothing, and the arrival after it still rings`() {
        val phone = Simulation(reminder(at(2026, 8, 27, 21, 30)), now)
        phone.step { Simulation.Deal.Elsewhere(home) }
        val late = phone.sleepUntil(local(2026, 8, 29, 22, 0))
        assertEquals(emptyList<Simulation.Ring>(), late, "no moment was missed: there was none on the clock")
        assertNull(missedFire(phone.reminder, phone.now))
        assertEquals(home, phone.reminder.snoozedToPlace, "still waiting at the door")
        assertEquals(local(2026, 8, 29, 22, 0), phone.cross(Transition.ENTER)!!.at)
    }

    @Test
    fun `the missed-firing pass reads a place snooze as an answer, and the net gives it two days`() {
        val phone = Simulation(reminder(at(2026, 8, 27, 21, 30)), now)
        phone.step { Simulation.Deal.Elsewhere(home) }
        phone.now = local(2026, 8, 30, 12, 0)
        assertNull(missedFire(phone.reminder, phone.now), "put off is an answer: nothing was missed")
        // Two of the net's longest waits after the ring the snooze answered, one quiet word
        // that it is still waiting — the only backstop a wait with nothing on the clock has.
        val due = phone.reminder.netDue(phone.now, zone, defaultTime, SafetyNetSettings())
        assertEquals(NetWord.WAITING, due?.word)
        assertEquals(phone.rings.single().rangFor, due?.about)
        assertEquals(phone.rings.single().rangFor.plus(Duration.ofHours(48)), due?.at)
    }

    @Test
    fun `a clock snooze given after a place snooze replaces it, and the other way round`() {
        val phone = Simulation(reminder(at(2026, 8, 27, 21, 30)), now)
        phone.step { Simulation.Deal.Elsewhere(home) }
        phone.deal(Simulation.Deal.Later(Snooze.TEN_MINUTES))
        assertNull(phone.reminder.snoozedToPlace)
        assertEquals(local(2026, 8, 27, 21, 40), phone.reminder.snoozedUntil)
        phone.deal(Simulation.Deal.Elsewhere(here))
        assertNull(phone.reminder.snoozedUntil)
        assertEquals(here, phone.reminder.snoozedToPlace)
    }
}
