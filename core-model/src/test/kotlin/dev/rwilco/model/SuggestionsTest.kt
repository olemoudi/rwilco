package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class SuggestionsTest {

    private fun used(text: String, daysAgo: Long, tags: List<String> = emptyList()) = Fixtures
        .reminder(id = "$text-$daysAgo", text = text, tags = tags)
        .copy(updatedAt = now.minus(Duration.ofDays(daysAgo)))

    @Test
    fun `often beats recently, but not for ever`() {
        val reminders = listOf(
            used("Sacar la basura", 1),
            used("Sacar la basura", 8),
            used("Sacar la basura", 15),
            used("Comprar pan", 0),
        )
        assertEquals(listOf("Sacar la basura", "Comprar pan"), suggestedTexts(reminders, now))
    }

    @Test
    fun `a single recent use beats a single old one`() {
        val reminders = listOf(used("Renovar el DNI", 200), used("Comprar pan", 2))
        assertEquals(listOf("Comprar pan", "Renovar el DNI"), suggestedTexts(reminders, now))
    }

    @Test
    fun `the same thing said twice is one suggestion, spelled the way it was last`() {
        val reminders = listOf(used("comprar pan", 30), used("Comprar Pan", 1))
        assertEquals(listOf("Comprar Pan"), suggestedTexts(reminders, now))
    }

    @Test
    fun `what is already written is not offered back`() {
        val reminders = listOf(used("Comprar pan", 1), used("Sacar la basura", 1))
        assertEquals(listOf("Sacar la basura"), suggestedTexts(reminders, now, exclude = "  comprar pan "))
    }

    @Test
    fun `blank texts and empty lists are nothing`() {
        assertTrue(suggestedTexts(listOf(used("   ", 1)), now).isEmpty())
        assertTrue(suggestedTexts(emptyList(), now).isEmpty())
        assertTrue(suggestedTags(emptyList(), now).isEmpty())
    }

    @Test
    fun `tags rank the same way and keep one spelling`() {
        val reminders = listOf(
            used("a", 1, tags = listOf("casa", "Compra")),
            used("b", 2, tags = listOf("compra")),
            used("c", 90, tags = listOf("papeleo")),
        )
        assertEquals(listOf("Compra", "casa", "papeleo"), suggestedTags(reminders, now))
    }

    @Test
    fun `the limit is honoured`() {
        val reminders = (1..30).map { used("text $it", it.toLong()) }
        assertEquals(5, suggestedTexts(reminders, now, limit = 5).size)
    }
}
