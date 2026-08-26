package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * "A la vez": every rule true at the same moment, and the time range that makes it worth
 * writing. What these pin is that the reading is a conjunction of *states* — so a set with two
 * instants in it says so instead of waiting for a coincidence that never comes.
 */
class TogetherTest {

    private val homeLat = 40.4169
    private val homeLng = -3.7035
    private val office = Trigger.Location(homeLat + 0.02, homeLng, 150, Transition.ENTER, "Oficina")
    private val home = Trigger.Location(homeLat, homeLng, 200, Transition.ENTER, "Casa")
    private val fiveToSeven = Trigger.Interval(LocalTime.of(17, 0), LocalTime.of(19, 0))
    private val nineAm = Trigger.AtTime(LocalTime.of(9, 0), DayOfWeek.entries.toSet())
    private val tenAm = Trigger.AtTime(LocalTime.of(10, 0), DayOfWeek.entries.toSet())

    private fun reminder(match: RuleMatch, vararg triggers: Trigger) = Reminder(
        id = "r1",
        text = "Preguntar por el pedido",
        rules = triggers.map { TriggerRule(it) },
        ruleMatch = match,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `the alarm is set from the folded rule, so a moment outside a sibling's window is never armed`() {
        // "A las nueve, y de ocho a diez los lunes": Mondays at nine. The firing judges the
        // rule with the window folded in; the scheduler has to offer the same moment, or it
        // arms tomorrow's nine o'clock on a Tuesday and the alarm rings for a set that does
        // not hold.
        val mondays = Trigger.Interval(LocalTime.of(8, 0), LocalTime.of(10, 0), setOf(DayOfWeek.MONDAY))
        val set = reminder(RuleMatch.TOGETHER, nineAm, mondays)
        val wake = nextWake(set, now, zone, defaultTime)
        assertNotNull(wake)
        val at = wake!!.at.atZone(zone)
        assertEquals(DayOfWeek.MONDAY, at.dayOfWeek, "nine o'clock on a day the window is shut is not the set")
        assertEquals(LocalTime.of(9, 0), at.toLocalTime())
        assertEquals(0, wake.ruleIndex, "the clock's rule is the one that rings it")
        assertEquals(wake.at, (nextFire(set, now, zone, defaultTime) as NextFire.Scheduled).at, "and Home says the same")
        // Two moments folded together can never both be true, so nothing is armed at all.
        assertNull(nextWake(reminder(RuleMatch.TOGETHER, nineAm, tenAm), now, zone, defaultTime))
    }

    @Test
    fun `a time range rings when it opens, every day unless days are given`() {
        val next = nextFireOf(fiveToSeven, "r", now, zone, defaultTime)
        assertNotNull(next)
        val at = (next as NextFire.Scheduled).at.atZone(zone)
        assertEquals(LocalTime.of(17, 0), at.toLocalTime(), "a range starts when it starts")
        // Days on it narrow it exactly as a repeating time's do.
        val mondays = Trigger.Interval(LocalTime.of(17, 0), LocalTime.of(19, 0), setOf(DayOfWeek.MONDAY))
        val monday = nextFireOf(mondays, "r", now, zone, defaultTime) as NextFire.Scheduled
        assertEquals(DayOfWeek.MONDAY, monday.at.atZone(zone).dayOfWeek)
        // And a range of nothing is not a range.
        assertEquals(TriggerProblem.WINDOW_EMPTY, problemOf(Trigger.Interval(LocalTime.NOON, LocalTime.NOON)))
        assertNull(problemOf(fiveToSeven))
    }

    @Test
    fun `states are the ones that can be true alongside something else`() {
        assertEquals(Condition.TimeWindow(LocalTime.of(17, 0), LocalTime.of(19, 0)), fiveToSeven.asState())
        assertEquals(Condition.AtPlace(homeLat, homeLng, 200, "Casa", inside = true), home.asState())
        assertEquals(false, (home.copy(transition = Transition.EXIT).asState() as Condition.AtPlace).inside)
        for (moment in listOf(nineAm, Trigger.OnDate(java.time.LocalDate.of(2026, 9, 1)), Trigger.Countdown(30))) {
            assertNull(moment.asState(), "$moment is only ever true at an instant")
            assertTrue(moment.isMoment)
        }
        assertFalse(home.isMoment)
        assertFalse(fiveToSeven.isMoment)
    }

    @Test
    fun `at once folds every other rule into the one that happened`() {
        val both = reminder(RuleMatch.TOGETHER, office, fiveToSeven)
        // Arriving at the office is judged with "and only between five and seven" on it...
        val onArrival = both.togetherRule(0)!!
        assertEquals(office, onArrival.trigger)
        assertEquals(listOf(fiveToSeven.asState()), onArrival.conditions)
        // ...and five o'clock is judged with "and only if I am at the office".
        val atFive = both.togetherRule(1)!!
        assertEquals(fiveToSeven, atFive.trigger)
        assertEquals(listOf(office.asState()), atFive.conditions)
    }

    @Test
    fun `a place with hours beside it is the answer on Home, not the hours' opening`() {
        val both = reminder(RuleMatch.TOGETHER, office, fiveToSeven)
        // "Al llegar a la oficina, de cinco a siete": what rings is the arrival.
        assertEquals(NextFire.WhenAt(office), nextFire(both, now, zone, defaultTime))
        // Five o'clock came and went with nobody at the office: still the arrival — not, as it
        // once read, a countdown to tomorrow's five.
        assertEquals(NextFire.WhenAt(office), nextFire(both, now.plusSeconds(3 * 3600), zone, defaultTime))
        // The opening is armed all the same, for a morning somebody is already there.
        assertEquals(local(2026, 8, 27, 17, 0), nextWake(both, now, zone, defaultTime)!!.at)
        // Under "cualquiera" the window opens whether or not anybody is there, so its moment stands.
        assertEquals(local(2026, 8, 27, 17, 0), (nextFire(reminder(RuleMatch.ANY, office, fiveToSeven), now, zone, defaultTime) as NextFire.Scheduled).at)
        // A moment beside a place is a real moment: nine, if at the office, is nine.
        assertTrue(nextFire(reminder(RuleMatch.TOGETHER, office, nineAm), now, zone, defaultTime) is NextFire.Scheduled)
    }

    @Test
    fun `two instants asked to coincide never ring, and say so`() {
        val impossible = reminder(RuleMatch.TOGETHER, nineAm, tenAm)
        assertTrue(impossible.momentsCannotCoincide())
        // Neither rule can be judged at all: the set cannot hold, so nothing rings.
        assertNull(impossible.togetherRule(0))
        assertNull(impossible.togetherRule(1))
        val said = warnings(impossible.rules, now, zone, defaultTime, RuleMatch.TOGETHER)
        assertTrue(ValidationWarning.MomentsCannotCoincide(0) in said)
        assertTrue(ValidationWarning.MomentsCannotCoincide(1) in said)
        // A place is NOT one of them, and that is the whole point of reading rules as states:
        // "en casa Y a las nueve" is not two instants asked to coincide, it is "estar en casa a
        // las nueve", which rings at nine if you are. The crossing is what wakes the app; being
        // there is what is true.
        val atHomeAtNine = reminder(RuleMatch.TOGETHER, home, nineAm)
        assertFalse(atHomeAtNine.momentsCannotCoincide())
        assertEquals(listOf(home.asState()), atHomeAtNine.togetherRule(1)!!.conditions)
        // And the other way round it cannot ring: arriving home at some other hour is not nine
        // o'clock, so the rule that rings this set is the clock's.
        assertNull(atHomeAtNine.togetherRule(0))
    }

    @Test
    fun `one moment among states is exactly what at once is for`() {
        val fine = reminder(RuleMatch.TOGETHER, nineAm, home.copy(transition = Transition.EXIT))
        assertFalse(fine.momentsCannotCoincide())
        assertNotNull(fine.togetherRule(0))
        assertTrue(warnings(fine.rules, now, zone, defaultTime, RuleMatch.TOGETHER).isEmpty())
        // Two places is fine as long as they overlap: nothing here is a moment but the crossings.
        val overlapping = reminder(RuleMatch.TOGETHER, home, home.copy(radiusM = 1_000, label = "Barrio"))
        assertTrue(warnings(overlapping.rules, now, zone, defaultTime, RuleMatch.TOGETHER).isEmpty())
    }

    @Test
    fun `the impossibilities that were already detectable are found through the fold`() {
        // Two circles that do not touch, as two triggers rather than a trigger and a condition.
        val apart = reminder(RuleMatch.TOGETHER, home, office)
        val said = warnings(apart.rules, now, zone, defaultTime, RuleMatch.TOGETHER)
        assertTrue(said.any { it is ValidationWarning.PlacesConflict })
        // A moment that falls outside the only range it is asked to be inside.
        val never = reminder(RuleMatch.TOGETHER, nineAm, fiveToSeven)
        assertTrue(warnings(never.rules, now, zone, defaultTime, RuleMatch.TOGETHER).any { it is ValidationWarning.NeverFires })
        // The same pair with a range that contains nine says nothing.
        val works = reminder(RuleMatch.TOGETHER, nineAm, Trigger.Interval(LocalTime.of(8, 0), LocalTime.of(10, 0)))
        assertTrue(warnings(works.rules, now, zone, defaultTime, RuleMatch.TOGETHER).isEmpty())
    }

    @Test
    fun `nothing accumulates at once, and every rule stays worth watching`() {
        val both = reminder(RuleMatch.TOGETHER, office, fiveToSeven)
        assertEquals(FiringOutcome.Ring, outcomeOfFiring(both, 0), "a set judged whole has nothing left to wait for")
        assertEquals(listOf(0, 1), both.pendingRules())
        // Where ALL ticks its rules off one by one and stops watching them.
        val accumulating = reminder(RuleMatch.ALL, office, fiveToSeven).copy(firedRules = setOf(0))
        assertEquals(FiringOutcome.Ring, outcomeOfFiring(accumulating, 1))
        assertEquals(listOf(1), accumulating.pendingRules())
    }

    @Test
    fun `a circle is left alone until the hours it needs can hold`() {
        val threeAm = Fixtures.local(2026, 8, 27, 3, 0)
        val sixPm = Fixtures.local(2026, 8, 27, 18, 0)
        val office = reminder(RuleMatch.TOGETHER, this.office, fiveToSeven)
        val windows = office.togetherRule(0)!!.windows()
        assertEquals(listOf(fiveToSeven.asState()), windows)
        // At three in the morning the set cannot ring however far anybody walks, so the answer
        // is not "watch" but "wake me at five".
        val opens = windows.openFrom(threeAm, zone)
        assertEquals(Fixtures.local(2026, 8, 27, 17, 0), opens)
        // Inside the window the gate is simply open, now.
        assertEquals(sixPm, windows.openFrom(sixPm, zone))
        // And a rule with no windows at all is never gated: there is nothing to wait for.
        assertEquals(threeAm, emptyList<Condition.TimeWindow>().openFrom(threeAm, zone))
    }

    @Test
    fun `two windows open where the later of them does, and never when they do not meet`() {
        val threeAm = Fixtures.local(2026, 8, 27, 3, 0)
        val evening = Condition.TimeWindow(LocalTime.of(17, 0), LocalTime.of(19, 0))
        val overlapping = Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(20, 0))
        assertEquals(
            Fixtures.local(2026, 8, 27, 18, 0),
            listOf(evening, overlapping).openFrom(threeAm, zone),
            "the conjunction begins where the later one does",
        )
        val morning = Condition.TimeWindow(LocalTime.of(9, 0), LocalTime.of(11, 0))
        assertNull(listOf(evening, morning).openFrom(threeAm, zone), "a circle to leave alone entirely")
    }

    @Test
    fun `a place whose crossing can never complete the set is still worth knowing about`() {
        // "En casa Y a las nueve": the crossing cannot ring (nine o'clock is not now), but the
        // nine o'clock rule is going to ask where the phone is, so it has to have been watched.
        val atHomeAtNine = reminder(RuleMatch.TOGETHER, home, nineAm)
        assertNull(atHomeAtNine.togetherRule(0), "arriving home at some other hour rings nothing")
        assertEquals(listOf(home.asState()), atHomeAtNine.togetherRule(1)!!.conditions)
    }

    @Test
    fun `a time range survives a round trip`() {
        val rules = listOf(TriggerRule(fiveToSeven), TriggerRule(office))
        assertEquals(rules, ReminderCodec.decodeRules(ReminderCodec.encodeRules(rules)))
    }
}
