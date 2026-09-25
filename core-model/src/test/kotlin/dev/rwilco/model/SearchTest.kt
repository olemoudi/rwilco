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

    // --- a place is searchable by its name (0.134.0) ---

    private val home = Trigger.Location(40.4168, -3.7038, 150, Presence.INSIDE, "Casa", onCrossing = true)
    private val office = Condition.AtPlace(40.45, -3.69, 200, "Oficina Castellana")

    @Test
    fun `a reminder is found by the place it rings at, waits at, or is fenced to`() {
        val reminders = listOf(
            reminder("Sacar la basura").copy(rules = listOf(TriggerRule(home))),
            reminder("Pedir las llaves").copy(rules = listOf(TriggerRule(Trigger.TimeOfDay(java.time.LocalTime.of(9, 0)), listOf(office)))),
            reminder("Devolver el libro").copy(snoozedToPlace = home.copy(label = "Biblioteca")),
            reminder("Comprar pan"),
        )
        assertEquals(listOf("Sacar la basura"), texts(search(reminders, "casa")))
        assertEquals(listOf("Pedir las llaves"), texts(search(reminders, "castellana")), "a word of the name is enough")
        assertEquals(listOf("Devolver el libro"), texts(search(reminders, "biblio")))
    }

    @Test
    fun `the words outrank the place, and a place is never matched by a scatter of its letters`() {
        val reminders = listOf(
            reminder("Sacar la basura").copy(rules = listOf(TriggerRule(home))),
            reminder("Pintar la casa"),
        )
        // Somebody typing on Home is looking for what a reminder says before where it rings.
        assertEquals(listOf("Pintar la casa", "Sacar la basura"), texts(search(reminders, "casa")))
        // "cs" abbreviates words somebody wrote; it does not abbreviate the name of a pin.
        assertEquals(emptyList<String>(), texts(search(listOf(reminders[0].copy(text = "Tirar el vidrio")), "cs")))
    }

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
    fun `letters in order have to start a word, or they are a coincidence`() {
        // A name typed in full used to find this: r inside "prueba", a inside "prueba", m at
        // "mesas", o and n inside "pong". What gives it away is where it starts — nobody
        // abbreviates from the middle of a word — and not how thinly it is spread, because
        // "pan" over "poner la lavadora antes de nada" is spread just as thin and is meant.
        val reminders = listOf(reminder("Prueba al llegar a mesas ping pong club"), reminder("Ping Ramón", id = "r2"))
        assertEquals(listOf("Ping Ramón"), texts(search(reminders, "ramon")))
        assertNull(fuzzyScore("ramon", fold("Prueba al llegar a mesas ping pong club")))
        // Every abbreviation this band exists for starts where a word does, and still answers.
        assertNotNull(fuzzyScore("cmp", "comprar pan"))
        assertNotNull(fuzzyScore("cp", "comprar pan"))
        assertNotNull(fuzzyScore("pan", "poner la nevera"))
        assertNotNull(fuzzyScore("pan", "poner la lavadora antes de nada"))
        // And a run that starts inside a word is not an abbreviation of anything, whatever
        // order its letters are in.
        assertNull(fuzzyScore("ram", "prueba mesas"))
    }

    @Test
    fun `letters in order may dive into the middle of a word once, not twice`() {
        // Reported from the Hechos search: "termo" found "Temporizador 10 minutos" — t, e and r
        // out of "temporizador", m and o out of "minutos". It starts a word, so the rule above let
        // it through; what gives it away is that it goes into the middle of a word twice (the r,
        // the o), which no abbreviation does: they are initials, or one word's skeleton.
        assertNull(fuzzyScore("termo", fold("Temporizador 10 minutos")))
        val reminders = listOf(reminder("Temporizador 10 minutos"), reminder("Llevar el termo", id = "t2"), reminder("Comprar un termómetro", id = "t3"))
        assertEquals(listOf("Llevar el termo", "Comprar un termómetro"), texts(search(reminders, "termo")))
        // What the band is for is untouched: a skeleton goes in once, initials never do — and
        // the best way through counts, not the first: "pan" is poner, antes, nada.
        assertNotNull(fuzzyScore("cmp", "comprar manzanas"))
        assertNotNull(fuzzyScore("tmpo", "tiempo"))
        assertNotNull(fuzzyScore("pan", "poner la lavadora antes de nada"))
        assertNotNull(fuzzyScore("lcp", "llevar la compra"))
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
