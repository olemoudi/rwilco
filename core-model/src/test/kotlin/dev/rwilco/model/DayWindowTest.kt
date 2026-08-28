package dev.rwilco.model

import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
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
        // Two instants — a length that runs out and an appointment — and that is exactly what
        // the warning is for. (A day with no window is not one of them any more: it is the
        // stretch this person is up for, see below.)
        val bare = warnings(
            listOf(TriggerRule(Trigger.Countdown(30, Fixtures.now)), appointment),
            Fixtures.now,
            zone,
            Fixtures.defaultTime,
            match = RuleMatch.TOGETHER,
        )
        assertTrue(bare.any { it is ValidationWarning.MomentsCannotCoincide }, "two instants still cannot: $bare")
    }

    @Test
    fun `a bare day in a set is the gate over its waking hours`() {
        // "El jueves a cualquier hora, y a la vez en la oficina" was one minute of Thursday,
        // drawn from the whole day and rung only if the phone happened to be inside the circle
        // at it. A day with no window is a window all the same — the hours this person is up —
        // and in a set it gates like any other: open from getting up, a state to its siblings.
        val bare = TriggerRule(Trigger.DayRandom(friday))
        val place = TriggerRule(Trigger.Location(40.4, -3.7, 150, Presence.INSIDE, "Oficina"))
        val alone = Reminder(id = "r", text = "x", rules = listOf(bare), createdAt = Fixtures.now, updatedAt = Fixtures.now)
        assertEquals(bare, alone.ruleInSet(0), "on its own the day is still a draw")
        // Somebody who gets up at ten and turns in at eleven, week and weekend alike: the gate
        // opens at ten, not at the default eight. (A Friday goes to bed at the weekend's hour.)
        val lieIn = DayShape(hours = AwakeHours(LocalTime.of(10, 0), LocalTime.of(23, 0), LocalTime.of(10, 0), LocalTime.of(23, 0)))
        for (match in listOf(RuleMatch.ALL, RuleMatch.TOGETHER)) {
            val combined = alone.copy(rules = listOf(bare, place), ruleMatch = match)
            assertEquals(Trigger.AtDateTime(friday.atTime(8, 0)), combined.ruleInSet(0)!!.trigger, "$match: opens when the day does")
            assertEquals(Trigger.AtDateTime(friday.atTime(10, 0)), combined.ruleInSet(0, lieIn)!!.trigger, "$match: this person's own hours")
        }
        val together = alone.copy(rules = listOf(bare, place), ruleMatch = RuleMatch.TOGETHER)
        // Friday under the default shape runs to the weekend's bedtime, half past one.
        assertTrue(
            together.ruleInSet(1)!!.conditions.contains(Condition.TimeWindow(LocalTime.of(8, 0), LocalTime.of(1, 30))),
            "the waking hours are folded into the place as a state: ${together.ruleInSet(1)!!.conditions}",
        )
        assertTrue(
            together.ruleInSet(1, lieIn)!!.conditions.contains(Condition.TimeWindow(LocalTime.of(10, 0), LocalTime.of(23, 0))),
        )
        assertTrue(!together.momentsCannotCoincide(), "a day and a place can both be true at once")
        assertTrue(!Trigger.DayRandom(friday).isMoment, "a day is a stretch, not an instant")
    }

    @Test
    fun `the gate opens where the day's own fences allow`() {
        // "El viernes a cualquier hora, y sólo si es entre las 16 y las 17", in a set. The door
        // used to open at eight — the start of the day — which the rule's own fence then
        // rejected, so the rule answered never and the set never completed. The door opens at
        // the first minute the fence allows.
        val teatime = Condition.TimeWindow(LocalTime.of(16, 0), LocalTime.of(17, 0))
        val fenced = TriggerRule(Trigger.DayRandom(friday), listOf(teatime))
        val place = TriggerRule(Trigger.Location(40.4, -3.7, 150, Presence.INSIDE, "Oficina"))
        val start = friday.atStartOfDay(zone).toInstant()
        for (match in listOf(RuleMatch.ALL, RuleMatch.TOGETHER)) {
            val set = Reminder(id = "r", text = "x", rules = listOf(fenced, place), ruleMatch = match, createdAt = start, updatedAt = start)
            val rule = set.ruleInSet(0)!!
            assertEquals(Trigger.AtDateTime(friday.atTime(16, 0)), rule.trigger, "$match")
            val next = nextFireOfRule(rule, "r", start, zone, Fixtures.defaultTime) as NextFire.Scheduled
            assertEquals(friday.atTime(16, 0).atZone(zone).toInstant(), next.at, "$match: armed at the opening")
            assertTrue(
                warnings(listOf(fenced, place), start, zone, Fixtures.defaultTime, match).none { it is ValidationWarning.NeverFires || it is ValidationWarning.NeverCompletes },
                "$match: nothing to warn about",
            )
        }
        // The same with a window of its own: the fence narrows it from within.
        val lunchFenced = TriggerRule(Trigger.DayRandom(friday, lunch), listOf(Condition.TimeWindow(LocalTime.of(15, 0), LocalTime.of(18, 0))))
        val windowed = Reminder(id = "r", text = "x", rules = listOf(lunchFenced, place), ruleMatch = RuleMatch.ALL, createdAt = start, updatedAt = start)
        assertEquals(Trigger.AtDateTime(friday.atTime(15, 0)), windowed.ruleInSet(0)!!.trigger)
        // A fence on another day allows no minute of this one: the door opens where it always
        // did, the walk says never, and the editor says so out loud.
        val mondays = TriggerRule(Trigger.DayRandom(friday), listOf(Condition.TimeWindow(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, setOf(DayOfWeek.MONDAY))))
        val doomed = Reminder(id = "r", text = "x", rules = listOf(mondays, place), ruleMatch = RuleMatch.ALL, createdAt = start, updatedAt = start)
        assertEquals(Trigger.AtDateTime(friday.atTime(8, 0)), doomed.ruleInSet(0)!!.trigger)
        assertNull(nextFireOfRule(doomed.ruleInSet(0)!!, "r", start, zone, Fixtures.defaultTime))
        assertTrue(warnings(listOf(mondays, place), start, zone, Fixtures.defaultTime, RuleMatch.ALL).any { it is ValidationWarning.NeverCompletes })
    }

    @Test
    fun `a saved window is a name over the same two times`() {
        val saved = SavedWindow("a la hora de comer", LocalTime.of(14, 0), LocalTime.of(16, 0))
        assertEquals(lunch, saved.window)
    }
}

private fun repeat(times: Int = 5, block: () -> Unit) = List(times) { block() }
