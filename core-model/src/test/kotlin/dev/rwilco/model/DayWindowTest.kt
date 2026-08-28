package dev.rwilco.model

import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * A stretch of the day, named or not: the third answer to "when in the day", between an hour
 * somebody picked and the whole of the day they are up for.
 */
class DayWindowTest {

    private val friday = LocalDate.of(2026, 8, 28)
    private val lunch = DayWindow(LocalTime.of(14, 0), LocalTime.of(16, 0))
    private val night = DayWindow(LocalTime.of(22, 0), LocalTime.of(1, 30))

    @Test
    fun `a window laid on a day is that day, and an end before its start is the next morning`() {
        assertEquals(friday.atTime(14, 0), lunch.on(friday).from)
        assertEquals(friday.atTime(16, 0), lunch.on(friday).to)
        // The same rule a bedtime past midnight gets: "de 22:00 a 01:30" is one stretch.
        assertEquals(friday.atTime(22, 0), night.on(friday).from)
        assertEquals(friday.plusDays(1).atTime(1, 30), night.on(friday).to)
    }

    @Test
    fun `a repeat draws its moment inside the window it was given`() {
        val repeat = Trigger.Repeat(startsOn = friday, unit = RepeatUnit.DAY, window = lunch)
        // Enough days to be sure it is the window doing the work and not one lucky draw.
        for (day in 0L until 30L) {
            val date = friday.plusDays(day)
            val at = repeat.momentOn(date, "r1", zone, DayShape.DEFAULT).atZone(zone).toLocalDateTime()
            assertTrue(at >= date.atTime(14, 0) && at <= date.atTime(16, 0), "drew $at on $date")
        }
    }

    @Test
    fun `an hour outranks a window, and no window is the day this person is up for`() {
        val date = friday
        val withHour = Trigger.Repeat(startsOn = date, unit = RepeatUnit.DAY, time = LocalTime.of(9, 0), window = lunch)
        assertEquals(date.atTime(9, 0).atZone(zone).toInstant(), withHour.momentOn(date, "r1", zone, DayShape.DEFAULT))

        val bare = Trigger.Repeat(startsOn = date, unit = RepeatUnit.DAY)
        val awake = bare.momentOn(date, "r1", zone, DayShape.DEFAULT).atZone(zone).toLocalDateTime()
        val hours = DayShape.DEFAULT.awakeOn(date)
        assertTrue(awake >= hours.from && awake <= hours.to, "drew $awake outside $hours")
        // And the two draws are not the same moment, or the window would be doing nothing.
        assertNotEquals(bare.momentOn(date, "r1", zone, DayShape.DEFAULT), bare.copy(window = lunch).momentOn(date, "r1", zone, DayShape.DEFAULT))
    }

    @Test
    fun `a one-off date draws from its window too`() {
        val at = nextFireOf(
            Trigger.DayRandom(friday, lunch),
            "r1",
            friday.atStartOfDay(zone).toInstant(),
            zone,
            Fixtures.defaultTime,
        )
        val moment = (at as NextFire.Scheduled).at.atZone(zone).toLocalDateTime()
        assertTrue(moment >= friday.atTime(14, 0) && moment <= friday.atTime(16, 0), "drew $moment")
    }

    @Test
    fun `the draw holds still while the day does`() {
        // Nothing is stored, so the same question has to give the same answer every time it is
        // asked — that is what lets the scheduler and the screen agree without talking.
        val repeat = Trigger.Repeat(startsOn = friday, unit = RepeatUnit.DAY, window = lunch)
        val once = repeat.momentOn(friday, "r1", zone, DayShape.DEFAULT)
        repeat { assertEquals(once, repeat.momentOn(friday, "r1", zone, DayShape.DEFAULT)) }
        // And a different reminder gets a different minute out of the same window.
        assertNotEquals(once, repeat.momentOn(friday, "r2", zone, DayShape.DEFAULT))
    }

    @Test
    fun `alone a window is a draw, in a set it is a gate`() {
        // The whole of what a window means depends on whether anything else is waiting on it.
        // On its own, "el viernes a la hora de comer" is a minute nobody chose between two and
        // four. In a set it cannot be: a draw that lands while the other half is false is a
        // reminder that silently does not ring, so the window becomes a door that is open from
        // two, and the ring lands the moment everything else is true inside it.
        val windowed = TriggerRule(Trigger.DayRandom(friday, lunch))
        val place = TriggerRule(Trigger.Location(40.4, -3.7, 150, Presence.INSIDE, "Casa"))

        val alone = Reminder(id = "r", text = "x", rules = listOf(windowed), createdAt = Fixtures.now, updatedAt = Fixtures.now)
        assertEquals(windowed, alone.ruleInSet(0), "nothing else waits on it, so nothing changes")

        for (match in listOf(RuleMatch.ALL, RuleMatch.TOGETHER)) {
            val combined = alone.copy(rules = listOf(windowed, place), ruleMatch = match)
            val rule = combined.ruleInSet(0)!!
            assertEquals(
                Trigger.AtDateTime(friday.atTime(14, 0)),
                rule.trigger,
                "$match: the moment is the door opening, which can be its first second",
            )
        }
        // And under "cualquiera" nobody depends on anybody, so the draw stands.
        val either = alone.copy(rules = listOf(windowed, place), ruleMatch = RuleMatch.ANY)
        assertEquals(windowed, either.ruleInSet(0))
    }

    @Test
    fun `in a set the window becomes a condition on every sibling`() {
        val windowed = TriggerRule(Trigger.DayRandom(friday, lunch))
        val place = TriggerRule(Trigger.Location(40.4, -3.7, 150, Presence.INSIDE, "Casa"))
        val together = Reminder(
            id = "r",
            text = "x",
            rules = listOf(windowed, place),
            ruleMatch = RuleMatch.TOGETHER,
            createdAt = Fixtures.now,
            updatedAt = Fixtures.now,
        )
        // The place's rule now carries "and only between two and four" — which is what makes an
        // arrival at 15:10 ring and one at 17:00 not.
        assertTrue(
            together.ruleInSet(1)!!.conditions.contains(Condition.TimeWindow(LocalTime.of(14, 0), LocalTime.of(16, 0))),
            "the window is folded into the place as a state",
        )
        // Two states can coincide, so the set is not the impossible kind.
        assertTrue(!together.momentsCannotCoincide(), "a window and a place can both be true at once")
    }

    @Test
    fun `a window in a set stops being one of the moments that cannot coincide`() {
        // Two instants are never the same instant, and "a la vez" says so before anybody waits
        // a week to find out. A window is not an instant — it is open for two hours — so a date
        // with one on it can share a moment with an appointment, and must not be warned about.
        val windowed = TriggerRule(Trigger.DayRandom(friday, lunch))
        val appointment = TriggerRule(Trigger.AtDateTime(friday.atTime(15, 0)))
        val said = warnings(
            listOf(windowed, appointment),
            Fixtures.now,
            zone,
            Fixtures.defaultTime,
            match = RuleMatch.TOGETHER,
        )
        assertTrue(
            said.none { it is ValidationWarning.MomentsCannotCoincide },
            "a window and a moment can coincide: $said",
        )
        // Without the window it is two instants, and that is exactly what the warning is for.
        val bare = warnings(
            listOf(TriggerRule(Trigger.DayRandom(friday)), appointment),
            Fixtures.now,
            zone,
            Fixtures.defaultTime,
            match = RuleMatch.TOGETHER,
        )
        assertTrue(bare.any { it is ValidationWarning.MomentsCannotCoincide }, "two instants still cannot: $bare")
    }

    @Test
    fun `a saved window is a name over the same two times`() {
        val saved = SavedWindow("a la hora de comer", LocalTime.of(14, 0), LocalTime.of(16, 0))
        assertEquals(lunch, saved.window)
    }
}

private fun repeat(times: Int = 5, block: () -> Unit) = List(times) { block() }
