package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * The ways one moment could ring twice.
 *
 * Every one of these is the same shape of mistake: the scheduler asks "what is next?", is handed
 * a moment that has already rung, arms an alarm for it, and the alarm — being in the past —
 * arrives at once. That is not a reminder ringing twice; it is a reminder ringing until somebody
 * makes it stop. The rules were taught this in 0.7.3 (`searchFrom`); the recurrences were not,
 * and a recurrence is the one shape that produces a moment with no rule behind it.
 */
class RingingTwiceTest {

    private val dayStart: LocalTime = LocalTime.of(9, 0)
    private val written = local(2026, 8, 27, 15, 0)

    private fun everySixHours(
        vararg triggers: Trigger,
        lastDealtAt: Instant? = null,
        lastFiredAt: Instant? = null,
    ) = Reminder(
        id = "r1",
        text = "Pastillas",
        rules = triggers.map { TriggerRule(it) },
        recurrence = Recurrence.After(6, RecurrenceUnit.HOURS),
        createdAt = written,
        updatedAt = written,
        lastDealtAt = lastDealtAt,
        lastFiredAt = lastFiredAt,
    )

    @Test
    fun `a recurrence that has rung and has not been dealt with arms nothing else`() {
        // Written at 15:00, so its first moment is 21:00. It rang at 21:00 and nobody answered.
        val rang = local(2026, 8, 27, 21, 0)
        val ignored = everySixHours(lastFiredAt = rang)
        val now = rang.plusSeconds(30)

        assertNull(
            nextWake(ignored, now, zone, defaultTime, dayStart),
            "the moment it rang for is spent; arming it again is how a phone rings all night",
        )
        assertNull(
            nextFire(ignored, now, zone, defaultTime, dayStart),
            "and Home has a word for a moment that came and went: overdue",
        )
    }

    @Test
    fun `dealing with it is what starts the clock again`() {
        val rang = local(2026, 8, 27, 21, 0)
        val dealt = rang.plusSeconds(90)
        val answered = everySixHours(lastDealtAt = dealt, lastFiredAt = rang)

        assertEquals(
            dealt.plus(Duration.ofHours(6)),
            nextWake(answered, dealt.plusSeconds(1), zone, defaultTime, dayStart)?.at,
            "six hours after the last one, counted from the person",
        )
    }

    @Test
    fun `dealt with, the rules rest for the span and then speak again`() {
        // "Al llegar a casa, cada día." Dealt with at 19:30, at home: until nine the next
        // morning nothing is armed and the place is not a ring; from nine the place speaks
        // again — and it is the place that rings, on a fresh arrival, never the nine o'clock.
        val home = Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa")
        val dealt = local(2026, 8, 27, 19, 30)
        val bins = Reminder(
            id = "r3",
            text = "Sacar la basura",
            rules = listOf(TriggerRule(home)),
            recurrence = Recurrence.After(1, RecurrenceUnit.DAYS),
            createdAt = written,
            updatedAt = written,
            lastFiredAt = local(2026, 8, 27, 19, 0),
            lastDealtAt = dealt,
        )
        assertEquals(local(2026, 8, 28, 9, 0), bins.restUntil(zone, dayStart), "the span, counted from the hecho")
        assertNull(nextWake(bins, dealt.plusSeconds(60), zone, defaultTime, dayStart), "a place has no moment to arm, resting or not")
        assertNull(nextWake(bins, local(2026, 8, 28, 12, 0), zone, defaultTime, dayStart))
        assertTrue(nextFire(bins, local(2026, 8, 28, 12, 0), zone, defaultTime, dayStart) is NextFire.WhenAt, "and Home says: when you get there")

        // A clock rule finds its first moment after the rest, not the recurrence's own.
        val daily = Trigger.AtTime(LocalTime.of(8, 0), java.time.DayOfWeek.entries.toSet())
        val pills = everySixHours(daily, lastDealtAt = local(2026, 8, 28, 8, 5), lastFiredAt = local(2026, 8, 28, 8, 0))
        assertEquals(local(2026, 8, 29, 8, 0), nextWake(pills, local(2026, 8, 28, 8, 6), zone, defaultTime, dayStart)?.at, "eight tomorrow: the first moment past the six hours")

        // With nothing left in the rules, the recurrence's moment is the ring — and, rung and
        // ignored, it is spent like any other.
        val once = Trigger.AtDateTime(java.time.LocalDateTime.of(2026, 8, 28, 8, 0))
        val dose = everySixHours(once, lastDealtAt = local(2026, 8, 28, 8, 5), lastFiredAt = local(2026, 8, 28, 8, 0))
        assertEquals(Wake(local(2026, 8, 28, 14, 5), null), nextWake(dose, local(2026, 8, 28, 8, 6), zone, defaultTime, dayStart))
        assertNull(nextWake(dose.copy(lastFiredAt = local(2026, 8, 28, 14, 5)), local(2026, 8, 28, 14, 6), zone, defaultTime, dayStart))
    }

    @Test
    fun `a recurrence the phone slept through is still armed`() {
        // Never rang: the moment is in the past because the device was off, not because it was
        // answered. That one has to be armed, and arrives at once — which is the catch-up.
        val slept = everySixHours()
        val now = local(2026, 8, 28, 4, 0)

        assertEquals(
            local(2026, 8, 27, 21, 0),
            nextWake(slept, now, zone, defaultTime, dayStart)?.at,
            "an unheard moment is still owed",
        )
    }

    @Test
    fun `a recurrence rung before the moment it was armed for is still spent`() {
        // Alarms are allowed to arrive a breath early, and the ring is recorded against the
        // moment it rang FOR. A ring stamped a second before its own moment must still count.
        val moment = local(2026, 8, 27, 21, 0)
        val early = everySixHours(lastFiredAt = moment.minusMillis(1))

        assertNotNull(nextWake(early, moment.minusSeconds(1), zone, defaultTime, dayStart))
        assertNull(
            nextWake(everySixHours(lastFiredAt = moment), moment, zone, defaultTime, dayStart),
            "to the millisecond, which is the grain everything is stored at",
        )
    }

    @Test
    fun `a rule's own moment that has rung is spent too`() {
        // The other half of the same rule, and the one that already worked: a daily alarm that
        // has just rung looks for tomorrow's, not for the one it is standing on.
        val daily = Reminder(
            id = "r2",
            text = "Regar",
            rules = listOf(TriggerRule(Trigger.AtTime(LocalTime.of(9, 0), setOf(java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY)))),
            recurrence = Recurrence.ByTrigger,
            createdAt = written,
            updatedAt = written,
            lastFiredAt = local(2026, 8, 27, 9, 0),
        )

        assertEquals(
            local(2026, 8, 28, 9, 0),
            nextWake(daily, local(2026, 8, 27, 9, 0), zone, defaultTime, dayStart)?.at,
            "the Thursday it just rang for is not the Thursday it is waiting for",
        )
    }

    @Test
    fun `no two random draws in a period land on the same minute`() {
        // Two draws on one minute are two alarms on one instant. The window the sheet allows can
        // be as tight as a minute per draw, which is where the pushing-apart runs out of room.
        val tight = Trigger.Random(
            timesPer = MAX_RANDOM_TIMES,
            period = Period.DAY,
            from = LocalTime.of(9, 0),
            to = LocalTime.of(9, MAX_RANDOM_TIMES),
        )
        val roomy = tight.copy(to = LocalTime.of(10, 0))

        for (trigger in listOf(tight, roomy)) {
            for (index in 0 until 400) {
                val draws = RandomDraw.draws(trigger, "reminder-$index", 20_693L, zone)
                assertEquals(
                    draws.size,
                    draws.distinct().size,
                    "reminder-$index drew the same minute twice: $draws",
                )
            }
        }
    }
}
