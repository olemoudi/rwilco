package dev.rwilco.ui.editor

import dev.rwilco.model.Presence
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * What the editor says and offers about a set of rules, held to what the set does (the
 * trigger-contract audit, 2026-11-04).
 */
class EditorContractTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private val now: Instant = LocalDateTime.of(2026, 8, 27, 15, 0).atZone(zone).toInstant()
    private val blank = EditorUiState(loaded = true)
    private val door = Trigger.Location(40.4168, -3.7038, 150, Presence.INSIDE, "Casa", onCrossing = true)
    private val home = door.copy(onCrossing = false)
    private val seven = Trigger.TimeOfDay(LocalTime.of(19, 0))

    @Test
    fun `turning a doorway into a state beside an hour asks a la vez, and back asks cualquiera`() {
        val pair = blank.commitTrigger(null, seven, now = now, zone = zone).commitTrigger(null, door, now = now, zone = zone)
        assertEquals(RuleMatch.ANY, pair.draft.ruleMatch, "two moments never coincide")
        val state = pair.commitTrigger(1, home, now = now, zone = zone)
        assertEquals(RuleMatch.TOGETHER, state.draft.ruleMatch)
        val back = state.commitTrigger(1, door, now = now, zone = zone)
        assertEquals(RuleMatch.ANY, back.draft.ruleMatch, "a la vez over two moments would never ring")
        // "Todos" chosen by hand is nobody's default and is left alone.
        val all = state.setRuleMatch(RuleMatch.ALL).commitTrigger(1, door, now = now, zone = zone)
        assertEquals(RuleMatch.ALL, all.draft.ruleMatch)
    }

    @Test
    fun `an hour a la vez with a place is offered as a stretch`() {
        val together = blank.commitTrigger(null, seven, now = now, zone = zone).commitTrigger(null, home, now = now, zone = zone)
        assertEquals(RuleMatch.TOGETHER, together.draft.ruleMatch)
        assertEquals(0 to Trigger.Interval(LocalTime.of(19, 0), LocalTime.of(22, 0)), together.draft.widenedMoment())
        val widened = together.widenMoment()
        assertEquals(Trigger.Interval(LocalTime.of(19, 0), LocalTime.of(22, 0)), widened.draft.rules[0].trigger)
        assertNull(widened.draft.widenedMoment(), "nothing left to offer")
        assertEquals(SetCaveat.IfThere("Casa", inside = true), setCaveatOf(together.draft.rules, RuleMatch.TOGETHER))
        assertEquals(SetCaveat.WhenAllHappened, setCaveatOf(together.draft.rules, RuleMatch.ALL))
        assertNull(setCaveatOf(together.draft.rules, RuleMatch.ANY))
    }
}
