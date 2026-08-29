package dev.rwilco.model

import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * A repeating time that was a trigger, read as the calendar it always was — and what happens to
 * everything that pointed at the list it was in.
 */
class LegacyRepeatsTest {

    private val nine = Trigger.AtTime(LocalTime.of(9, 0), setOf(DayOfWeek.MONDAY))
    private val ten = Trigger.AtTime(LocalTime.of(10, 0), setOf(DayOfWeek.TUESDAY))
    private val home = Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa")

    private fun stored(vararg triggers: Trigger, armedRule: Int?, firedRules: Set<Int>) = Reminder(
        id = "r1",
        text = "Pastillas",
        rules = triggers.map { TriggerRule(it) },
        ruleMatch = RuleMatch.ALL,
        createdAt = local(2026, 8, 27, 15, 0),
        updatedAt = local(2026, 8, 27, 15, 0),
        armedFor = local(2026, 8, 28, 9, 0),
        armedRule = armedRule,
        firedRules = firedRules,
        lastFiredAt = local(2026, 8, 27, 9, 0),
        lastFiredRule = armedRule,
    )

    @Test
    fun `two legacy repeats fold to one calendar and the rules after them move by two`() {
        // Every legacy repeat goes; the indices used to move by one, as if only the first had,
        // and a reminder with two of them came out pointing at rules it no longer had.
        val folded = stored(nine, ten, home, armedRule = 2, firedRules = setOf(1, 2)).foldRepeats(zone)
        assertEquals(listOf(TriggerRule(home)), folded.rules)
        assertEquals(Recurrence.Calendar(Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 27), unit = RepeatUnit.WEEK, time = LocalTime.of(9, 0), days = setOf(DayOfWeek.MONDAY))), folded.recurrence)
        assertEquals(0, folded.armedRule, "the place, two places further left")
        assertEquals(setOf(0), folded.firedRules, "the repeat's tick is gone, the place's moved")
        assertEquals(0, folded.lastFiredRule, "and so is the rule the last ring was for")
    }

    @Test
    fun `a preset folds the same way, and a shape already answered by a calendar is left alone`() {
        val preset = Preset(
            id = "p1",
            name = "Pastillas",
            rules = listOf(TriggerRule(nine), TriggerRule(home)),
            createdAt = local(2026, 8, 27, 15, 0),
        ).foldRepeats(zone)
        assertEquals(listOf(TriggerRule(home)), preset.rules)
        assertEquals(true, preset.recurrence is Recurrence.Calendar)
        // A store with both a legacy repeat and a calendar is hand-edited; guessing which of the
        // two somebody meant is exactly what the fold exists to stop, so it does nothing.
        val both = stored(nine, home, armedRule = 1, firedRules = emptySet())
            .copy(recurrence = Recurrence.Calendar(Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 24), unit = RepeatUnit.WEEK, time = LocalTime.of(10, 0))))
        assertEquals(both, both.foldRepeats(zone))
        val untouched = stored(home, armedRule = 0, firedRules = emptySet())
        assertEquals(untouched, untouched.foldRepeats(zone), "nothing legacy, nothing moved")
    }

    @Test
    fun `a repeat written as a rule is the calendar as it stands, fences and all`() {
        val monthly = Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 1), unit = RepeatUnit.MONTH, time = LocalTime.of(9, 0), monthly = MonthlyOn.Day(1))
        val fence = Condition.TimeWindow(LocalTime.of(8, 0), LocalTime.of(10, 0))
        val folded = stored(home, armedRule = null, firedRules = emptySet())
            .copy(rules = listOf(TriggerRule(monthly, listOf(fence)), TriggerRule(home)))
            .foldRepeats(zone)
        assertEquals(Recurrence.Calendar(monthly, listOf(fence)), folded.recurrence)
        assertEquals(listOf(TriggerRule(home)), folded.rules)
    }

    @Test
    fun `a weekly hour with no days folds to every day, not to the day it was written on`() {
        val bare = stored(Trigger.AtTime(LocalTime.of(9, 0), emptySet()), armedRule = null, firedRules = emptySet()).foldRepeats(zone)
        val calendar = (bare.recurrence as Recurrence.Calendar).repeat
        assertEquals(DayOfWeek.entries.toSet(), calendar.days)
    }

    @Test
    fun `an alarm armed for a repeat belongs to no rule once it is the calendar`() {
        val folded = stored(home, nine, armedRule = 1, firedRules = emptySet()).foldRepeats(zone)
        assertNull(folded.armedRule)
        assertNull(folded.armedFor, "the recurrence has its own moment; the old one is not it")
        assertNull(folded.lastFiredRule, "a ring of the repeat's is the calendar's now, and has no index")
        assertEquals(listOf(TriggerRule(home)), folded.rules)
    }
}
