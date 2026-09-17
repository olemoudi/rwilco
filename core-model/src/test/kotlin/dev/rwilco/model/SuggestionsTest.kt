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
    fun `what has been written before is matched by the start of its words, as it is typed`() {
        val written = listOf("Comprar pan", "Sacar la basura", "Comprar filtros de café", "Llamar a mamá", "Pan de molde")
        // One word on its way to being a word of the phrase, accents and capitals aside.
        assertEquals(listOf("Comprar pan", "Comprar filtros de café"), textsMatching(written, "com"))
        assertEquals(listOf("Llamar a mamá"), textsMatching(written, "mama"))
        // Every word typed has to start a word of it, in any order: "pan com" is still that one.
        assertEquals(listOf("Comprar pan"), textsMatching(written, "pan com"))
        assertEquals(listOf("Comprar filtros de café"), textsMatching(written, "Comprar fil"))
        // In the order it came in — best first — and few enough to sit under the field.
        assertEquals(listOf("Comprar pan", "Pan de molde"), textsMatching(written, "pan"))
        assertEquals(listOf("Comprar pan"), textsMatching(written, "pan", limit = 1))
    }

    @Test
    fun `the phrase already typed whole is not offered back, and the middle of a word matches nothing`() {
        val written = listOf("Comprar pan", "Sacar la basura")
        assertEquals(emptyList<String>(), textsMatching(written, "comprar pan"), "it is what the field already says")
        assertEquals(emptyList<String>(), textsMatching(written, "asura"), "not somebody on their way to «basura»")
        assertEquals(emptyList<String>(), textsMatching(written, "   "))
        assertEquals(emptyList<String>(), textsMatching(written, "dentista"))
    }

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
