package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CurationTest {

    private fun reminder(id: String, text: String, vararg tags: String) = Reminder(
        id = id,
        text = text,
        tags = tags.toList(),
        createdAt = now.minusSeconds(1_000),
        updatedAt = now.minusSeconds(500),
    )

    private val all = listOf(
        reminder("1", "Comprar pan", "compra", "casa"),
        reminder("2", "Regar las plantas", "Casa"),
        reminder("3", "Llamar al banco", "papeleo"),
        reminder("4", "comprar pan", "compra"),
    )

    @Test
    fun `renaming a tag touches only the reminders wearing it, whatever the case`() {
        val changed = renameTagIn(all, "casa", "hogar")
        assertEquals(listOf("1", "2"), changed.map { it.id })
        assertEquals(listOf("compra", "hogar"), changed[0].tags)
        assertEquals(listOf("hogar"), changed[1].tags)
    }

    @Test
    fun `renaming a tag onto one the reminder already has merges instead of doubling it`() {
        val changed = renameTagIn(all, "compra", "Casa")
        assertEquals(listOf("1", "4"), changed.map { it.id })
        assertEquals(listOf("Casa"), changed[0].tags, "the reminder had both; now it has one")
        assertEquals(listOf("Casa"), changed[1].tags)
    }

    @Test
    fun `a new name is a rename, and so is a respelling of the same tag`() {
        assertNull(tagMergeCount(all, emptyList(), "casa", "hogar"))
        // The carriers of "casa" match "Casa" once case is ignored, which is exactly what made a
        // respelling read as a merge — and lose the undo a rename is owed.
        assertNull(tagMergeCount(all, emptyList(), "casa", "CASA"))
        assertNull(tagMergeCount(all, emptyList(), "casa", "   "), "a rename to nothing is not anything")
    }

    @Test
    fun `renaming onto a tag already worn is a merge, and counts each reminder once`() {
        // "compra" is on 1 and 4, "casa" on 1 and 2: three reminders end up under the one tag,
        // and the first of them was wearing both.
        assertEquals(3, tagMergeCount(all, emptyList(), "compra", "Casa"))
    }

    @Test
    fun `renaming onto a tag that is only written down, or only on a preset, still asks`() {
        // Nothing wears "viaje" yet, but it exists — it has a row in the panel — and after the
        // rename there is one tag where the panel showed two. No inverse, so it is a question.
        assertEquals(2, tagMergeCount(all, listOf("viaje"), "compra", "Viaje"))
    }

    @Test
    fun `a rename to nothing is not a rename`() {
        assertTrue(renameTagIn(all, "casa", "   ").isEmpty())
    }

    @Test
    fun `removing a tag leaves the reminders and the other tags alone`() {
        val changed = removeTagIn(all, "CASA")
        assertEquals(listOf("1", "2"), changed.map { it.id })
        assertEquals(listOf("compra"), changed[0].tags)
        assertTrue(changed[1].tags.isEmpty())
    }

    @Test
    fun `renaming a text takes every reminder with exactly those words`() {
        val changed = renameTextIn(all, "Comprar pan", "Comprar pan de masa madre")
        assertEquals(listOf("1", "4"), changed.map { it.id }, "case does not make it a different phrase")
        assertTrue(changed.all { it.text == "Comprar pan de masa madre" })
    }

    @Test
    fun `renaming a text never touches a reminder that merely contains it`() {
        val longer = listOf(reminder("5", "Comprar pan y leche"))
        assertTrue(renameTextIn(longer, "Comprar pan", "Otra cosa").isEmpty())
    }

    @Test
    fun `curating leaves updatedAt alone, or every rename would look like a use`() {
        val renamed = renameTagIn(all, "casa", "hogar").first()
        assertEquals(all.first().updatedAt, renamed.updatedAt)
        assertEquals(all.first().createdAt, renamed.createdAt)
    }

    @Test
    fun `a dismissed phrase drops out of the offers and stays dismissed once`() {
        val texts = listOf("Comprar pan", "Regar las plantas")
        val hidden = withHiddenText(emptyList(), "comprar PAN")
        assertEquals(listOf("Regar las plantas"), visibleTexts(texts, hidden))
        assertEquals(hidden, withHiddenText(hidden, "Comprar pan"), "dismissing it twice does not list it twice")
        assertEquals(emptyList<String>(), withHiddenText(emptyList(), "  "))
    }
}
