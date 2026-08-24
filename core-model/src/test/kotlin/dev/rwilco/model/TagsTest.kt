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
}
