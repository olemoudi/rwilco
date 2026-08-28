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
            status = statusAfterDismissal(copy(dealtThrough = consumed ?: dealtThrough), at, zone, defaultTime),
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
