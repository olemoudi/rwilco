package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A stretch of the day opens at the first minute its own fences allow.
 *
 * [Trigger.Weekday] and [Trigger.DayRandom] have always done that ([openingOf]); the window did
 * not, and took its own `from` bare. So a fence that ruled out the opening ruled out the whole
 * shape: the walk offered the same hour the next day, and the next, and after
 * [MAX_CANDIDATES] of them the rule read as one that could never ring — a reminder that
 * plainly holds at ten o'clock, silent for ever, and told so by the editor in so many words.
 */
class IntervalFenceTest {

    /** A Thursday at 08:00 in Madrid, before any of the windows below have opened. */
    private val morning = local(2026, 8, 27, 8, 0)

    private fun ruleOf(trigger: Trigger, vararg conditions: Condition) = TriggerRule(trigger, conditions.toList())

    @Test
    fun `a window fenced to later hours opens when the fence lets it`() {
        val rule = ruleOf(
            Trigger.Interval(LocalTime.of(9, 0), LocalTime.of(11, 0)),
            Condition.TimeWindow(LocalTime.of(10, 0), LocalTime.of(12, 0)),
        )
        val at = (nextFireOfRule(rule, "r1", morning, zone, defaultTime) as? NextFire.Scheduled)?.at
        assertEquals(local(2026, 8, 27, 10, 0), at, "it holds at ten, and that is when it rings")
    }

    @Test
    fun `a bare window still opens at its own hour`() {
        val rule = ruleOf(Trigger.Interval(LocalTime.of(9, 0), LocalTime.of(11, 0)))
        val at = (nextFireOfRule(rule, "r1", morning, zone, defaultTime) as? NextFire.Scheduled)?.at
        assertEquals(local(2026, 8, 27, 9, 0), at, "no fences, no change")
    }

    @Test
    fun `a fence outside the window leaves nothing to open`() {
        // "De 09:00 a 11:00, y sólo si es de 17:00 a 19:00" really does never ring, and the
        // opening comes back bare for the walk above to reject — which is what says so.
        val rule = ruleOf(
            Trigger.Interval(LocalTime.of(9, 0), LocalTime.of(11, 0)),
            Condition.TimeWindow(LocalTime.of(17, 0), LocalTime.of(19, 0)),
        )
        assertEquals(null, nextFireOfRule(rule, "r1", morning, zone, defaultTime))
    }

    @Test
    fun `a window only on the days it names`() {
        val rule = ruleOf(Trigger.Interval(LocalTime.of(9, 0), LocalTime.of(11, 0), days = setOf(DayOfWeek.SATURDAY)))
        val at = (nextFireOfRule(rule, "r1", morning, zone, defaultTime) as? NextFire.Scheduled)?.at
        assertEquals(local(2026, 8, 29, 9, 0), at, "the Thursday is not one of its days")
    }

    /**
     * The same fix read through "a la vez", which is where it actually bit: the fold turns every
     * sibling into a fence, so two overlapping stretches fenced each other out and a set that
     * plainly holds at ten never rang.
     */
    @Test
    fun `two overlapping stretches at once ring where they overlap`() {
        val reminder = Reminder(
            id = "r1",
            text = "Call the office",
            rules = listOf(
                TriggerRule(Trigger.Interval(LocalTime.of(9, 0), LocalTime.of(11, 0))),
                TriggerRule(Trigger.Interval(LocalTime.of(10, 0), LocalTime.of(12, 0))),
            ),
            ruleMatch = RuleMatch.TOGETHER,
            createdAt = morning,
            updatedAt = morning,
        )
        val next = nextFire(reminder, morning, zone, defaultTime)
        assertNotNull(next, "ten o'clock is inside both of them")
        assertEquals(local(2026, 8, 27, 10, 0), (next as NextFire.Scheduled).at)
        assertTrue(warnings(reminder.rules, morning, zone, defaultTime, RuleMatch.TOGETHER).isEmpty())
    }

}
