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
    fun `a repeat opens inside the window it was given`() {
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
    fun `a one-off date opens with its window too`() {
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
    fun `a stretch opens where it opens, for everybody and every time it is asked`() {
        // Nothing is stored, so the same question has to give the same answer every time it is
        // asked — that is what lets the scheduler and the screen agree without talking. It used
        // to be a draw seeded by (reminder, day), which agreed for one reminder and gave the
        // next one a different minute. It is the opening now, so two reminders on the same
        // stretch ring together, which is what "a la hora de comer" says on the face of it.
        val repeat = Trigger.Repeat(startsOn = friday, unit = RepeatUnit.DAY, window = lunch)
        val once = repeat.momentOn(friday, "r1", zone, DayShape.DEFAULT)
        assertEquals(friday.atTime(14, 0).atZone(zone).toInstant(), once)
        repeat { assertEquals(once, repeat.momentOn(friday, "r1", zone, DayShape.DEFAULT)) }
        assertEquals(once, repeat.momentOn(friday, "r2", zone, DayShape.DEFAULT), "the stretch is nobody's private lottery")
    }

    @Test
    fun `a window means one thing, alone and in company`() {
        // It used to mean two. On its own, "el viernes a la hora de comer" was a minute nobody
        // chose between two and four; in a set it could not be — a draw landing while the other
        // half is false is a reminder that silently does not ring — so a set rewrote it into the
        // door opening at two. Two readings of one control, and which one you got depended on
        // what else was on the card. The door is the only reading now, so no set has to rewrite
        // anything and every rule comes back exactly as it was written.
        val windowed = TriggerRule(Trigger.DayRandom(friday, lunch))
        val place = TriggerRule(Trigger.Location(40.4, -3.7, 150, Presence.INSIDE, "Casa"))
        val start = friday.atStartOfDay(zone).toInstant()
        val opening = friday.atTime(14, 0).atZone(zone).toInstant()

        val alone = Reminder(id = "r", text = "x", rules = listOf(windowed), createdAt = start, updatedAt = start)
        assertEquals(windowed, alone.ruleInSet(0))
        assertEquals(opening, (nextFireOf(windowed.trigger, "r", start, zone, Fixtures.defaultTime) as NextFire.Scheduled).at)

        for (match in listOf(RuleMatch.ALL, RuleMatch.TOGETHER, RuleMatch.ANY)) {
            val combined = alone.copy(rules = listOf(windowed, place), ruleMatch = match)
            val rule = combined.ruleInSet(0)!!
            assertEquals(windowed.trigger, rule.trigger, "$match: the trigger is untouched")
            assertEquals(
                opening,
                (nextFireOfRule(rule, "r", start, zone, Fixtures.defaultTime) as NextFire.Scheduled).at,
                "$match: and it rings when the door opens",
            )
        }
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
            together.ruleInSet(1)!!.conditions.contains(Condition.TimeWindow(LocalTime.of(14, 0), LocalTime.of(16, 0), date = friday)),
            "the window is folded into the place as a state, on the day it is about",
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
    fun `a bare day is the stretch this person is up for`() {
        // "El jueves a cualquier hora" was one minute of Thursday, drawn from the whole day, and
        // "y a la vez en la oficina" only rang if the phone happened to be inside the circle at
        // exactly it. A day with no window is a window all the same — the hours this person is
        // up — and it opens when they do, alone or in company, a state to its siblings either way.
        val bare = TriggerRule(Trigger.DayRandom(friday))
        val place = TriggerRule(Trigger.Location(40.4, -3.7, 150, Presence.INSIDE, "Oficina"))
        val start = friday.atStartOfDay(zone).toInstant()
        val alone = Reminder(id = "r", text = "x", rules = listOf(bare), createdAt = start, updatedAt = start)
        assertEquals(bare, alone.ruleInSet(0))
        // Somebody who gets up at ten and turns in at eleven, week and weekend alike: the day
        // opens at ten, not at the default eight. (A Friday goes to bed at the weekend's hour.)
        val lieIn = DayShape(hours = AwakeHours(LocalTime.of(10, 0), LocalTime.of(23, 0), LocalTime.of(10, 0), LocalTime.of(23, 0)))
        for (match in listOf(RuleMatch.ALL, RuleMatch.TOGETHER, RuleMatch.ANY)) {
            val combined = alone.copy(rules = listOf(bare, place), ruleMatch = match)
            val rule = combined.ruleInSet(0)!!
            assertEquals(bare.trigger, rule.trigger, "$match: the trigger is untouched")
            assertEquals(
                friday.atTime(8, 0).atZone(zone).toInstant(),
                (nextFireOfRule(rule, "r", start, zone, Fixtures.defaultTime) as NextFire.Scheduled).at,
                "$match: opens when the day does",
            )
            assertEquals(
                friday.atTime(10, 0).atZone(zone).toInstant(),
                (nextFireOfRule(combined.ruleInSet(0, lieIn)!!, "r", start, zone, Fixtures.defaultTime, lieIn) as NextFire.Scheduled).at,
                "$match: this person's own hours",
            )
        }
        val together = alone.copy(rules = listOf(bare, place), ruleMatch = RuleMatch.TOGETHER)
        // Friday under the default shape runs to the weekend's bedtime, half past one.
        assertTrue(
            together.ruleInSet(1)!!.conditions.contains(Condition.TimeWindow(LocalTime.of(8, 0), LocalTime.of(1, 30), date = friday)),
            "the waking hours are folded into the place as a state: ${together.ruleInSet(1)!!.conditions}",
        )
        assertTrue(
            together.ruleInSet(1, lieIn)!!.conditions.contains(Condition.TimeWindow(LocalTime.of(10, 0), LocalTime.of(23, 0), date = friday)),
        )
        assertTrue(!together.momentsCannotCoincide(), "a day and a place can both be true at once")
        assertTrue(!Trigger.DayRandom(friday).isMoment, "a day is a stretch, not an instant")
    }

    @Test
    fun `the opening is the first minute the day's own fences allow`() {
        // "El viernes a cualquier hora, y sólo si es entre las 16 y las 17". The door used to
        // open at eight — the start of the day — which the rule's own fence then rejected, so
        // the rule answered never. It opens at the first minute the fence actually allows, on
        // its own and in any set.
        val teatime = Condition.TimeWindow(LocalTime.of(16, 0), LocalTime.of(17, 0))
        val fenced = TriggerRule(Trigger.DayRandom(friday), listOf(teatime))
        val place = TriggerRule(Trigger.Location(40.4, -3.7, 150, Presence.INSIDE, "Oficina"))
        val start = friday.atStartOfDay(zone).toInstant()
        val lone = Reminder(id = "r", text = "x", rules = listOf(fenced), createdAt = start, updatedAt = start)
        assertEquals(
            friday.atTime(16, 0).atZone(zone).toInstant(),
            (nextFireOfRule(lone.ruleInSet(0)!!, "r", start, zone, Fixtures.defaultTime) as NextFire.Scheduled).at,
            "on its own",
        )
        for (match in listOf(RuleMatch.ALL, RuleMatch.TOGETHER)) {
            val set = Reminder(id = "r", text = "x", rules = listOf(fenced, place), ruleMatch = match, createdAt = start, updatedAt = start)
            val next = nextFireOfRule(set.ruleInSet(0)!!, "r", start, zone, Fixtures.defaultTime) as NextFire.Scheduled
            assertEquals(friday.atTime(16, 0).atZone(zone).toInstant(), next.at, "$match: armed at the opening")
            assertTrue(
                warnings(listOf(fenced, place), start, zone, Fixtures.defaultTime, match).none { it is ValidationWarning.NeverFires || it is ValidationWarning.NeverCompletes },
                "$match: nothing to warn about",
            )
        }
        // The same with a window of its own: the fence narrows it from within.
        val lunchFenced = TriggerRule(Trigger.DayRandom(friday, lunch), listOf(Condition.TimeWindow(LocalTime.of(15, 0), LocalTime.of(18, 0))))
        val windowed = Reminder(id = "r", text = "x", rules = listOf(lunchFenced, place), ruleMatch = RuleMatch.ALL, createdAt = start, updatedAt = start)
        assertEquals(
            friday.atTime(15, 0).atZone(zone).toInstant(),
            (nextFireOfRule(windowed.ruleInSet(0)!!, "r", start, zone, Fixtures.defaultTime) as NextFire.Scheduled).at,
        )
        // A fence on another day allows no minute of this one: the door opens where it always
        // did, the walk says never, and the editor says so out loud.
        val mondays = TriggerRule(Trigger.DayRandom(friday), listOf(Condition.TimeWindow(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, setOf(DayOfWeek.MONDAY))))
        val doomed = Reminder(id = "r", text = "x", rules = listOf(mondays, place), ruleMatch = RuleMatch.ALL, createdAt = start, updatedAt = start)
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
