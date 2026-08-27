package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The calendar in "Vuelve": the shape that used to be a trigger, asked the questions a
 * recurrence is asked.
 *
 * The date arithmetic itself is `RepeatTriggerTest`'s and is unchanged — the same
 * [Trigger.Repeat] does it. What is new is everything around it: that it rings without a rule
 * behind it, that a moment it rang is spent, that a series with an end finishes, that its fences
 * are honoured, and that with rules on the reminder it becomes the rest they wait out.
 */
class CalendarRecurrenceTest {

    private val dayStart: LocalTime = LocalTime.of(9, 0)

    /** Mondays at nine, from Monday the 24th of August 2026. */
    private val mondays = Trigger.Repeat(
        startsOn = LocalDate.of(2026, 8, 24),
        unit = RepeatUnit.WEEK,
        time = LocalTime.of(9, 0),
        days = setOf(DayOfWeek.MONDAY),
    )

    private fun reminder(
        recurrence: Recurrence,
        vararg triggers: Trigger,
        createdAt: Instant = local(2026, 8, 27, 15, 0),
        lastDealtAt: Instant? = null,
        lastFiredAt: Instant? = null,
    ) = Reminder(
        id = "r1",
        text = "Sacar la basura",
        rules = triggers.map { TriggerRule(it) },
        recurrence = recurrence,
        createdAt = createdAt,
        updatedAt = createdAt,
        lastDealtAt = lastDealtAt,
        lastFiredAt = lastFiredAt,
    )

    @Test
    fun `a calendar with no rules is the whole arrangement, and rings on its own dates`() {
        val now = local(2026, 8, 27, 15, 0)
        val bins = reminder(Recurrence.Calendar(mondays))
        val next = nextFire(bins, now, zone, defaultTime, dayStart) as NextFire.Scheduled
        assertEquals(local(2026, 8, 31, 9, 0), next.at)
        assertNull(next.trigger, "no rule behind it, and the card must not pretend there is")
        // And that is what the alarm is set for, with no rule to tick off.
        assertEquals(Wake(local(2026, 8, 31, 9, 0), null), nextWake(bins, now, zone, defaultTime, dayStart))
    }

    @Test
    fun `it never asks the anchor, so an unanswered one still comes back next Monday`() {
        // Rang on the 31st and nobody dealt with it: a span would be stuck on a moment already
        // gone, and a calendar is not a span. The 7th is the answer either way.
        val rang = local(2026, 8, 31, 9, 0)
        val bins = reminder(Recurrence.Calendar(mondays), lastFiredAt = rang)
        val next = nextFire(bins, rang.plusSeconds(60), zone, defaultTime, dayStart) as NextFire.Scheduled
        assertEquals(local(2026, 9, 7, 9, 0), next.at)
    }

    @Test
    fun `an alarm a breath early does not ring the same Monday twice`() {
        val early = local(2026, 8, 31, 9, 0).minusSeconds(1)
        val bins = reminder(Recurrence.Calendar(mondays), lastFiredAt = local(2026, 8, 31, 9, 0))
        val next = nextFire(bins, early, zone, defaultTime, dayStart) as NextFire.Scheduled
        assertEquals(local(2026, 9, 7, 9, 0), next.at, "the moment it rang for is spent")
    }

    @Test
    fun `a series that has run out is finished, where a span never is`() {
        val ends = Recurrence.Calendar(mondays.copy(ends = RepeatEnd.After(2)))
        val bins = reminder(ends, lastFiredAt = local(2026, 8, 31, 9, 0))
        val now = local(2026, 8, 31, 9, 5)
        assertEquals(Status.DONE, statusAfterDismissal(bins, now, zone, defaultTime))
        // One with a Monday still to come stays.
        assertEquals(
            Status.ACTIVE,
            statusAfterDismissal(reminder(Recurrence.Calendar(mondays), lastFiredAt = local(2026, 8, 31, 9, 0)), now, zone, defaultTime),
        )
    }

    @Test
    fun `a fence the calendar cannot clear moves it to the next date that can`() {
        // Every day at nine, and only in the first three days of a week: Thursday's nine is out,
        // and the walk lands on the following Monday.
        val daily = Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 24), unit = RepeatUnit.DAY, time = LocalTime.of(9, 0))
        val onlyEarlyWeek = Condition.TimeWindow(
            from = LocalTime.of(8, 0),
            to = LocalTime.of(10, 0),
            days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY),
        )
        val bins = reminder(Recurrence.Calendar(daily, listOf(onlyEarlyWeek)))
        val thursday = local(2026, 8, 27, 15, 0)
        val next = nextFire(bins, thursday, zone, defaultTime, dayStart) as NextFire.Scheduled
        assertEquals(local(2026, 8, 31, 9, 0), next.at)
    }

    @Test
    fun `a fence nothing can ever clear answers never instead of looping`() {
        val daily = Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 24), unit = RepeatUnit.DAY, time = LocalTime.of(9, 0))
        val impossible = Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))
        val bins = reminder(Recurrence.Calendar(daily, listOf(impossible)))
        assertNull(nextFire(bins, local(2026, 8, 27, 15, 0), zone, defaultTime, dayStart))
    }

    @Test
    fun `with a rule on the reminder, the calendar is the rest that rule waits out`() {
        // "Al llegar a casa" — dealt with on Thursday, and the circle is not watched again
        // until Monday, when the calendar says the next round starts.
        val home = Trigger.Location(40.4, -3.7, 200, Presence.INSIDE, "Casa")
        val dealt = local(2026, 8, 27, 15, 0)
        val bins = reminder(Recurrence.Calendar(mondays), home, lastDealtAt = dealt)
        // A day-counted rest ends with the day when a rule names an hour of its own; a place
        // names none, so the rest ends at the calendar's own moment.
        assertEquals(local(2026, 8, 31, 9, 0), bins.restUntil(zone, dayStart))
        assertEquals(NextFire.WhenAt(home), nextFire(bins, dealt.plusSeconds(60), zone, defaultTime, dayStart))
    }

    @Test
    fun `an hour drawn from the waking day is drawn by reminder and day, not by chance`() {
        // No time on the calendar: the moment comes from that day's waking hours, and the same
        // reminder on the same day always gets the same one — the screen and the scheduler have
        // to agree without storing it.
        val loose = Recurrence.Calendar(mondays.copy(time = null))
        val bins = reminder(loose)
        val now = local(2026, 8, 27, 15, 0)
        val first = nextFire(bins, now, zone, defaultTime, dayStart) as NextFire.Scheduled
        val again = nextFire(bins, now, zone, defaultTime, dayStart) as NextFire.Scheduled
        assertEquals(first.at, again.at)
        assertEquals(LocalDate.of(2026, 8, 31), first.at.atZone(zone).toLocalDate())
    }

    @Test
    fun `a calendar is anchored, counts in days, and is not a span`() {
        val calendar = Recurrence.Calendar(mondays)
        assertEquals(true, calendar.isAnchored)
        assertEquals(true, calendar.isCalendar)
        assertEquals(true, calendar.countsInDays)
        assertEquals(true, calendar.repeats)
        // nextRecurrence is the span question, and a calendar is not one of those.
        assertNull(nextRecurrence(calendar, local(2026, 8, 27, 15, 0), zone, dayStart))
    }

    @Test
    fun `a bad calendar blocks the save the way a bad trigger does`() {
        val backwards = mondays.copy(ends = RepeatEnd.On(LocalDate.of(2026, 8, 1)))
        assertEquals(
            listOf(ValidationError.BadRecurrence(TriggerProblem.ENDS_BEFORE_START)),
            validate("Basura", emptyList(), Recurrence.Calendar(backwards)),
        )
        assertEquals(emptyList<ValidationError>(), validate("Basura", emptyList(), Recurrence.Calendar(mondays)))
    }

    @Test
    fun `a calendar that can never clear its fences says so before anybody waits a week`() {
        val daily = Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 24), unit = RepeatUnit.DAY, time = LocalTime.of(9, 0))
        val now = local(2026, 8, 27, 15, 0)
        val impossible = Recurrence.Calendar(daily, listOf(Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))))
        assertEquals(RecurrenceWarning.NEVER_FIRES, recurrenceWarning(impossible, now, zone))
        // A series whose ending is behind us is the other thing worth saying.
        val over = Recurrence.Calendar(daily.copy(ends = RepeatEnd.On(LocalDate.of(2026, 8, 26))))
        assertEquals(RecurrenceWarning.OVER, recurrenceWarning(over, now, zone))
        // And an ordinary one has nothing to say, nor has anything that is not a calendar.
        assertNull(recurrenceWarning(Recurrence.Calendar(daily), now, zone))
        assertNull(recurrenceWarning(Recurrence.After(6, RecurrenceUnit.HOURS), now, zone))
    }
}
