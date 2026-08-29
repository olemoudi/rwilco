package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * "Hecho" on something that has not rung yet.
 *
 * A daily at two o'clock, ticked off this morning, is somebody saying they have done tomorrow's
 * — so tomorrow is spent and the day after is what is coming, and doing it again sends it on
 * another day. What it must never do is that to a firing: after it rings, the ring is what is
 * being answered, and taking tomorrow's with it would skip a day nobody asked to skip.
 */
class DoneAheadTest {

    private val two = LocalTime.of(14, 0)

    /** Every day at two, and nothing else: the recurrence is the whole arrangement. */
    private fun daily(
        lastFiredAt: Instant? = null,
        lastDealtAt: Instant? = null,
        dealtThrough: Instant? = null,
        ends: RepeatEnd = RepeatEnd.Never,
    ) = Reminder(
        id = "r1",
        text = "Estirar la espalda",
        recurrence = Recurrence.Calendar(
            Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 28), unit = RepeatUnit.DAY, time = two, ends = ends),
        ),
        createdAt = now.minusSeconds(86_400),
        updatedAt = now.minusSeconds(86_400),
        lastFiredAt = lastFiredAt,
        lastDealtAt = lastDealtAt,
        dealtThrough = dealtThrough,
    )

    private fun Reminder.next() = (nextFire(this, now, zone, defaultTime) as? NextFire.Scheduled)?.at
    private fun Reminder.spends() = momentDealtWith(now, zone, defaultTime)

    /** What the app does on a "hecho": spend the moment, and take the round with it. */
    private fun Reminder.dealtWith(at: Instant = now): Reminder {
        val consumed = momentDealtWith(at, zone, defaultTime)
        return copy(
            snoozedUntil = null,
            firedRules = emptySet(),
            lastDealtAt = at,
            dealtThrough = consumed ?: dealtThrough,
            status = statusAfterDismissal(copy(lastDealtAt = at, dealtThrough = consumed ?: dealtThrough), at, zone, defaultTime),
        )
    }

    @Test
    fun `ticking off tomorrow's makes the day after the next one`() {
        // Thursday afternoon; the next one is Friday at two.
        val reminder = daily()
        assertEquals(local(2026, 8, 28, 14, 0), reminder.next())
        assertEquals(local(2026, 8, 28, 14, 0), reminder.spends(), "that is the one being dealt with")

        val once = reminder.dealtWith()
        assertEquals(local(2026, 8, 29, 14, 0), once.next(), "Friday is done, so Saturday is next")
        assertEquals(Status.ACTIVE, once.status)
    }

    @Test
    fun `and doing it again sends it on another day, and so on`() {
        var reminder = daily()
        val days = (28..31).map { local(2026, 8, it, 14, 0) }
        // Each "hecho" spends the one it was showing and moves to the next.
        for ((index, day) in days.withIndex()) {
            assertEquals(day, reminder.next(), "after $index of them")
            reminder = reminder.dealtWith()
        }
        assertEquals(local(2026, 9, 1, 14, 0), reminder.next())
    }

    @Test
    fun `answering a firing spends nothing extra, or a day would go missing`() {
        // It rang at two this afternoon and is waiting for an answer.
        val rang = local(2026, 8, 27, 14, 0)
        val ringing = daily(lastFiredAt = rang)
        assertNull(ringing.spends(), "the ring is what is being answered")
        assertEquals(local(2026, 8, 28, 14, 0), ringing.next())
        val answered = ringing.dealtWith()
        assertEquals(local(2026, 8, 28, 14, 0), answered.next(), "tomorrow is still tomorrow")
    }

    @Test
    fun `with nothing to come back on, a hecho does the moment that is coming`() {
        // No "Vuelve": the only round is the one coming, however far off it is.
        val tomorrow = Trigger.AtDateTime(LocalDate.of(2026, 12, 31).atTime(20, 0))
        val once = Reminder(id = "r1", text = "x", rules = listOf(TriggerRule(tomorrow)), createdAt = now, updatedAt = now)
        assertEquals(local(2026, 12, 31, 20, 0), once.spends())
    }

    @Test
    fun `a hecho does not do a moment a whole season away`() {
        // "Al llegar a casa, o el 31 de diciembre a las ocho", coming back every day. Swiped
        // done on Home in August: the round that is coming is tomorrow's, not December's. It
        // used to spend the 31st, and the place was neither watched nor armed until January.
        val home = Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa")
        val newYearsEve = Trigger.AtDateTime(LocalDate.of(2026, 12, 31).atTime(20, 0))
        val reminder = Reminder(
            id = "r1",
            text = "Llamar a la abuela",
            rules = listOf(TriggerRule(home), TriggerRule(newYearsEve)),
            recurrence = Recurrence.After(1, RecurrenceUnit.DAYS),
            createdAt = now.minusSeconds(86_400),
            updatedAt = now.minusSeconds(86_400),
        )
        assertEquals(local(2026, 12, 31, 20, 0), reminder.next(), "the date is what Home shows")
        assertNull(reminder.spends(), "and a hecho now does not do December")
        val dealt = reminder.dealtWith()
        assertNull(dealt.dealtThrough)
        assertEquals(local(2026, 8, 28, 0, 0), dealt.restUntil(zone, LocalTime.of(9, 0)), "the rest ends with the day")
        assertEquals(local(2026, 12, 31, 20, 0), nextWake(dealt, local(2026, 8, 28, 12, 0), zone, defaultTime)?.at, "December is still armed")
    }

    @Test
    fun `done ahead on a date inside the calendar's next step is still done ahead`() {
        // "El 26 a las 20:00, y vuelve cada mes", ticked off on the 27th of August: the 26th of
        // September is the round that is coming, and doing it now sends it on to October.
        val twentySixth = Trigger.AtDateTime(LocalDate.of(2026, 9, 26).atTime(20, 0))
        val monthly = Trigger.Repeat(startsOn = LocalDate.of(2026, 9, 26), unit = RepeatUnit.MONTH, time = LocalTime.of(20, 0))
        val reminder = Reminder(
            id = "r1",
            text = "Pagar el alquiler",
            rules = listOf(TriggerRule(twentySixth)),
            recurrence = Recurrence.Calendar(monthly),
            createdAt = now.minusSeconds(86_400),
            updatedAt = now.minusSeconds(86_400),
        )
        assertEquals(local(2026, 9, 26, 20, 0), reminder.spends())
        assertEquals(local(2026, 10, 26, 20, 0), reminder.dealtWith().next())
    }

    @Test
    fun `a series dealt through its last date is finished`() {
        // Two dates only: the 28th and the 29th.
        var reminder = daily(ends = RepeatEnd.After(2))
        reminder = reminder.dealtWith()
        assertEquals(local(2026, 8, 29, 14, 0), reminder.next())
        assertEquals(Status.ACTIVE, reminder.status)
        reminder = reminder.dealtWith()
        assertEquals(Status.DONE, reminder.status, "nothing left to come back for")
    }

    @Test
    fun `a span counts from the moment that was dealt with, not from the afternoon it happened in`() {
        // "A las 14:00 mañana, y luego cada día a la misma hora."
        val reminder = Reminder(
            id = "r1",
            text = "Pastilla",
            rules = listOf(TriggerRule(Trigger.AtDateTime(LocalDate.of(2026, 8, 28).atTime(two)))),
            recurrence = Recurrence.After(1, RecurrenceUnit.DAYS, hour = RecurrenceHour.At(two)),
            createdAt = now.minusSeconds(86_400),
            updatedAt = now.minusSeconds(86_400),
        )
        assertEquals(local(2026, 8, 28, 14, 0), reminder.next())
        val once = reminder.dealtWith()
        assertEquals(local(2026, 8, 29, 14, 0), once.next(), "a day after the one dealt with")
    }

    @Test
    fun `a place has no moment to do in advance`() {
        val place = Reminder(
            id = "r1",
            text = "Sacar la basura",
            rules = listOf(TriggerRule(Trigger.Location(40.4, -3.7, 150, Presence.INSIDE, "Casa"))),
            recurrence = Recurrence.After(1, RecurrenceUnit.DAYS),
            createdAt = now,
            updatedAt = now,
        )
        assertNull(place.spends())
    }
}
