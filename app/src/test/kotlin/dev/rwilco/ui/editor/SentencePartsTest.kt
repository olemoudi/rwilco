package dev.rwilco.ui.editor

import dev.rwilco.model.Condition
import dev.rwilco.model.Presence
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.LocalTime

/** The shape of the sentence over the save button: what is said, and in what order. */
class SentencePartsTest {

    private val clock = TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 9, 0)))
    private val place = TriggerRule(Trigger.Location(40.4, -3.7, 150, Presence.INSIDE, "Casa"))

    @Test
    fun `the words, the rules, and the recurrence last`() {
        val parts = sentenceParts(
            text = "Sacar la basura",
            rules = listOf(clock),
            match = RuleMatch.ANY,
            recurrence = Recurrence.After(2, RecurrenceUnit.WEEKS),
        )
        assertEquals(
            listOf(
                SentencePart.Words("Sacar la basura"),
                SentencePart.Rule(clock),
                SentencePart.Returns(Recurrence.After(2, RecurrenceUnit.WEEKS)),
            ),
            parts,
        )
    }

    @Test
    fun `the join between two rules is the whole reading`() {
        fun joins(match: RuleMatch) = sentenceParts("Llamar a Marta", listOf(place, clock), match, Recurrence.None)
            .filterIsInstance<SentencePart.Join>()
            .map { it.match }

        // One join for two rules, and it says which of the three arrangements this is.
        assertEquals(listOf(RuleMatch.ANY), joins(RuleMatch.ANY))
        assertEquals(listOf(RuleMatch.ALL), joins(RuleMatch.ALL))
        assertEquals(listOf(RuleMatch.TOGETHER), joins(RuleMatch.TOGETHER))
    }

    @Test
    fun `three rules are joined twice, never at the ends`() {
        val parts = sentenceParts("x", listOf(clock, place, clock), RuleMatch.ALL, Recurrence.None)
        assertEquals(
            listOf("Words", "Rule", "Join", "Rule", "Join", "Rule"),
            parts.map { it::class.simpleName },
        )
    }

    @Test
    fun `a rule carries its own fences`() {
        val fenced = clock.copy(conditions = listOf(Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))))
        val part = sentenceParts("x", listOf(fenced), RuleMatch.ANY, Recurrence.None)
            .filterIsInstance<SentencePart.Rule>()
            .single()
        assertEquals(fenced.conditions, part.rule.conditions)
    }

    @Test
    fun `blank words are left out rather than stood in for`() {
        val parts = sentenceParts("   ", listOf(clock), RuleMatch.ANY, Recurrence.None)
        assertTrue(parts.none { it is SentencePart.Words })
        assertEquals(1, parts.size)
    }

    @Test
    fun `a recurrence that works out no moments of its own says nothing`() {
        // "No repetir" is the absence of a clause, and so is "lo decide el azar" — the random
        // window on the row above IS that answer.
        for (recurrence in listOf(Recurrence.None, Recurrence.ByTrigger)) {
            val parts = sentenceParts("x", listOf(clock), RuleMatch.ANY, recurrence)
            assertTrue(parts.none { it is SentencePart.Returns }, "$recurrence")
        }
        val anchored = sentenceParts("x", listOf(clock), RuleMatch.ANY, Recurrence.After(6, RecurrenceUnit.HOURS))
        assertTrue(anchored.any { it is SentencePart.Returns })
    }

    @Test
    fun `a note with no when at all has nothing to read back`() {
        val note = sentenceParts("Ideas para el regalo", emptyList(), RuleMatch.ANY, Recurrence.None)
        assertEquals(listOf(SentencePart.Words("Ideas para el regalo")), note)
        assertFalse(note.saysMoreThanWords(), "the line stays off the screen")
        assertTrue(sentenceParts("x", listOf(clock), RuleMatch.ANY, Recurrence.None).saysMoreThanWords())
    }
}
