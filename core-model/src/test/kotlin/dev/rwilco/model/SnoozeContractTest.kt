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
import java.time.LocalDate
import java.time.LocalTime

/**
 * What a snooze promises, held to it (the trigger-contract audit, 2026-11-04): a snooze answers
 * the ring it was given to, and nothing about it may quietly cost a ring somebody was counting on
 * — tomorrow's moment, the next day of a rhythm, a sibling place, the arrival a button named.
 *
 * Calendar: 2026-08-28 is a Friday, 08-31 a Monday, 09-01 a Tuesday.
 */
class SnoozeContractTest {

    private val home = Trigger.Location(40.4169, -3.7035, radiusM = 200, presence = Presence.INSIDE, label = "Casa")

    private fun t(hour: Int, minute: Int = 0): LocalTime = LocalTime.of(hour, minute)

    private fun row(vararg rules: TriggerRule, recurrence: Recurrence = Recurrence.None, createdAt: java.time.Instant = now) =
        Reminder(id = "r1", text = "x", rules = rules.toList(), recurrence = recurrence, createdAt = createdAt, updatedAt = createdAt)

    private val terms = SnoozeTerms(DayShape.DEFAULT, DayParts(), DEFAULT_SNOOZE_MINUTES)

    @Test
    fun `a ten minute snooze under desde que suena does not move the rhythm off the rule's hour`() {
        // "A las 9, y vuelve cada 24 h desde que suena", put off ten minutes and done then: it used
        // to count the 24 h from 9:10 and skip the next day's nine o'clock.
        val pills = row(TriggerRule(Trigger.TimeOfDay(t(9))), recurrence = Recurrence.After(24, RecurrenceUnit.HOURS, RecurrenceFrom.RANG))
        val phone = Simulation(pills, now)
        assertEquals(local(2026, 8, 28, 9, 0), phone.step { Simulation.Deal.Later(Snooze.TEN_MINUTES) }!!.at)
        val again = phone.step { Simulation.Deal.Done }!!
        assertEquals(local(2026, 8, 28, 9, 10), again.at)
        assertEquals(Wake(local(2026, 8, 29, 9, 0), 0), phone.arm(), "Saturday's nine still rings")
    }

    @Test
    fun `a hecho on a ring put off to tomorrow answers that ring and spends nothing ahead`() {
        val daily = Recurrence.Calendar(Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 31), unit = RepeatUnit.DAY, time = t(9)))
        val created = local(2026, 8, 30, 12, 0)
        for (snooze in listOf(Snooze.TOMORROW, Snooze.TOMORROW_MORNING, Snooze.TWO_HOURS, Snooze.NEXT_WEEK)) {
            val phone = Simulation(row(recurrence = daily, createdAt = created), created)
            assertEquals(local(2026, 8, 31, 9, 0), phone.step()!!.at)
            phone.now = local(2026, 8, 31, 9, 2)
            phone.deal(Simulation.Deal.Later(snooze))
            phone.now = local(2026, 8, 31, 20, 0)
            assertNull(phone.reminder.momentDealtWith(phone.now, zone, defaultTime), "$snooze: the hecho is about Monday's ring")
            phone.deal(Simulation.Deal.Done)
            assertEquals(Wake(local(2026, 9, 1, 9, 0), null), phone.arm(), "$snooze: Tuesday's nine still rings")
        }
    }

    @Test
    fun `a snooze given from Home before anything rang is still a moment done ahead`() {
        // Nothing rang: putting off this afternoon's two o'clock and then ticking it off is doing
        // that one ahead, as it always was.
        val daily = Recurrence.Calendar(Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 27), unit = RepeatUnit.DAY, time = t(17)))
        val r = row(recurrence = daily, createdAt = local(2026, 8, 27, 12, 0)).copy(snoozedUntil = local(2026, 8, 27, 18, 0))
        assertEquals(local(2026, 8, 27, 18, 0), r.momentDealtWith(now, zone, defaultTime))
    }

    @Test
    fun `the ring of a snoozed clock rule keeps that rule, so the place beside it is free`() {
        // "Mientras esté en casa, o a las 9": the nine o'clock is put off and rings again. That
        // ring was written with no rule, which reads as the place having had its say, and arriving
        // home rang nothing for the rest of the round.
        val either = row(TriggerRule(home), TriggerRule(Trigger.TimeOfDay(t(9))))
        val phone = Simulation(either, now)
        assertEquals(1, phone.step { Simulation.Deal.Later(Snooze.TEN_MINUTES) }!!.ruleIndex)
        val snoozed = phone.step()!!
        assertNull(snoozed.ruleIndex, "still the snooze's own ring")
        assertEquals(1, phone.reminder.lastFiredRule, "written against the nine o'clock it put off")
        assertFalse(phone.reminder.presenceAlreadyRang(home, 0))
        assertFalse(isPlaceEcho(phone.reminder.lastFiredAt, phone.reminder.lastFiredRule, 0, phone.now.plusSeconds(120)))
    }

    @Test
    fun `the snoozed ring of the place itself keeps the place quiet, as its own ring did`() {
        val alone = row(TriggerRule(home)).copy(lastFiredAt = now, lastFiredRule = 0, snoozedUntil = now.plusSeconds(600))
        assertEquals(0, alone.ruleRungBy(null, viaSnoozePlace = false))
        // A place snooze's crossing is the reminder itself, and echoes every rule's.
        assertNull(alone.copy(snoozedUntil = null, snoozedToPlace = home).ruleRungBy(null, viaSnoozePlace = true))
        assertEquals(1, alone.ruleRungBy(1, viaSnoozePlace = false))
    }

    @Test
    fun `tomorrow morning from a friday night is saturday at the weekend's waking hour`() {
        val back = Snooze.TOMORROW_MORNING.until(local(2026, 8, 28, 21, 0), zone, terms)
        assertEquals(local(2026, 8, 29, 10, 0), back)
        assertFalse(hushedByTheHour(back, back, zone, DayShape.DEFAULT))
        // A weekday's morning is still the morning's own hour.
        assertEquals(local(2026, 8, 31, 9, 0), Snooze.TOMORROW_MORNING.until(local(2026, 8, 30, 21, 0), zone, terms))
    }

    @Test
    fun `tomorrow, asked a quarter past midnight, is the morning about to come`() {
        val smallHours = local(2026, 9, 1, 0, 15)
        assertEquals(local(2026, 9, 1, 9, 0), Snooze.TOMORROW_MORNING.until(smallHours, zone, terms))
        assertEquals(local(2026, 9, 1, 9, 0), SnoozeSpec.On(SnoozeDay.Tomorrow, SnoozeHour.Part(SnoozePart.MORNING)).until(smallHours, zone, terms))
        assertEquals(local(2026, 9, 1, 17, 0), SnoozeSpec.On(SnoozeDay.Tomorrow, SnoozeHour.Part(SnoozePart.AFTERNOON)).until(smallHours, zone, terms))
        // "Esta noche" was last night's, and has gone.
        assertNull(SnoozeSpec.On(SnoozeDay.Today, SnoozeHour.Part(SnoozePart.EVENING)).until(smallHours, zone, terms))
        // "Mañana a la misma hora" was already right: it is tomorrow night, twenty-four hours on.
        assertEquals(local(2026, 9, 2, 0, 15), Snooze.TOMORROW.until(smallHours, zone, terms))
        // An early morning is today's: up at seven, "mañana" is Wednesday.
        assertEquals(local(2026, 9, 2, 9, 0), Snooze.TOMORROW_MORNING.until(local(2026, 9, 1, 7, 0), zone, terms))
    }

    @Test
    fun `an hour somebody chose themselves is never moved, even asleep`() {
        val sevenOnSaturday = SnoozeSpec.On(SnoozeDay.Weekday(DayOfWeek.SATURDAY), SnoozeHour.At(t(7)))
        assertEquals(local(2026, 8, 29, 7, 0), sevenOnSaturday.until(now, zone, terms))
    }

    @Test
    fun `la proxima vez que llegue is not held back by the rest counted from the ring it answers`() {
        // "Al llegar a casa, y vuelve cada día desde que suena": rung on arrival, answered "la
        // próxima vez que llegue". The rest from that ring held the doorway quiet until the next
        // morning, and the next arrival — the one the button named — rang nothing.
        val door = home.copy(onCrossing = true)
        val rang = local(2026, 8, 27, 19, 0)
        val r = row(TriggerRule(door), recurrence = Recurrence.After(1, RecurrenceUnit.DAYS, RecurrenceFrom.RANG))
            .copy(lastFiredAt = rang, lastFiredRule = 0)
        assertEquals(local(2026, 8, 28, 9, 0), r.restUntil(zone, DEFAULT_DAY_START), "unanswered, it rests from the ring")
        val promised = r.copy(snoozedToRules = true)
        assertNull(promised.restUntil(zone, DEFAULT_DAY_START))
        val later = local(2026, 8, 27, 21, 0)
        assertNull(promised.watchedCircles(later, zone, defaultTime).single().opensAt, "the doorway is watched tonight")
    }

    @Test
    fun `desde el hecho, a ring nobody answers comes back a span after it`() {
        // "El día 26 a las 20:00, y vuelve cada mes desde el hecho": rung and swiped away. It used
        // to wait for a "hecho" for ever; the alarm is now a month after the ring.
        val r = row(TriggerRule(Trigger.AtDateTime(java.time.LocalDateTime.of(2026, 11, 26, 20, 0))), recurrence = Recurrence.After(1, RecurrenceUnit.MONTHS), createdAt = local(2026, 11, 20, 12, 0))
            .copy(lastFiredAt = local(2026, 11, 26, 20, 0), lastFiredRule = 0)
        val after = local(2026, 11, 27, 10, 0)
        assertEquals(Wake(local(2026, 12, 26, 9, 0), null), nextWake(r, after, zone, defaultTime))
        assertNull(nextFire(r, after, zone, defaultTime), "Home keeps it where an unanswered ring belongs")
        // And once it is dealt with, it counts from the hecho — from the day of the ring the
        // hecho answers, which is the 26th (2026-11-04: a late "hecho" no longer skips a round).
        val dealt = r.copy(lastDealtAt = local(2026, 11, 28, 10, 0))
        assertEquals(Wake(local(2026, 12, 26, 9, 0), null), nextWake(dealt, local(2026, 11, 28, 10, 1), zone, defaultTime))
        // A hecho a whole span after the ring is about something else, and counts from itself.
        val late = r.copy(lastDealtAt = local(2026, 12, 27, 10, 0))
        assertEquals(Wake(local(2027, 1, 27, 9, 0), null), nextWake(late, local(2026, 12, 27, 10, 1), zone, defaultTime))
    }

    @Test
    fun `desde el hecho with rules still to say rings on them, not on the span`() {
        // A daily nine o'clock answered by nobody rings tomorrow at nine, as it always did: the
        // come-back is only for a reminder with nothing else left.
        val daily = row(TriggerRule(Trigger.TimeOfDay(t(9))), recurrence = Recurrence.After(2, RecurrenceUnit.DAYS))
            .copy(lastFiredAt = local(2026, 8, 28, 9, 0), lastFiredRule = 0)
        assertEquals(Wake(local(2026, 8, 29, 9, 0), 0), nextWake(daily, local(2026, 8, 28, 9, 5), zone, defaultTime))
        assertTrue(daily.unansweredComesBack)
    }

    @Test
    fun `a long snooze says how many of the reminder's own rings it swallows`() {
        // A daily nine o'clock, rung on Friday and put off to next week: six mornings unheard.
        val daily = row(TriggerRule(Trigger.TimeOfDay(t(9)))).copy(lastFiredAt = local(2026, 8, 28, 9, 0), lastFiredRule = 0)
        val friday = local(2026, 8, 28, 9, 1)
        val week = Snooze.NEXT_WEEK.until(friday, zone, terms)
        assertEquals(7, daily.ringsSkippedUntil(week, friday, zone, defaultTime))
        assertTrue(skipsWorthSaying(7))
        // "Mañana": the snooze's own ring stands in for tomorrow's nine, and one is not worth saying.
        val tomorrow = Snooze.TOMORROW.until(friday, zone, terms)
        assertEquals(1, daily.ringsSkippedUntil(tomorrow, friday, zone, defaultTime))
        assertFalse(skipsWorthSaying(1))
        // Ten minutes swallows nothing.
        assertEquals(0, daily.ringsSkippedUntil(friday.plusSeconds(600), friday, zone, defaultTime))
        // Under "todos" a moment is a note, not a ring: nothing is claimed.
        assertEquals(0, daily.copy(rules = daily.rules + TriggerRule(home), ruleMatch = RuleMatch.ALL).ringsSkippedUntil(week, friday, zone, defaultTime))
    }

    @Test
    fun `taking a snooze off a reminder whose moment has gone leaves it with nothing to ring`() {
        val once = row(TriggerRule(Trigger.AtDateTime(java.time.LocalDateTime.of(2026, 8, 27, 10, 0))), createdAt = local(2026, 8, 27, 9, 0))
            .copy(lastFiredAt = local(2026, 8, 27, 10, 0), lastFiredRule = 0, snoozedUntil = local(2026, 8, 27, 18, 0))
        assertTrue(once.unsnoozeLeavesNothing(now, zone, defaultTime))
        val daily = row(TriggerRule(Trigger.TimeOfDay(t(9)))).copy(snoozedUntil = local(2026, 8, 27, 18, 0))
        assertFalse(daily.unsnoozeLeavesNothing(now, zone, defaultTime), "a daily goes back to its own nine o'clock")
        assertFalse(daily.copy(snoozedUntil = null).unsnoozeLeavesNothing(now, zone, defaultTime), "nothing to take off")
    }
}
