package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Journeys that mix what the trigger-contract round changed (2026-11-04) — snoozes, sets, places,
 * "Vuelve", the safety net, a pause — wound forward on the [Simulation] phone, one ring at a time.
 *
 * The harness knows nothing of where the phone is (a place condition holds), and it does not ask
 * `isPlaceEcho` or `presenceAlreadyRang` on a place's own ring; where a journey depends on either,
 * the test asks it in so many words, as `ReminderFiring.fire` does before ringing.
 *
 * Calendar: 2026-11-02 is a Monday, 11-06 a Friday, 11-05 a Thursday.
 */
class JourneyReliabilityTest {

    private val home = Trigger.Location(40.4169, -3.7035, radiusM = 200, presence = Presence.INSIDE, label = "Casa")
    private val atHome = Condition.AtPlace(home.lat, home.lng, home.radiusM, "Casa")

    private fun t(hour: Int, minute: Int = 0): LocalTime = LocalTime.of(hour, minute)

    /** Alarm after alarm until one rings: under "todos" most of them are notes. */
    private fun Simulation.nextRing(deal: (Simulation.Ring) -> Simulation.Deal = { Simulation.Deal.Ignore }): Simulation.Ring {
        repeat(50) { step(deal = deal)?.let { return it } }
        error("nothing rang in fifty alarms")
    }

    private fun row(
        vararg rules: TriggerRule,
        match: RuleMatch = RuleMatch.ANY,
        recurrence: Recurrence = Recurrence.None,
        createdAt: Instant,
        deadline: Deadline? = null,
    ) = Reminder(
        id = "journey",
        text = "x",
        rules = rules.toList(),
        ruleMatch = match,
        recurrence = recurrence,
        deadline = deadline,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    // A snooze on a «todos» set.

    @Test
    fun `under todos a snooze rings once, keeps the rule that completed the set, and the next Friday rings at its hour`() {
        val created = local(2026, 10, 31, 15, 0) // a Saturday afternoon
        val friday = row(
            TriggerRule(Trigger.Weekday(setOf(DayOfWeek.FRIDAY))),
            TriggerRule(Trigger.TimeOfDay(t(20))),
            match = RuleMatch.ALL,
            createdAt = created,
        )
        val phone = Simulation(friday, created)
        val first = phone.nextRing { Simulation.Deal.Later(Snooze.TEN_MINUTES) }
        assertEquals(local(2026, 11, 6, 20, 0), first.at, "not at Friday's opening, and not off Saturday's 20:00")
        assertEquals(1, first.ruleIndex)
        assertEquals(0, phone.reminder.ringsSkippedUntil(local(2026, 11, 13, 20, 0), phone.now, zone, defaultTime), "under todos a moment is a note, never a counted ring")
        val snoozed = phone.nextRing { Simulation.Deal.Done }
        assertEquals(local(2026, 11, 6, 20, 10), snoozed.at)
        assertNull(snoozed.ruleIndex, "the snooze's own ring")
        assertEquals(1, phone.reminder.lastFiredRule, "written against the rule it put off")
        // Nothing was asked to come back, so the hecho finishes it.
        assertEquals(Status.DONE, phone.reminder.status)
    }

    @Test
    fun `under todos desde que suena a snooze does not move the next round off Friday at 20`() {
        val created = local(2026, 10, 31, 15, 0)
        val friday = row(
            TriggerRule(Trigger.Weekday(setOf(DayOfWeek.FRIDAY))),
            TriggerRule(Trigger.TimeOfDay(t(20))),
            match = RuleMatch.ALL,
            recurrence = Recurrence.After(1, RecurrenceUnit.WEEKS, RecurrenceFrom.RANG),
            createdAt = created,
        )
        val phone = Simulation(friday, created)
        assertEquals(local(2026, 11, 6, 20, 0), phone.nextRing { Simulation.Deal.Later(Snooze.TEN_MINUTES) }.at)
        assertEquals(local(2026, 11, 6, 20, 10), phone.nextRing().at)
        assertEquals(emptySet<Int>(), phone.reminder.firedRules, "«desde que suena» ends the round at the ring")
        // Nobody answers anything, for three more weeks.
        val rest = phone.run(until = local(2026, 11, 28, 0, 0))
        assertEquals(
            listOf(local(2026, 11, 13, 20, 0), local(2026, 11, 20, 20, 0), local(2026, 11, 27, 20, 0)),
            rest.map { it.at },
            "every Friday at 20:00 and nowhere else",
        )
    }

    // A «hecho» after a snooze, on a calendar with a place.

    @Test
    fun `a hecho after a snooze on a calendar with a place answers the ring and keeps next month's date`() {
        val created = local(2026, 11, 1, 12, 0)
        val monthly = Recurrence.Calendar(
            Trigger.Repeat(startsOn = LocalDate.of(2026, 11, 5), unit = RepeatUnit.MONTH, time = t(20)),
            conditions = listOf(atHome),
        )
        val bins = row(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 5, 20, 0))), recurrence = monthly, createdAt = created)
        val phone = Simulation(bins, created)
        assertEquals(local(2026, 11, 5, 20, 0), phone.step { Simulation.Deal.Later(Snooze.TWO_HOURS) }!!.at)
        val again = phone.step()!!
        assertEquals(local(2026, 11, 5, 22, 0), again.at)
        assertEquals(0, phone.reminder.lastFiredRule, "the snooze's ring keeps the dated rule")
        phone.now = local(2026, 11, 5, 22, 5)
        assertNull(phone.reminder.momentDealtWith(phone.now, zone, defaultTime), "the hecho is about tonight's ring")
        phone.deal(Simulation.Deal.Done)
        assertEquals(Status.ACTIVE, phone.reminder.status)
        assertEquals(Wake(local(2026, 12, 5, 20, 0), null), phone.arm(), "the calendar's own date, at its own hour")
    }

    @Test
    fun `a calendar with a place left unanswered comes back on its next date rather than waiting for ever`() {
        val created = local(2026, 11, 1, 12, 0)
        val monthly = Recurrence.Calendar(
            Trigger.Repeat(startsOn = LocalDate.of(2026, 11, 5), unit = RepeatUnit.MONTH, time = t(20)),
            conditions = listOf(atHome),
        )
        val bins = row(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 5, 20, 0))), recurrence = monthly, createdAt = created)
        val phone = Simulation(bins, created)
        val rings = phone.run(until = local(2027, 1, 6, 0, 0))
        assertEquals(listOf(local(2026, 11, 5, 20, 0), local(2026, 12, 5, 20, 0), local(2027, 1, 5, 20, 0)), rings.map { it.at })
        // Home keeps it overdue meanwhile: the come-back is the alarm's answer only.
        assertNull(nextFire(phone.reminder, phone.now, zone, defaultTime))
    }

    // A stretch written while open, with a place condition.

    @Test
    fun `a stretch written while open rings the minute after, once, and its place ring is then dropped`() {
        val written = local(2026, 11, 4, 18, 30)
        val evening = row(TriggerRule(Trigger.Interval(t(18), t(20)), conditions = listOf(atHome)), createdAt = written)
        assertEquals(local(2026, 11, 4, 18, 0), evening.stretchOwed(0, written, zone, defaultTime), "open and owed when written")
        val phone = Simulation(evening, written)
        val first = phone.step()!!
        assertEquals(local(2026, 11, 4, 18, 31), first.at, "counted from the writing, not tomorrow's opening")
        assertEquals(0, phone.reminder.lastFiredRule)
        assertNull(phone.reminder.stretchOwed(0, phone.now, zone, defaultTime), "the round has had its say")
        phone.now = local(2026, 11, 4, 19, 0)
        assertNull(phone.arriveAtCondition(0), "getting home later in the same stretch rings nothing more")
        assertEquals(Wake(local(2026, 11, 5, 18, 0), 0), phone.arm(), "tomorrow's opening")
    }

    @Test
    fun `a stretch whose opening rang elsewhere is not owed again by its place, and the next day it is`() {
        val created = local(2026, 11, 3, 12, 0)
        val evening = row(TriggerRule(Trigger.Interval(t(18), t(20)), conditions = listOf(atHome)), createdAt = created)
        val phone = Simulation(evening, created)
        assertEquals(local(2026, 11, 3, 18, 0), phone.step { Simulation.Deal.Done }!!.at)
        phone.now = local(2026, 11, 3, 18, 40)
        assertNull(phone.reminder.stretchOwed(0, phone.now, zone, defaultTime), "dealt with since it opened")
        assertNull(phone.arriveAtCondition(0))
        phone.now = local(2026, 11, 4, 18, 45)
        assertEquals(local(2026, 11, 4, 18, 0), phone.reminder.stretchOwed(0, phone.now, zone, defaultTime), "a new stretch, owed its ring")
    }

    @Test
    fun `a stretch with a place under a snooze is not rung by its place until the snooze is over`() {
        val created = local(2026, 11, 3, 12, 0)
        val evening = row(TriggerRule(Trigger.Interval(t(18), t(20)), conditions = listOf(atHome)), createdAt = created)
        val phone = Simulation(evening, created)
        assertEquals(local(2026, 11, 3, 18, 0), phone.step { Simulation.Deal.Later(Snooze.TWO_HOURS) }!!.at)
        phone.now = local(2026, 11, 3, 18, 30)
        assertNull(phone.arriveAtCondition(0), "the snooze answered this stretch")
        assertEquals(local(2026, 11, 3, 20, 0), phone.step()!!.at, "the snooze rings it")
    }

    // An unanswered «desde el hecho» reminder over a week, with the safety net.

    @Test
    fun `an unanswered desde el hecho comes back every day of the week, the net speaks once per ring, and a late hecho spends nothing ahead`() {
        val created = local(2026, 11, 1, 20, 0)
        val pills = row(
            TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 2, 9, 0))),
            recurrence = Recurrence.After(1, RecurrenceUnit.DAYS, RecurrenceFrom.DEALT, hour = RecurrenceHour.At(t(9))),
            createdAt = created,
        )
        val phone = Simulation(pills, created)
        val net = SafetyNetSettings()
        val first = phone.step()!!
        assertEquals(local(2026, 11, 2, 9, 0), first.at)
        val word = phone.reminder.netDue(phone.now, zone, defaultTime, net)
        assertNotNull(word)
        assertEquals(NetWord.LET_GO, word!!.word)
        assertTrue(word.at < local(2026, 11, 3, 9, 0), "the net speaks before the ring comes back")
        assertNull(nextFire(phone.reminder, phone.now, zone, defaultTime), "Home files it under Vencidos")
        val week = phone.run(until = local(2026, 11, 5, 12, 0))
        assertEquals(listOf(local(2026, 11, 3, 9, 0), local(2026, 11, 4, 9, 0), local(2026, 11, 5, 9, 0)), week.map { it.at })
        // The net's word was said about Monday's ring; a word about Thursday's is owed again.
        val said = phone.reminder.copy(nudgedAt = local(2026, 11, 2, 12, 0))
        assertEquals(local(2026, 11, 5, 9, 0), said.netDue(phone.now, zone, defaultTime, net)!!.about)
        // Thursday afternoon, the hecho at last: it answers Thursday's ring and spends nothing ahead.
        phone.now = local(2026, 11, 5, 15, 0)
        assertNull(phone.reminder.momentDealtWith(phone.now, zone, defaultTime))
        phone.deal(Simulation.Deal.Done)
        assertEquals(Wake(local(2026, 11, 6, 9, 0), null), phone.arm(), "Friday's nine, a span after the hecho")
        assertNull(phone.reminder.netDue(phone.now, zone, defaultTime, net), "answered: the net has nothing to say")
    }

    @Test
    fun `a state place under desde el hecho left unanswered asks again only once a span from the ring is over`() {
        val created = local(2026, 11, 1, 12, 0)
        val whileHome = row(TriggerRule(home), recurrence = Recurrence.After(1, RecurrenceUnit.DAYS, RecurrenceFrom.DEALT), createdAt = created)
        val phone = Simulation(whileHome, created)
        phone.now = local(2026, 11, 2, 19, 0)
        assertNotNull(phone.arrive(0))
        val ringing = phone.reminder
        phone.now = local(2026, 11, 2, 23, 0)
        assertTrue(ringing.presenceAlreadyRang(home, 0, phone.now, zone, DEFAULT_DAY_START), "the same evening it has had its say")
        phone.now = local(2026, 11, 3, 19, 0)
        assertFalse(ringing.presenceAlreadyRang(home, 0, phone.now, zone, DEFAULT_DAY_START), "a span on, still unanswered, it asks again")
        assertTrue(ringing.watchedCircles(phone.now, zone, defaultTime).any { it.ruleIndex == 0 }, "and its circle is still watched for it")
    }

    // A pause and a resume across a plazo.

    @Test
    fun `a pause across a window plazo comes back into a fresh round that can still ring the same evening`() {
        val created = local(2026, 11, 3, 12, 0)
        val both = row(
            TriggerRule(Trigger.TimeOfDay(t(19))),
            TriggerRule(Trigger.TimeOfDay(t(21))),
            match = RuleMatch.ALL,
            recurrence = Recurrence.After(1, RecurrenceUnit.DAYS, RecurrenceFrom.DEALT),
            deadline = Deadline.Window(t(18), t(22)),
            createdAt = created,
        )
        val written = both.copy(expiresAt = both.roundExpiry(created, zone, defaultTime))
        assertEquals(local(2026, 11, 3, 22, 0), written.expiresAt)
        val phone = Simulation(written, created)
        assertNull(phone.step(), "19:00 is a note under todos")
        assertEquals(setOf(0), phone.reminder.firedRules)
        // Paused at 20:00 and resumed the next morning: the window closed meanwhile. A paused row
        // arms nothing (the re-arm pass clears the armed moment of anything not active).
        val paused = phone.reminder.copy(status = Status.PAUSED, armedFor = null, armedRule = null)
        val resumedAt = local(2026, 11, 4, 10, 0)
        val round = paused.roundOnResume(resumedAt, zone, defaultTime)
        assertNotNull(round, "a deadline that passed during the pause starts a fresh round")
        assertEquals(emptySet<Int>(), round!!.firedRules, "yesterday's 19:00 is not today's")
        assertEquals(local(2026, 11, 4, 22, 0), round.expiresAt, "today's window")
        val back = Simulation(paused.copy(status = Status.ACTIVE, firedRules = round.firedRules, expiresAt = round.expiresAt, resumedAt = resumedAt), resumedAt)
        assertEquals(local(2026, 11, 4, 21, 0), back.run(until = local(2026, 11, 4, 23, 0)).single().at, "rings with the last of today's two")
        assertTrue(back.lapses.isEmpty(), "and nothing was written off")
    }

    @Test
    fun `a pause that ends before the plazo closes keeps the round it was in`() {
        val created = local(2026, 11, 3, 12, 0)
        val both = row(
            TriggerRule(Trigger.TimeOfDay(t(19))),
            TriggerRule(Trigger.TimeOfDay(t(21))),
            match = RuleMatch.ALL,
            deadline = Deadline.Window(t(18), t(22)),
            createdAt = created,
        ).let { it.copy(expiresAt = it.roundExpiry(created, zone, defaultTime)) }
        val ticked = both.copy(firedRules = setOf(0), status = Status.PAUSED)
        assertNull(ticked.roundOnResume(local(2026, 11, 3, 20, 30), zone, defaultTime), "the window is still open: nothing to start again")
    }

    // What a long snooze says it costs, journeyed.

    @Test
    fun `next week on a daily nine swallows six mornings, and tomorrow swallows none`() {
        val created = local(2026, 11, 1, 12, 0)
        val daily = row(TriggerRule(Trigger.TimeOfDay(t(9))), createdAt = created)
        val phone = Simulation(daily, created)
        phone.step()
        val terms = SnoozeTerms(DayShape.DEFAULT, DayParts(), DEFAULT_SNOOZE_MINUTES)
        val rung = phone.reminder
        assertEquals(6, rung.ringsSkippedUntil(Snooze.NEXT_WEEK.until(phone.now, zone, terms), phone.now, zone, defaultTime))
        assertEquals(0, rung.ringsSkippedUntil(Snooze.TOMORROW.until(phone.now, zone, terms), phone.now, zone, defaultTime))
        assertFalse(skipsWorthSaying(1))
        // And the snooze, taken, does swallow them: the next ring is the snooze's.
        phone.deal(Simulation.Deal.Later(Snooze.NEXT_WEEK))
        assertEquals(local(2026, 11, 9, 9, 0), phone.step()!!.at)
    }

    @Test
    fun `an unanswered desde el hecho is counted by its come-backs when a snooze would swallow them`() {
        val created = local(2026, 11, 1, 20, 0)
        val pills = row(
            TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 2, 9, 0))),
            recurrence = Recurrence.After(1, RecurrenceUnit.DAYS, RecurrenceFrom.DEALT, hour = RecurrenceHour.At(t(9))),
            createdAt = created,
        )
        val phone = Simulation(pills, created)
        phone.step()
        val terms = SnoozeTerms(DayShape.DEFAULT, DayParts(), DEFAULT_SNOOZE_MINUTES)
        assertEquals(6, phone.reminder.ringsSkippedUntil(Snooze.NEXT_WEEK.until(phone.now, zone, terms), phone.now, zone, defaultTime))
    }

    // "Mañana" at a quarter past midnight, through a snooze and a dated rule.

    @Test
    fun `a quarter past midnight, tomorrow morning and a dictated tomorrow both mean the morning about to come`() {
        val late = local(2026, 11, 4, 0, 15) // Wednesday, still Tuesday night
        val terms = SnoozeTerms(DayShape.DEFAULT, DayParts(), DEFAULT_SNOOZE_MINUTES)
        assertEquals(local(2026, 11, 4, 9, 0), Snooze.TOMORROW_MORNING.until(late, zone, terms))
        val tomorrow = Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS), time = t(10))
        val settled = settleRelativeDates(listOf(TriggerRule(tomorrow)), late, zone, DayShape.DEFAULT).single().trigger
        assertEquals(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 4, 10, 0)), settled)
        // And from breakfast on, tomorrow is the calendar's.
        val morning = local(2026, 11, 4, 8, 30)
        assertEquals(local(2026, 11, 5, 9, 0), Snooze.TOMORROW_MORNING.until(morning, zone, terms))
    }

    // «La próxima vez que llegue» under a rest «desde que suena».

    @Test
    fun `next time I get home outranks the rest, and once it has rung the rest holds the doorway again`() {
        val created = local(2026, 11, 1, 12, 0)
        val door = home.copy(onCrossing = true)
        val arriving = row(TriggerRule(door), recurrence = Recurrence.After(24, RecurrenceUnit.HOURS, RecurrenceFrom.RANG), createdAt = created)
        val phone = Simulation(arriving, created)
        phone.now = local(2026, 11, 2, 18, 0)
        assertNotNull(phone.arrive(0) { Simulation.Deal.NextTime })
        assertNull(phone.reminder.restUntil(zone, DEFAULT_DAY_START), "the promise is about the next arrival")
        assertTrue(phone.reminder.watchedCircles(phone.now, zone, defaultTime).single { it.ruleIndex == 0 }.opensAt == null, "and the door is watched now")
        phone.now = local(2026, 11, 2, 21, 0)
        assertNotNull(phone.arrive(0))
        assertFalse(phone.reminder.snoozedToRules, "the ring kept the promise")
        assertEquals(local(2026, 11, 3, 21, 0), phone.reminder.restUntil(zone, DEFAULT_DAY_START), "and the rest counts from that ring")
        assertNotNull(phone.reminder.watchedCircles(phone.now, zone, defaultTime).single { it.ruleIndex == 0 }.opensAt)
    }

    // A hecho after midnight on an evening «desde el hecho», with the come-back in between.

    @Test
    fun `an evening ring answered after midnight counts from its day, and left unanswered comes back each evening`() {
        val created = local(2026, 11, 1, 12, 0)
        val evening = row(
            TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 2, 22, 0))),
            recurrence = Recurrence.After(1, RecurrenceUnit.DAYS, RecurrenceFrom.DEALT, hour = RecurrenceHour.At(t(22))),
            createdAt = created,
        )
        val phone = Simulation(evening, created)
        assertEquals(local(2026, 11, 2, 22, 0), phone.step()!!.at)
        assertEquals(local(2026, 11, 3, 22, 0), phone.step()!!.at, "unanswered, it comes back the next evening")
        phone.now = local(2026, 11, 4, 0, 15)
        phone.deal(Simulation.Deal.Done)
        assertEquals(Wake(local(2026, 11, 4, 22, 0), null), phone.arm(), "this evening, not tomorrow's")
    }

    @Test
    fun `a come-back put off to tomorrow morning rings then, and unanswered comes back a span after that ring`() {
        val created = local(2026, 11, 1, 20, 0)
        val pills = row(
            TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 11, 2, 9, 0))),
            recurrence = Recurrence.After(1, RecurrenceUnit.DAYS, RecurrenceFrom.DEALT, hour = RecurrenceHour.At(t(9))),
            createdAt = created,
        )
        val phone = Simulation(pills, created)
        assertEquals(local(2026, 11, 2, 9, 0), phone.step()!!.at)
        val back = phone.step { Simulation.Deal.Later(Snooze.TWO_HOURS) }!!
        assertEquals(local(2026, 11, 3, 9, 0), back.at)
        assertNull(back.ruleIndex, "the come-back is the reminder's own ring")
        assertEquals(local(2026, 11, 3, 11, 0), phone.step()!!.at, "the snooze")
        assertEquals(local(2026, 11, 4, 9, 0), phone.step()!!.at, "and a span on, at its hour, it comes back again")
    }
}
