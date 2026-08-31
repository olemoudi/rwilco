package dev.rwilco.model

import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

/**
 * Four months of "los viernes a las 14:00, y vuelve cada 30 días", wound forward one alarm at a
 * time — the same steps the scheduler and the firing take on a phone ([Simulation]).
 *
 * A single next-fire is not enough to see what the three readings of [SpanLanding] actually
 * *are*: the difference between them is a shape that only shows over rounds. "El siguiente"
 * turns thirty days into thirty-five and does it again every time; "el más cercano" holds a
 * steady twenty-eight; "justo el plazo" is thirty to the day and off the Fridays entirely.
 * Every one of those is somebody's reminder, and none of them is a bug.
 */
class SpanLandingJourneyTest {

    /** Wednesday 1 April 2026, mid-morning: the reminder is written, the first Friday is the 3rd. */
    private val written = local(2026, 4, 1, 10, 0)

    private fun journey(landing: SpanLanding, hour: RecurrenceHour = RecurrenceHour.At(LocalTime.of(14, 0))): List<String> {
        val reminder = Fixtures.reminder(
            Trigger.TimeOfDay(LocalTime.of(14, 0), setOf(DayOfWeek.FRIDAY)),
            updatedAt = written,
        ).copy(recurrence = Recurrence.After(30, RecurrenceUnit.DAYS, hour = hour, landing = landing))
        val phone = Simulation(reminder, written)
        // Answered the moment it rings, every time: the cleanest case, and the one where the
        // three readings differ for no reason but the reading itself.
        phone.run(until = written.plus(Duration.ofDays(150))) { Simulation.Deal.Done }
        return phone.rings.map { it.local(zone).toString() }
    }

    @Test
    fun `the next allowed day turns thirty days into thirty-five, over and over`() {
        assertEquals(
            listOf(
                "2026-04-03T14:00",
                "2026-05-08T14:00",
                "2026-06-12T14:00",
                "2026-07-17T14:00",
                "2026-08-21T14:00",
            ),
            journey(SpanLanding.NEXT),
        )
        // Thirty-five, not thirty, and the same thirty-five every round: thirty days after a
        // Friday is a Sunday, and the first Friday after a Sunday is five days later. Nothing
        // is wrong here — it is simply not what "cada 30 días" says, which is the whole reason
        // the question is now asked out loud.
        val gaps = gapsOf(journey(SpanLanding.NEXT))
        assertTrue(gaps.all { it == 35L }, "the drift is systematic, not incidental: $gaps")
    }

    @Test
    fun `the closest allowed day holds a steady twenty-eight`() {
        assertEquals(
            listOf(
                "2026-04-03T14:00",
                "2026-05-01T14:00",
                "2026-05-29T14:00",
                "2026-06-26T14:00",
                "2026-07-24T14:00",
                "2026-08-21T14:00",
            ),
            journey(SpanLanding.NEAREST),
        )
        // Two days early rather than five days late, every time — and it settles into four
        // weeks, which is what "cada 30 días, en viernes" means to the person who wrote it.
        val gaps = gapsOf(journey(SpanLanding.NEAREST))
        assertTrue(gaps.all { it == 28L }, "$gaps")
    }

    @Test
    fun `exactly the span is thirty days to the day, Fridays or not`() {
        assertEquals(
            listOf(
                "2026-04-03T14:00",
                "2026-05-03T14:00",
                "2026-06-02T14:00",
                "2026-07-02T14:00",
                "2026-08-01T14:00",
            ),
            journey(SpanLanding.EXACT),
        )
        // Sunday, Tuesday, Thursday, Saturday. The rules said Fridays and the person said
        // thirty days; this is the answer that believes the second one, and the first ring is
        // still the rules', because until it has been dealt with there is no span to count.
        val gaps = gapsOf(journey(SpanLanding.EXACT))
        assertTrue(gaps.all { it == 30L }, "$gaps")
    }

    @Test
    fun `until something is dealt with there is no span, so the reading changes nothing`() {
        // The span is counted from the "hecho" ([RecurrenceFrom.DEALT]), so a reminder nobody
        // has answered has no rest at all and the rule speaks for itself: every Friday at two,
        // which is what a standing hour does. All three readings are therefore the same list
        // — the landing question only ever bites on the far side of a "hecho", and it must not
        // reach back and quietly change what an unanswered reminder does.
        val ignored = SpanLanding.entries.map { landing ->
            val reminder = Fixtures.reminder(
                Trigger.TimeOfDay(LocalTime.of(14, 0), setOf(DayOfWeek.FRIDAY)),
                updatedAt = written,
            ).copy(recurrence = Recurrence.After(30, RecurrenceUnit.DAYS, landing = landing))
            val phone = Simulation(reminder, written)
            phone.run(until = written.plus(Duration.ofDays(90))) { Simulation.Deal.Ignore }
            assertEquals(Status.ACTIVE, phone.reminder.status, "$landing")
            phone.rings.map { it.local(zone).toString() }
        }
        assertEquals(13, ignored.first().size, "every Friday for thirteen weeks")
        assertEquals("2026-04-03T14:00", ignored.first().first())
        assertTrue(ignored.all { it == ignored.first() }, "the readings disagree before any «hecho»: $ignored")
    }

    @Test
    fun `a phone switched off across a round still rings, late, once`() {
        // The catch-up path: the alarm never arrived, and the launch pass rings it late rather
        // than losing the round. Under "justo el plazo" the moment it is late for is the span's
        // own, which is the one thing about that reading the catch-up had to be shown to handle.
        for (landing in SpanLanding.entries) {
            val reminder = Fixtures.reminder(
                Trigger.TimeOfDay(LocalTime.of(14, 0), setOf(DayOfWeek.FRIDAY)),
                updatedAt = written,
            ).copy(recurrence = Recurrence.After(30, RecurrenceUnit.DAYS, hour = RecurrenceHour.At(LocalTime.of(14, 0)), landing = landing))
            val phone = Simulation(reminder, written)
            // The first round, answered.
            phone.step { Simulation.Deal.Done }
            assertEquals(local(2026, 4, 3, 14, 0), phone.rings.single().rangFor, "$landing")
            // Then the phone is off until five days past the moment that was armed.
            val next = phone.arm()!!.at
            val wokeUp = next.plus(Duration.ofDays(5))
            val late = phone.sleepUntil(wokeUp)
            assertEquals(1, late.size, "$landing rang ${late.size} times catching up")
            assertEquals(next, late.single().late, "$landing rang late for the wrong moment")
            // Recorded against the moment it actually rang, which is what a late ring is: the
            // round is not lost, and the next span counts from here rather than from a Friday
            // the phone slept through.
            assertEquals(wokeUp, late.single().rangFor, "$landing")
        }
    }

    @Test
    fun `written as two triggers it is the same reminder, ring for ring`() {
        // The point of "un día de la semana": "los viernes" and "a las 14:00" as two triggers
        // joined by "a la vez" have to mean exactly what the one welded trigger meant — and
        // keep meaning it through the recurrence, which is where the days are read again
        // ([Reminder.daysNamedByRules]) and where a fold that lost them would show up as a
        // series landing on Sundays.
        for (landing in SpanLanding.entries) {
            val apart = Fixtures.reminder(
                Trigger.TimeOfDay(LocalTime.of(14, 0)),
                Trigger.Weekday(setOf(DayOfWeek.FRIDAY)),
                updatedAt = written,
            ).copy(
                ruleMatch = RuleMatch.TOGETHER,
                recurrence = Recurrence.After(30, RecurrenceUnit.DAYS, hour = RecurrenceHour.At(LocalTime.of(14, 0)), landing = landing),
            )
            val phone = Simulation(apart, written)
            phone.run(until = written.plus(Duration.ofDays(150))) { Simulation.Deal.Done }
            assertEquals(
                journey(landing),
                phone.rings.map { it.local(zone).toString() },
                "the two spellings disagree under $landing",
            )
        }
    }

    @Test
    fun `the days are found through the fold, not only on the rule that rings`() {
        // The union is taken over every rule, and under "a la vez" the rule that carries the
        // days is not the rule that rings — the hour is. Read off the ringing rule alone the
        // span would have had nothing to bend to and "el más cercano" would have been a no-op.
        val apart = Fixtures.reminder(
            Trigger.TimeOfDay(LocalTime.of(14, 0)),
            Trigger.Weekday(setOf(DayOfWeek.FRIDAY)),
            updatedAt = written,
        ).copy(ruleMatch = RuleMatch.TOGETHER)
        assertEquals(setOf(DayOfWeek.FRIDAY), apart.daysNamedByRules())
    }

    private fun gapsOf(rings: List<String>): List<Long> =
        rings.zipWithNext { a, b ->
            Duration.between(
                java.time.LocalDateTime.parse(a).atZone(zone),
                java.time.LocalDateTime.parse(b).atZone(zone),
            ).toDays()
        }
}
