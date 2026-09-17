package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TagsTest {

    @Test
    fun `a tag is trimmed and its inner whitespace collapsed`() {
        assertEquals("lista de la compra", normalizeTag("  lista   de la\tcompra "))
    }

    @Test
    fun `a blank tag is nothing`() {
        assertNull(normalizeTag("   "))
        assertNull(normalizeTag(""))
    }

    @Test
    fun `a tag is capped at the maximum length without a trailing space`() {
        val long = "a".repeat(MAX_TAG_LENGTH - 1) + " bcdef"
        assertEquals("a".repeat(MAX_TAG_LENGTH - 1), normalizeTag(long))
    }

    @Test
    fun `tags are de-duplicated case-insensitively keeping the first spelling and the order`() {
        assertEquals(
            listOf("Compra", "casa", "Trabajo"),
            normalizeTags(listOf("Compra", "casa", " compra", "CASA ", "Trabajo", "")),
        )
    }

    @Test
    fun `a tag being typed is offered the ones it is turning into, and the ones it has already passed`() {
        val existing = listOf("Compra", "casa", "Camión", "papeleo", "Trabajo")
        // Still on the way to it: what is typed is the start of a tag that exists.
        assertEquals(listOf("Compra"), tagsLike(existing, "comp"))
        // And past it, which is how a second tag gets made by accident: "Compras" beside "Compra".
        assertEquals(listOf("Compra"), tagsLike(existing, "Compras"))
        // In the order they were handed over (most used first), capped, accents and case aside.
        assertEquals(listOf("Compra", "casa", "Camión"), tagsLike(existing, "c"))
        assertEquals(listOf("Compra", "casa"), tagsLike(existing, "c", limit = 2))
        assertEquals(listOf("Camión"), tagsLike(existing, "camion"))
        // The very tag, typed whole, is still worth saying: it exists, and tapping it is the same.
        assertEquals(listOf("casa"), tagsLike(existing, "CASA"))
    }

    @Test
    fun `nothing typed, or nothing like it, offers nothing`() {
        val existing = listOf("Compra", "casa")
        assertEquals(emptyList<String>(), tagsLike(existing, ""))
        assertEquals(emptyList<String>(), tagsLike(existing, "   "))
        assertEquals(emptyList<String>(), tagsLike(existing, "viaje"))
        // Inside a word is not like it: "asa" is not somebody on their way to "casa".
        assertEquals(emptyList<String>(), tagsLike(existing, "asa"))
    }
}
