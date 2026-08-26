package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.reminder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The two chips the app keeps for itself. What they are for is being *absent*: a row that ends
 * in "sin etiqueta" every day of your life is a row nobody reads to the end.
 */
class TagFilterTest {

    private val trigger = Trigger.OnDate(LocalDate.of(2026, 9, 1))

    private fun r(id: String, tags: List<String> = emptyList(), status: Status = Status.ACTIVE) =
        reminder(trigger, id = id, tags = tags, status = status)

    @Test
    fun `the app's own chips appear only when they have something in them`() {
        val tidy = listOf(r("a", listOf("casa")), r("b", listOf("salud")))
        assertEquals(listOf(TagFilter.Named("casa"), TagFilter.Named("salud")), tagFilters(tidy))

        val untidy = tidy + r("c")
        assertEquals(TagFilter.Untagged, tagFilters(untidy).last(), "and at the end of the row")

        val paused = tidy + r("d", listOf("casa"), Status.PAUSED)
        assertEquals(listOf(TagFilter.Named("casa"), TagFilter.Named("salud"), TagFilter.Paused), tagFilters(paused))

        val both = tidy + r("c") + r("d", listOf("casa"), Status.PAUSED)
        assertEquals(listOf(TagFilter.Untagged, TagFilter.Paused), tagFilters(both).takeLast(2))
    }

    @Test
    fun `a reminder that is done is nobody's job any more`() {
        val done = listOf(r("a", listOf("casa")), r("b", status = Status.DONE), r("c", status = Status.DONE))
        assertEquals(listOf(TagFilter.Named("casa")), tagFilters(done), "a finished reminder needs no tag")
    }

    @Test
    fun `each chip keeps exactly the reminders it says it does`() {
        val tagged = r("a", listOf("Casa"))
        val bare = r("b")
        val paused = r("c", listOf("casa"), Status.PAUSED)
        val all = listOf(tagged, bare, paused)

        assertTrue(TagFilter.Named("casa").matches(tagged), "one spelling is every spelling")
        assertTrue(TagFilter.Named("casa").matches(paused))
        assertFalse(TagFilter.Named("casa").matches(bare))
        assertTrue(TagFilter.Untagged.matches(bare))
        assertFalse(TagFilter.Untagged.matches(tagged))
        assertTrue(TagFilter.Paused.matches(paused))
        assertFalse(TagFilter.Paused.matches(tagged))
        // A paused reminder is under its tag AND under "en pausa": the chip is a view, not a home.
        assertEquals(2, TagFilter.Named("casa").countIn(all))
        assertEquals(1, TagFilter.Untagged.countIn(all))
        assertEquals(1, TagFilter.Paused.countIn(all))
    }

    @Test
    fun `home filters by the app's own chips as readily as by a tag`() {
        val zone = Fixtures.zone
        val bare = r("b")
        val all = listOf(r("a", listOf("casa")), bare, r("c", listOf("casa"), Status.PAUSED))
        val untagged = groupForHome(all, now, zone, Fixtures.defaultTime, TagFilter.Untagged)
        val shown = listOfNotNull(untagged.hero?.entry?.reminder) + untagged.sections.values.flatten().map { it.reminder }
        assertEquals(listOf("b"), shown.map { it.id })
        val paused = groupForHome(all, now, zone, Fixtures.defaultTime, TagFilter.Paused)
        val stopped = listOfNotNull(paused.hero?.entry?.reminder) + paused.sections.values.flatten().map { it.reminder }
        assertEquals(listOf("c"), stopped.map { it.id })
    }
}
