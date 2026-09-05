package dev.rwilco.model

import dev.rwilco.model.Fixtures.reminder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The little the app remembers about tags themselves.
 *
 * A tag is read off the reminders wearing it, so there is nothing to keep — except the two
 * things they cannot say: which tags somebody put at the front of the row, and a tag written
 * down before anything wears it.
 */
class TagPrefTest {

    private val trigger = Trigger.OnDate(LocalDate.of(2026, 9, 1))

    private fun r(id: String, tags: List<String>) = reminder(trigger, id = id, tags = tags)

    @Test
    fun `pinning is an order and not a filter`() {
        val tags = listOf("compra", "casa", "salud")
        val prefs = listOf(TagPref("salud", pinned = true))
        assertEquals(listOf("salud", "compra", "casa"), pinnedFirst(tags, prefs))
        assertEquals(tags, pinnedFirst(tags, emptyList()), "nothing pinned, nothing moved")
    }

    @Test
    fun `the pinned ones lead in the order they were pinned, the rest keep theirs`() {
        val tags = listOf("a", "b", "c", "d")
        val prefs = listOf(TagPref("c", pinned = true), TagPref("b"), TagPref("a", pinned = true))
        assertEquals(listOf("c", "a", "b", "d"), pinnedFirst(tags, prefs))
    }

    @Test
    fun `a tag written down exists before anything wears it`() {
        val prefs = withTagPref(emptyList(), "bici", pinned = false)
        assertEquals(listOf(TagPref("bici")), prefs)
        assertEquals(listOf("casa", "bici"), knownTags(listOf("casa"), prefs), "offered, and after what is used")
        assertTrue(tagFilters(listOf(r("a", listOf("casa"))), prefs).none { it == TagFilter.Named("bici") }) {
            "a filter that finds nothing is not a filter"
        }
    }

    @Test
    fun `pinning a tag twice says the same thing once`() {
        val once = withTagPref(emptyList(), "casa", pinned = true)
        val twice = withTagPref(once, "casa", pinned = true)
        assertEquals(once, twice)
        assertEquals(listOf(TagPref("casa", pinned = false)), withTagPref(twice, "CASA", pinned = false), "and unpinning finds it whatever the case")
    }

    @Test
    fun `the row a tag leaves behind follows a rename and goes with a removal`() {
        val prefs = listOf(TagPref("compra", pinned = true), TagPref("casa"))
        assertEquals(
            listOf(TagPref("la compra", pinned = true), TagPref("casa")),
            withTagRenamed(prefs, "Compra", "la compra"),
        )
        assertEquals(prefs, withTagRenamed(prefs, "salud", "sanidad"), "a tag with nothing said about it has no row to follow")
        assertEquals(listOf(TagPref("casa")), withTagForgotten(prefs, "COMPRA"))
    }

    @Test
    fun `renaming one tag onto another merges their rows, pinned if either was`() {
        val prefs = listOf(TagPref("casa"), TagPref("hogar", pinned = true))
        assertEquals(listOf(TagPref("hogar", pinned = true)), withTagRenamed(prefs, "casa", "hogar"))
    }

    @Test
    fun `the spelling on the reminders wins over the spelling in the row`() {
        val prefs = listOf(TagPref("Casa", pinned = true))
        assertEquals(listOf("casa"), knownTags(listOf("casa"), prefs))
    }

    @Test
    fun `home wears the pinned tags first`() {
        val reminders = listOf(r("a", listOf("compra")), r("b", listOf("compra")), r("c", listOf("salud")))
        assertEquals(
            listOf(TagFilter.Named("compra"), TagFilter.Named("salud")),
            tagFilters(reminders),
            "by use, until somebody says otherwise",
        )
        assertEquals(
            listOf(TagFilter.Named("salud"), TagFilter.Named("compra")),
            tagFilters(reminders, listOf(TagPref("salud", pinned = true))),
        )
    }
}
