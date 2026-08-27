package dev.rwilco.ui.home

import dev.rwilco.model.Period
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.Section
import dev.rwilco.model.Status
import dev.rwilco.model.TagFilter
import dev.rwilco.model.Presence
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.TriggerFamily
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
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
    private val place = reminder("place", Trigger.Location(40.4, -3.7, 200, Presence.INSIDE, "Casa"), tags = listOf("casa"))
    private val random = reminder("random", Trigger.Random(2, Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0), emptySet()), tags = listOf("salud"))
    private val paused = reminder("paused", Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 16, 0)), status = Status.PAUSED)

    @Test
    fun `a row says whether its circle is costing anything`() {
        // The shape is the battery and the colour is the answer, so the row has to carry both.
        // "En casa, a la vez que de 20 a 22" at three in the afternoon: the circle cannot ring
        // for five hours and is not worth a position until two before, so it rides along on
        // whatever the others pay for — and the card says so.
        val gated = Reminder(
            id = "gated",
            text = "Regar",
            rules = listOf(
                TriggerRule(Trigger.Location(40.4, -3.7, 200, Presence.INSIDE, "Casa")),
                TriggerRule(Trigger.Interval(LocalTime.of(20, 0), LocalTime.of(22, 0))),
            ),
            ruleMatch = dev.rwilco.model.RuleMatch.TOGETHER,
            status = Status.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
        val state = buildHomeState(listOf(gated, place), defaultTime, now, zone, selectedTag = null)
        val cards = listOfNotNull(state.hero?.card) + state.sections.flatMap { it.cards }
        val rows = cards.first { it.id == "gated" }.triggers
        assertFalse(rows[0].watched, "a circle five hours from being able to ring is not worth a position")
        assertTrue(rows[1].watched, "and nothing but a place is ever not watched")
        // A place with nothing in front of it is watched, and the row says nothing about
        // battery it does not have to.
        assertTrue(cards.first { it.id == "place" }.triggers[0].watched)
    }

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
        // The tags in use, and then the app's own two — one reminder here carries no tag and
        // one is paused, so both have something to show.
        assertEquals(
            listOf(TagFilter.Named("casa"), TagFilter.Named("salud"), TagFilter.Untagged, TagFilter.Paused),
            state.tags,
        )
    }

    @Test
    fun `a tag filter narrows the cards and a stale filter is dropped`() {
        val filtered = buildHomeState(listOf(soon, place, random), defaultTime, now, zone, selectedTag = TagFilter.Named("casa"))
        assertEquals(TagFilter.Named("casa"), filtered.selectedTag)
        assertEquals("soon", filtered.hero!!.card.id)
        assertEquals(listOf("place"), filtered.sections.single().cards.map { it.id })

        val stale = buildHomeState(listOf(soon), defaultTime, now, zone, selectedTag = TagFilter.Named("trabajo"))
        assertNull(stale.selectedTag, "a filter on a tag nobody has anymore is silently cleared")
        assertEquals("soon", stale.hero!!.card.id)
    }

    @Test
    fun `no open reminders is the empty state, a filter that matches nothing is not`() {
        val done = reminder("done", Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 16, 0)), status = Status.DONE)
        assertTrue(buildHomeState(listOf(done), defaultTime, now, zone, selectedTag = null).empty)
        assertTrue(buildHomeState(emptyList(), defaultTime, now, zone, selectedTag = null).empty)
        val filtered = buildHomeState(listOf(soon, random), defaultTime, now, zone, selectedTag = TagFilter.Named("salud"))
        assertFalse(filtered.empty)
    }

    @Test
    fun `a recurrence that works out its own moments gets a row of its own`() {
        // "Cada 6 h" carries no trigger at all, so the card had nothing to show and said nothing
        // whatsoever about when it rings — a shape that was real, armed and invisible.
        val pills = Reminder(
            id = "pills",
            text = "Tomar la pastilla",
            recurrence = Recurrence.After(6, RecurrenceUnit.HOURS),
            createdAt = now.minusSeconds(3600),
            updatedAt = now.minusSeconds(3600),
        )

        val card = cardFor(pills)
        assertTrue(card.triggers.isEmpty(), "there is no trigger to show")
        assertEquals(Recurrence.After(6, RecurrenceUnit.HOURS), card.recurrence)
    }

    @Test
    fun `a recurrence the triggers already answer for is not said twice`() {
        // ByTrigger IS the repeating trigger on the card above it; a second row saying so is
        // noise. And a reminder that does not repeat has nothing to say either.
        val weekly = Trigger.AtTime(LocalTime.of(9, 0), setOf(DayOfWeek.MONDAY))
        val base = Reminder(id = "r", text = "Regar", rules = listOf(TriggerRule(weekly)), createdAt = now, updatedAt = now)

        assertNull(cardFor(base.copy(recurrence = Recurrence.ByTrigger)).recurrence)
        assertNull(cardFor(base).recurrence)
        // One that works out its own moments shows up even next to a trigger: it is the answer
        // to a different question, and the trigger cannot speak for it.
        assertEquals(
            Recurrence.After(1, RecurrenceUnit.DAYS),
            cardFor(base.copy(recurrence = Recurrence.After(1, RecurrenceUnit.DAYS))).recurrence,
        )
    }

    private fun cardFor(reminder: Reminder): ReminderCardUi {
        val state = buildHomeState(listOf(reminder), LocalTime.of(9, 0), now, zone, selectedTag = null)
        return state.hero?.card ?: state.sections.first().cards.first()
    }
}
