package dev.rwilco.ui.home

import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class SearchStateTest {

    private val now: Instant = LocalDateTime.of(2026, 8, 27, 15, 0).atZone(ZoneId.of("Europe/Madrid")).toInstant()

    private fun reminder(id: String, text: String, tags: List<String> = emptyList(), status: Status = Status.ACTIVE) = Reminder(
        id = id,
        text = text,
        tags = tags,
        rules = listOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 9, 0)))),
        status = status,
        createdAt = now,
        updatedAt = now,
    )

    private val reminders = listOf(
        reminder("bread", "Comprar pan", tags = listOf("compra")),
        reminder("milk", "Comprar leche", tags = listOf("compra")),
        reminder("bins", "Sacar la basura", tags = listOf("casa")),
    )

    @Test
    fun `closed is empty whatever was typed`() {
        val state = buildSearchState(reminders, "compra", open = false)
        assertFalse(state.open)
        assertEquals(emptyList<SearchHitUi>(), state.hits)
    }

    @Test
    fun `open with nothing typed has not failed to find anything`() {
        val state = buildSearchState(reminders, "", open = true)
        assertTrue(state.hits.isEmpty())
        assertFalse(state.nothingFound, "an empty field has not failed to find anything")
    }

    @Test
    fun `a query that matches nothing says so`() {
        assertTrue(buildSearchState(reminders, "zzzz", open = true).nothingFound)
    }

    @Test
    fun `hits carry what the rows need, and the tag its count`() {
        val hits = buildSearchState(reminders, "compra", open = true).hits
        val tag = hits.filterIsInstance<SearchHitUi.OfTag>().single()
        assertEquals("compra", tag.tag)
        assertEquals(2, tag.count)
        val ids = hits.filterIsInstance<SearchHitUi.OfReminder>().map { it.id }
        assertEquals(setOf("bread", "milk"), ids.toSet())
        assertEquals(listOf("compra"), hits.filterIsInstance<SearchHitUi.OfReminder>().first().tags)
    }

    @Test
    fun `keys are stable and tell the two kinds apart`() {
        val hits = buildSearchState(reminders, "compra", open = true).hits
        assertEquals(hits.size, hits.map { it.key }.distinct().size)
        assertTrue(hits.any { it.key == "tag-compra" } && hits.any { it.key == "reminder-bread" }, hits.map { it.key }.toString())
    }

    @Test
    fun `what is done is found after what is open, and the row knows`() {
        val done = reminders.map { if (it.id == "milk") it.copy(status = Status.DONE) else it }
        val hits = buildSearchState(done, "comprar", open = true).hits.filterIsInstance<SearchHitUi.OfReminder>()
        assertEquals(listOf("bread", "milk"), hits.map { it.id })
        assertEquals(listOf(false, true), hits.map { it.done })
    }
}
