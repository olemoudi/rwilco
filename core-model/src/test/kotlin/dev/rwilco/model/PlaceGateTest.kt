package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Which circles cost a position and which do not — the one decision in this app that spends a
 * radio, and until now the only one that could be asked nothing but a phone.
 *
 * The clock is a Thursday at 15:00 in Madrid ([Fixtures.now]).
 */
class PlaceGateTest {

    private val homeLat = 40.4169
    private val homeLng = -3.7035
    private val home = Trigger.Location(homeLat, homeLng, radiusM = 200, transition = Transition.ENTER, label = "Casa")
    private val leavingHome = home.copy(transition = Transition.EXIT)

    private fun reminder(
        vararg rules: TriggerRule,
        match: RuleMatch = RuleMatch.ANY,
        fired: Set<Int> = emptySet(),
        dealt: java.time.Instant? = null,
        recurrence: Recurrence = Recurrence.None,
    ) = Reminder(
        id = "r1",
        text = "Sacar la basura",
        rules = rules.toList(),
        ruleMatch = match,
        firedRules = fired,
        recurrence = recurrence,
        lastDealtAt = dealt,
        status = Status.ACTIVE,
        createdAt = now.minusSeconds(3_600),
        updatedAt = now.minusSeconds(3_600),
    )

    private fun Reminder.circles() = watchedCircles(now, zone, defaultTime)

    private fun at(hour: Int, minute: Int = 0, day: Int = 27) =
        TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, day, hour, minute)))

    @Test
    fun `a bare place is watched now, and asks for the ordinary cadence`() {
        val circles = reminder(TriggerRule(home)).circles()
        assertEquals(1, circles.size)
        assertNull(circles[0].opensAt, "a place with nothing in front of it is worth a fix now")
        assertEquals(Crossing.RINGS, circles[0].place.crossing)
        assertEquals(Duration.ZERO, circles[0].place.floor, "and no floor under the arithmetic")
    }

    @Test
    fun `hours of its own shut the gate until the run-up`() {
        // "Al llegar a casa, y sólo si es de 20 a 22": at three in the afternoon nothing can
        // ring however far anybody walks, and the watch wakes two hours before it can.
        val evening = Condition.TimeWindow(LocalTime.of(20, 0), LocalTime.of(22, 0))
        val circle = reminder(TriggerRule(home, listOf(evening))).circles().single()
        assertEquals(Fixtures.local(2026, 8, 27, 18, 0), circle.opensAt)
    }

    @Test
    fun `under a la vez a franja gates the place and a moment only asks it`() {
        // Two shapes of "a la vez" and they are not the same question. Beside a WINDOW the
        // place is what rings, so it needs the run-up: a baseline before the hours open, or an
        // arrival at one minute past is a first reading. Beside a MOMENT it rings nothing —
        // the clock does — and all it is ever asked is where the phone is AT the moment.
        val window = TriggerRule(Trigger.Interval(LocalTime.of(19, 0), LocalTime.of(21, 0)))
        val beside = reminder(TriggerRule(home), window, match = RuleMatch.TOGETHER).circles()
        assertEquals(Fixtures.local(2026, 8, 27, 17, 0), beside.single { it.ruleIndex == 0 }.opensAt)
        assertEquals(Crossing.RINGS, beside.single { it.ruleIndex == 0 }.place.crossing)

        val moment = reminder(TriggerRule(home), at(16), match = RuleMatch.TOGETHER).circles()
        val asked = moment.single { it.ruleIndex == 0 }
        assertEquals(Fixtures.local(2026, 8, 27, 15, 55), asked.opensAt, "five minutes, not two hours")
        assertEquals(Crossing.NOTHING, asked.place.crossing, "the clock is what rings there")
    }

    @Test
    fun `under todos a place is never gated by its siblings, only slowed`() {
        // "Cuando salga de la oficina, y el 26 del mes que viene", read as "todos". The set
        // cannot ring for a month — but a place under "todos" is a state, and the leaving that
        // meets it happens when it happens. Switched off until the 26th, that leaving is lost
        // and the set waits for one that will never come again. So: watched, at the cheapest
        // cadence there is.
        val far = reminder(TriggerRule(leavingHome), at(9, day = 28), match = RuleMatch.ALL).circles()
        val circle = far.single { it.ruleIndex == 0 }
        assertNull(circle.opensAt, "a place under todos was switched off")
        assertEquals(PlaceWatchPolicy.MAX_WAIT, circle.place.floor, "and it should be the hourly one")

        // Inside the run-up of the moment that can complete the set, the distance arithmetic
        // has it back.
        val near = reminder(TriggerRule(leavingHome), at(16), match = RuleMatch.ALL).circles()
        assertNull(near.single { it.ruleIndex == 0 }.opensAt)
        assertEquals(Duration.ZERO, near.single { it.ruleIndex == 0 }.place.floor)
    }

    @Test
    fun `under todos a ticked-off place waits for the crossing that takes it back`() {
        val set = reminder(TriggerRule(leavingHome), at(9, day = 28), match = RuleMatch.ALL, fired = setOf(0))
        val circle = set.circles().single { it.ruleIndex == 0 }
        assertNull(circle.opensAt, "a ticked-off place stopped being watched")
        assertEquals(Crossing.TAKES_BACK, circle.place.crossing)
        assertEquals(Transition.ENTER, circle.place.transition, "it is waiting to be walked back into")
        // The id is the circle's, not the crossing's: the memory of which side of the line the
        // phone is on has to survive being met and un-met.
        assertEquals(GeofenceIds.encode("r1", 0, leavingHome), circle.place.id)
    }

    @Test
    fun `under cualquiera nothing is ticked off, so nothing waits to come undone`() {
        val set = reminder(TriggerRule(leavingHome), at(9, day = 28), match = RuleMatch.ANY, fired = setOf(0))
        val circle = set.circles().single { it.ruleIndex == 0 }
        assertEquals(Crossing.RINGS, circle.place.crossing)
        assertEquals(Transition.EXIT, circle.place.transition)
        assertEquals(Duration.ZERO, circle.place.floor, "and no floor: nothing is holding it back")
    }

    @Test
    fun `a resting reminder watches nothing and keeps its memory`() {
        // Dealt with an hour ago and coming back tomorrow: the rules say nothing until the rest
        // is up, but which side of the line the phone is on is what the next crossing is judged
        // against — and what a place that has already rung is owed a leaving by.
        val resting = reminder(
            TriggerRule(home),
            recurrence = Recurrence.After(1, RecurrenceUnit.DAYS),
            dealt = now.minusSeconds(3_600),
        )
        val circle = resting.circles().single()
        assertNotNull(circle.opensAt, "a resting circle was watched")
        assertTrue(circle.resting, "and its memory would have been dropped")
    }

    @Test
    fun `a circle only asked about is watched five minutes before it is asked`() {
        val asking = TriggerRule(
            Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 0)),
            listOf(Condition.AtPlace(homeLat, homeLng, 200, "Casa", inside = true)),
        )
        val circle = reminder(asking).circles().single()
        assertEquals(Fixtures.local(2026, 8, 27, 20, 55), circle.opensAt)
        assertEquals(Crossing.NOTHING, circle.place.crossing)
        assertEquals(Transition.ENTER, circle.place.transition, "waiting to be there reads as an arrival")
    }

    @Test
    fun `what a card asks is whether the circle is costing anything`() {
        val evening = Condition.TimeWindow(LocalTime.of(20, 0), LocalTime.of(22, 0))
        assertTrue(reminder(TriggerRule(home)).circles().watchingRule(0))
        assertTrue(!reminder(TriggerRule(home, listOf(evening))).circles().watchingRule(0), "shut is not watched")
        // A circle that is only ever asked about is nobody's idea of a watched rule either: it
        // rings nothing, and the mark on that row is the clock's.
        val asked = TriggerRule(
            Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 15, 3)),
            listOf(Condition.AtPlace(homeLat, homeLng, 200, "Casa")),
        )
        assertTrue(!reminder(asked).circles().watchingRule(0))
    }

    @Test
    fun `a finished reminder is watched by nobody`() {
        assertTrue(reminder(TriggerRule(home)).copy(status = Status.DONE).circles().isEmpty())
    }
}
