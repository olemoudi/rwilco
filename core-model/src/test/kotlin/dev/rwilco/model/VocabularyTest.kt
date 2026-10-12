package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** The person's own words (0.189.0): gathered from what they wrote, asked for, chosen and put right. */
class VocabularyTest {

    private fun reminder(text: String, tags: List<String> = emptyList(), contact: Boolean = false) = Reminder(
        id = text, text = text, tags = tags, contactKind = if (contact) ContactKind.PERSONAL else null,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    private val casa = SavedPlace("Casa de Mercedes", 40.40, -3.70, 120, id = "p")

    @Test
    fun `the names in what was written, the people kept in touch with, the tags and the places, most used first`() {
        val vocabulary = vocabularyOf(
            listOf(
                reminder("Llamar a Vito"),
                reminder("Cumpleaños de Vito el sábado"),
                reminder("Comprar pan"),
                reminder("Revisar Duolingo. Llamar al banco"),
                reminder("Belén Ortiz", contact = true),
                reminder("Regar", tags = listOf("jardín")),
            ),
            listOf(casa),
        )
        assertEquals("Vito", vocabulary.words.first())
        assertTrue(vocabulary.words.containsAll(listOf("Duolingo", "Belén", "Ortiz", "jardín", "Casa", "Mercedes")), vocabulary.words.toString())
        // The first word of a sentence is capitalised for being first, not for being a name.
        assertTrue("Comprar" !in vocabulary.words && "Llamar" !in vocabulary.words && "Regar" !in vocabulary.words, vocabulary.words.toString())
        // Too short to be anybody's own: "de", "Al".
        assertTrue(vocabulary.words.none { it.length < 3 }, vocabulary.words.toString())
    }

    @Test
    fun `a word written lower case and then as a name keeps the name's spelling`() {
        val vocabulary = vocabularyOf(listOf(reminder("Ver a Belén"), reminder("Belen", contact = true)), emptyList())
        assertEquals(listOf("Belén"), vocabulary.words)
    }

    @Test
    fun `the recognizer is asked to lean towards the most used, fixes first`() {
        val vocabulary = Vocabulary(listOf("Vito", "Mercadona"), learned = mapOf("a visto" to "Vito", "con lusia" to "Lucía"))
        assertEquals(listOf("Vito", "Lucía", "Mercadona"), vocabulary.bias)
    }

    @Test
    fun `of the readings offered, the one that says the person's own words wins, the first on a tie`() {
        val vocabulary = Vocabulary(listOf("Vito"))
        assertEquals("llamar a Vito", vocabulary.choose(listOf("llamar a bito", "llamar a Vito", "llamar a visto")))
        assertEquals("comprar pan", vocabulary.choose(listOf("comprar pan", "comprar van")))
        assertNull(vocabulary.choose(emptyList()))
    }

    @Test
    fun `a name the recognizer guessed at is written as the person's own when it sounds the same`() {
        val vocabulary = Vocabulary(listOf("Vito", "Cecilia", "Belén", "Quique", "Baca"))
        assertEquals("Llamar a Vito mañana", vocabulary.corrected("Llamar a Bito mañana"))
        assertEquals("Ver a Cecilia", vocabulary.corrected("Ver a Sesilia"))
        assertEquals("Café con Belén", vocabulary.corrected("Café con Belen"))
        assertEquals("Cena con Quique", vocabulary.corrected("Cena con Kike"))
        // Never an ordinary word in lower case, nor the first word, capitalised only for being first.
        assertEquals("Comprar leche de vaca", vocabulary.corrected("Comprar leche de vaca"))
        assertEquals("Bito llamar", vocabulary.corrected("Bito llamar"))
        // A name nobody here has stays as heard.
        assertEquals("Llamar a Marta", vocabulary.corrected("Llamar a Marta"))
    }

    @Test
    fun `what was put right by hand is put right the same way again, in the same place`() {
        val vocabulary = Vocabulary(emptyList(), learned = mapOf("a visto" to "Vito", "bito" to "Vito"))
        assertEquals("llamar a Vito", vocabulary.corrected("llamar a visto"))
        assertEquals("Vito llamará", vocabulary.corrected("bito llamará"))
        // Anywhere else the word is its own: "he visto" is still seen.
        assertEquals("ya he visto la película", vocabulary.corrected("ya he visto la película"))
        // And the fixed word is one of the person's own from then on.
        assertEquals("Café con Vito", Vocabulary(emptyList(), learned = mapOf("a visto" to "Vito")).choose(listOf("Café con visto", "Café con Vito")))
    }

    @Test
    fun `what was put right by hand is learned word for word, names only`() {
        assertEquals(mapOf("a visto" to "Vito"), fixesBetween("llamar a visto mañana", "llamar a Vito mañana"))
        assertEquals(mapOf("bito" to "Vito"), fixesBetween("bito llamará mañana", "Vito llamará mañana"))
        assertEquals(mapOf("con lucia" to "Lucía", "lucia garci" to "García"), fixesBetween("café con lucia garci", "café con Lucía García"))
        // Words added or taken away, or an ordinary word changed, teach nothing.
        assertEquals(emptyMap<String, String>(), fixesBetween("llamar a juan", "llamar a juan mañana"))
        assertEquals(emptyMap<String, String>(), fixesBetween("ir a casa", "ir al casa"))
        assertEquals(emptyMap<String, String>(), fixesBetween("comprar pan", "comprar pan"))
        assertEquals(emptyMap<String, String>(), fixesBetween("llamar a visto", "escribir un correo largo a Vito sobre la cena"))
        // The same word written as a name now is a name to write so.
        assertEquals(mapOf("a belen" to "Belén"), fixesBetween("llamar a belen", "llamar a Belén"))
    }

    @Test
    fun `nothing known changes nothing`() {
        assertEquals("Llamar a Bito", Vocabulary(emptyList()).corrected("Llamar a Bito"))
    }

    @Test
    fun `words that are said the same sound the same`() {
        assertEquals(soundOf("Vito"), soundOf("Bito"))
        assertEquals(soundOf("Cecilia"), soundOf("Sesilia"))
        assertEquals(soundOf("Quique"), soundOf("Kike"))
        assertEquals(soundOf("Hugo"), soundOf("Ugo"))
        assertEquals(soundOf("Yolanda"), soundOf("Llolanda"))
        assertEquals(soundOf("Jorge"), soundOf("Jorje"))
        assertNotEquals(soundOf("Vito"), soundOf("Tito"))
        assertNotEquals(soundOf("Marta"), soundOf("Martín"))
    }
}
