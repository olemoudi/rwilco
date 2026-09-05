package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.reminder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * A tag you are offered has to be a tag you can delete.
 *
 * Reported against 0.89.0: a tag showed up in the editor's list of all tags, never appeared on
 * Home, and could not be removed anywhere. It was on finished reminders only — the editor offers
 * from everything ever written ([suggestedTags] over every row), Home's chips filter open
 * reminders ([tagsInUse]), and the panel that administers them had been built on the second of
 * those. Offered from one screen, absent from the only screen that can delete it.
 *
 * The two questions are both legitimate and stay separate. What must hold is that the set the
 * editor offers and the set the panel administers are the same set.
 */
class OrphanTagTest {

    private val trigger = Trigger.OnDate(LocalDate.of(2026, 9, 1))

    private fun r(id: String, tags: List<String>, status: Status = Status.ACTIVE) =
        reminder(trigger, id = id, tags = tags, status = status)

    private fun preset(id: String, tags: List<String>) =
        Preset(id = id, name = id, tags = tags, createdAt = now)

    private val reminders = listOf(
        r("live", listOf("casa")),
        r("finished", listOf("location"), status = Status.DONE),
    )

    @Test
    fun `a tag left on finished reminders only is still a tag that exists`() {
        assertFalse("location" in tagsInUse(reminders), "Home filters open reminders; it would find nothing")
        assertTrue("location" in tagsEverUsed(reminders), "but it exists, and the editor will offer it")
    }

    @Test
    fun `what the editor offers is exactly what the panel administers`() {
        // The two call sites, side by side, which is the only way this class of bug is visible.
        val offeredByTheEditor = knownTags(suggestedTags(reminders, now), emptyList())
        val administeredByThePanel = knownTags(tagsEverUsed(reminders), emptyList())
        assertEquals(
            offeredByTheEditor.map { it.lowercase() }.toSet(),
            administeredByThePanel.map { it.lowercase() }.toSet(),
        )
    }

    @Test
    fun `a tag written down and never worn is in both, and still gets no chip`() {
        val prefs = withTagPref(emptyList(), "bici", pinned = true)
        assertTrue("bici" in knownTags(tagsEverUsed(reminders), prefs))
        assertTrue(tagFilters(reminders, prefs).none { it == TagFilter.Named("bici") })
    }

    /**
     * The other place a tag can hide, and the one that puts it back rather than merely keeping
     * it: a preset carries tags of its own and puts them on every reminder made from it. Removed
     * from every reminder and left in a shape, a tag returns the next time that shape is used.
     */
    @Test
    fun `removing a tag reaches the shapes that would put it back`() {
        val presets = listOf(
            preset("p1", listOf("location", "casa")),
            preset("p2", listOf("casa")),
        )
        assertEquals(listOf(listOf("casa"), listOf("casa")), removeTagInPresets(presets, "LOCATION").map { it.tags })
    }

    @Test
    fun `renaming reaches them too, and merges rather than doubling`() {
        val presets = listOf(preset("p1", listOf("location", "casa")))
        assertEquals(listOf("sitio", "casa"), renameTagInPresets(presets, "location", "sitio").single().tags)
        // Onto a tag the shape already carries: one tag out, not the same one twice.
        assertEquals(listOf("casa"), renameTagInPresets(presets, "location", "casa").single().tags)
    }

    @Test
    fun `a shape without the tag is left exactly as it was`() {
        val untouched = preset("p2", listOf("casa"))
        assertEquals(untouched, removeTagInPresets(listOf(untouched), "location").single())
        assertEquals(listOf(untouched), renameTagInPresets(listOf(untouched), "location", "sitio"))
    }

    @Test
    fun `removing it reaches the finished reminders it was hiding on`() {
        // The delete already read every row; it was the listing that could not see them.
        val cleaned = removeTagIn(reminders, "location")
        assertEquals(listOf("finished"), cleaned.map { it.id })
        assertTrue(cleaned.single().tags.isEmpty())
    }
}
