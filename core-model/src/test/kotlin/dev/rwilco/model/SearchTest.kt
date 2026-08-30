package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchTest {

    private fun reminder(text: String, tags: List<String> = emptyList(), id: String = text, status: Status = Status.ACTIVE) =
        Fixtures.reminder(id = id, text = text, tags = tags, status = status)

    private fun texts(hits: List<SearchHit>) = hits.filterIsInstance<SearchHit.OfReminder>().map { it.reminder.text }
    private fun tags(hits: List<SearchHit>) = hits.filterIsInstance<SearchHit.OfTag>().map { it.tag }

    @Test
    fun `a blank query is not a search`() {
        val reminders = listOf(reminder("Comprar pan"))
        assertEquals(emptyList<SearchHit>(), search(reminders, ""))
        assertEquals(emptyList<SearchHit>(), search(reminders, "   "))
    }

    @Test
    fun `reminders and tags both come back, each saying which it is`() {
        val reminders = listOf(
            reminder("Comprar pan", tags = listOf("compra")),
            reminder("Llamar a mamá", tags = listOf("casa")),
        )
        val hits = search(reminders, "comp")
        assertEquals(listOf("compra"), tags(hits))
        assertEquals(listOf("Comprar pan"), texts(hits))
    }

    @Test
    fun `a tag says how many reminders are under it`() {
        val reminders = listOf(
            reminder("Comprar pan", tags = listOf("compra")),
            reminder("Comprar leche", tags = listOf("Compra")),
            reminder("Sacar la basura", tags = listOf("casa")),
        )
        val hit = search(reminders, "compra").filterIsInstance<SearchHit.OfTag>().single()
        // One tag, counted across both spellings, spelled the way it was first written.
        assertEquals("compra", hit.tag)
        assertEquals(2, hit.count)
    }

    @Test
    fun `accents and case are not something to remember`() {
        val reminders = listOf(reminder("Llamar a mamá", tags = listOf("Teléfono")))
        assertEquals(listOf("Llamar a mamá"), texts(search(reminders, "MAMA")))
        assertEquals(listOf("Teléfono"), tags(search(reminders, "telefono")))
    }

    @Test
    fun `the letters only have to be in order`() {
        val reminders = listOf(reminder("Comprar manzanas"))
        assertEquals(listOf("Comprar manzanas"), texts(search(reminders, "cmp")))
        assertEquals(emptyList<String>(), texts(search(reminders, "zzz")))
    }

    @Test
    fun `what was done is found too, after what is open, and says so`() {
        val reminders = listOf(
            reminder("Comprar pan", tags = listOf("compra"), id = "open"),
            // A better match than the open one, and still behind it: the person typing on Home
            // is after something to do before something they did.
            reminder("Compra", tags = listOf("compra"), id = "done", status = Status.DONE),
        )
        val hits = search(reminders, "compra")
        assertEquals(listOf("Comprar pan", "Compra"), texts(hits))
        assertEquals(listOf(false, true), hits.filterIsInstance<SearchHit.OfReminder>().map { it.done })
        // The tag counts the open ones alone: it is what the filter would show.
        assertEquals(1, hits.filterIsInstance<SearchHit.OfTag>().single().count)
    }

    @Test
    fun `the better match comes first`() {
        val reminders = listOf(
            reminder("Recordar comprar pan por la tarde"),
            reminder("Pan"),
            reminder("Poner la lavadora antes de nada"),
        )
        // Exact, then the one that merely contains it, then the letters-in-order match.
        assertEquals(listOf("Pan", "Recordar comprar pan por la tarde", "Poner la lavadora antes de nada"), texts(search(reminders, "pan")))
    }

    @Test
    fun `a tag and a reminder that match equally well put the tag first`() {
        val reminders = listOf(reminder("compra", tags = listOf("compra")))
        val hits = search(reminders, "compra")
        assertTrue(hits.first() is SearchHit.OfTag, "the broader answer leads: $hits")
    }

    @Test
    fun `the list is capped`() {
        val reminders = (1..30).map { reminder("Comprar cosa $it", id = "r$it") }
        assertEquals(5, search(reminders, "comprar", limit = 5).size)
    }

    @Test
    fun `scoring ranks whole over start over word over anywhere over letters`() {
        val whole = fuzzyScore("pan", "pan")!!
        val start = fuzzyScore("pan", "pan de payés")!!
        val word = fuzzyScore("pan", "comprar pan")!!
        val anywhere = fuzzyScore("pan", "campana")!!
        val letters = fuzzyScore("pan", "poner la nevera")!!
        assertTrue(whole > start && start > word && word > anywhere && anywhere > letters, "$whole $start $word $anywhere $letters")
    }

    @Test
    fun `a query that is not in there at all scores nothing`() {
        assertNull(fuzzyScore("xyz", "comprar pan"))
        assertNull(fuzzyScore("", "comprar pan"))
        assertNull(fuzzyScore("pan", ""))
        assertNotNull(fuzzyScore("cp", "comprar pan"))
    }

    @Test
    fun `folding strips what nobody types on purpose`() {
        assertEquals("mama", fold("  MaMá "))
        assertEquals("nino", fold("Niño"))
    }
}
