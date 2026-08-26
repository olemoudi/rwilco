package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * The marks on a card's rules. Fixtures.now is a Thursday at 15:00 in Madrid, which is what the
 * windows below are chosen around.
 */
class RuleStandingTest {

    private val office = Trigger.Location(40.4369, -3.7035, 150, Transition.ENTER, "Oficina")
    private val leavingOffice = office.copy(transition = Transition.EXIT)
    private val afternoon = Trigger.Interval(LocalTime.of(14, 0), LocalTime.of(18, 0))
    private val evening = Trigger.Interval(LocalTime.of(20, 0), LocalTime.of(23, 0))
    private val nineAm = Trigger.AtTime(LocalTime.of(9, 0), DayOfWeek.entries.toSet())

    private fun reminder(match: RuleMatch, vararg triggers: Trigger, fired: Set<Int> = emptySet()) = Reminder(
        id = "r1",
        text = "Preguntar por el pedido",
        rules = triggers.map { TriggerRule(it) },
        ruleMatch = match,
        firedRules = fired,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `one rule has no standing, and neither has either-of-them`() {
        assertEquals(listOf(null), reminder(RuleMatch.ALL, nineAm).ruleStandings(now, zone))
        assertEquals(listOf(null, null), reminder(RuleMatch.ANY, nineAm, office).ruleStandings(now, zone))
    }

    @Test
    fun `under all of them, what has happened is ticked off and the rest are waiting`() {
        val set = reminder(RuleMatch.ALL, nineAm, office, fired = setOf(0))
        assertEquals(listOf(RuleStanding.DONE, RuleStanding.PENDING), set.ruleStandings(now, zone))
        // Dealing with the firing starts the round again, and the mark goes back.
        assertEquals(
            listOf(RuleStanding.PENDING, RuleStanding.PENDING),
            set.copy(firedRules = emptySet()).ruleStandings(now, zone),
        )
    }

    @Test
    fun `under a la vez, a window says whether it is true right now`() {
        val set = reminder(RuleMatch.TOGETHER, office, afternoon)
        assertEquals(RuleStanding.HOLDING, set.ruleStandings(now, zone)[1], "15:00 is inside 14-18")
        val later = reminder(RuleMatch.TOGETHER, office, evening)
        assertEquals(RuleStanding.NOT_HOLDING, later.ruleStandings(now, zone)[1], "and outside 20-23")
    }

    @Test
    fun `a place answers from the watch, and says so when nobody has looked`() {
        val set = reminder(RuleMatch.TOGETHER, office, afternoon)
        assertEquals(RuleStanding.UNKNOWN, set.ruleStandings(now, zone) { null }[0])
        assertEquals(RuleStanding.HOLDING, set.ruleStandings(now, zone) { true }[0])
        assertEquals(RuleStanding.NOT_HOLDING, set.ruleStandings(now, zone) { false }[0])
    }

    @Test
    fun `waiting to leave holds while the phone is out, which is the other way round`() {
        val set = reminder(RuleMatch.TOGETHER, leavingOffice, afternoon)
        assertEquals(RuleStanding.HOLDING, set.ruleStandings(now, zone) { false }[0])
        assertEquals(RuleStanding.NOT_HOLDING, set.ruleStandings(now, zone) { true }[0])
    }

    @Test
    fun `a moment has no standing under a la vez, because it is what rings`() {
        val set = reminder(RuleMatch.TOGETHER, nineAm, afternoon)
        assertEquals(listOf(null, RuleStanding.HOLDING), set.ruleStandings(now, zone))
    }
}
