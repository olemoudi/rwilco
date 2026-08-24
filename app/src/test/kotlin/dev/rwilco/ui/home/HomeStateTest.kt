package dev.rwilco.ui.home

import dev.rwilco.model.Period
import dev.rwilco.model.Reminder
import dev.rwilco.model.Section
import dev.rwilco.model.Status
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.TriggerFamily
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class HomeStateTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private val now: Instant = LocalDateTime.of(2026, 8, 27, 15, 0).atZone(zone).toInstant()
    private val defaultTime = LocalTime.of(9, 0)

    private fun reminder(id: String, vararg triggers: Trigger, tags: List<String> = emptyList(), status: Status = Status.ACTIVE) =
        Reminder(id = id, text = "text $id", tags = tags, rules = triggers.map(::TriggerRule), status = status, createdAt = now, updatedAt = now)

    private val soon = reminder("soon", Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 16, 0)), tags = listOf("casa"))
    private val place = reminder("place", Trigger.Location(40.4, -3.7, 200, Transition.ENTER, "Casa"), tags = listOf("casa"))
    private val random = reminder("random", Trigger.Random(2, Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0), emptySet()), tags = listOf("salud"))
    private val paused = reminder("paused", Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 16, 0)), status = Status.PAUSED)

    @Test
    fun `the hero and the sections come out as cards with per-trigger moments`() {
        val state = buildHomeState(listOf(soon, place, random, paused), defaultTime, now, zone, selectedTag = null)
        assertTrue(state.loaded)
        assertFalse(state.empty)
        assertEquals("soon", state.hero!!.card.id)
        assertEquals(LocalDateTime.of(2026, 8, 27, 16, 0).atZone(zone).toInstant(), state.hero!!.at)
        assertEquals(listOf(Section.WHENEVER, Section.PAUSED), state.sections.map { it.section })
        val whenever = state.sections[0].cards
        assertEquals(listOf("random", "place"), whenever.map { it.id }, "a draw with a moment sorts before a place")
        val randomRow = whenever[0].triggers.single()
        assertEquals(TriggerFamily.CHANCE, randomRow.family)
        assertNull(randomRow.nextAt)
        assertNotNull(randomRow.window)
        val placeRow = whenever[1].triggers.single()
        assertEquals(TriggerFamily.PLACE, placeRow.family)
        assertNull(placeRow.nextAt)
        assertNull(placeRow.window)
        assertTrue(state.sections[1].cards.single().paused)
        assertEquals(listOf("casa", "salud"), state.tags)
    }

    @Test
    fun `a tag filter narrows the cards and a stale filter is dropped`() {
        val filtered = buildHomeState(listOf(soon, place, random), defaultTime, now, zone, selectedTag = "casa")
        assertEquals("casa", filtered.selectedTag)
        assertEquals("soon", filtered.hero!!.card.id)
        assertEquals(listOf("place"), filtered.sections.single().cards.map { it.id })

        val stale = buildHomeState(listOf(soon), defaultTime, now, zone, selectedTag = "trabajo")
        assertNull(stale.selectedTag, "a filter on a tag nobody has anymore is silently cleared")
        assertEquals("soon", stale.hero!!.card.id)
    }

    @Test
    fun `no open reminders is the empty state, a filter that matches nothing is not`() {
        val done = reminder("done", Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 16, 0)), status = Status.DONE)
        assertTrue(buildHomeState(listOf(done), defaultTime, now, zone, selectedTag = null).empty)
        assertTrue(buildHomeState(emptyList(), defaultTime, now, zone, selectedTag = null).empty)
        val filtered = buildHomeState(listOf(soon, random), defaultTime, now, zone, selectedTag = "salud")
        assertFalse(filtered.empty)
    }
}
