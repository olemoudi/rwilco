package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Correcting by touch (0.192.0): what else the recognizer heard, words kept as words, names that sound alike. */
class ReadingsTest {

    private val casa = SavedPlace("Casa", 40.40, -3.70, 120, id = "p-casa")
    private val settings = AppSettings(savedPlaces = listOf(casa))

    @Test
    fun `where the other readings differ, word for word, is what each word is offered`() {
        val chosen = "llamar a Bito mañana"
        val options = wordAlternatives(chosen, listOf("llamar a Vito mañana", "llamar a visto mañana", "llamar a Vito mañana"))
        val bito = chosen.indexOf("Bito").let { it until it + 4 }
        assertEquals(mapOf(bito to listOf("Vito", "visto")), options)
    }

    @Test
    fun `one word heard as two, or two as one, is offered whole`() {
        val chosen = "comprar pan en Mercadona"
        val options = wordAlternatives(chosen, listOf("comprar pan en merca dona", "comprar pan en Mercadona"))
        assertEquals(mapOf(chosen.indexOf("Mercadona").let { it until it + 9 } to listOf("merca dona")), options)
        val split = "comprar pan en merca dona"
        assertEquals(mapOf(split.indexOf("merca").let { it until split.length } to listOf("Mercadona")), wordAlternatives(split, listOf("comprar pan en Mercadona")))
    }

    @Test
    fun `readings that agree offer nothing, and case or a comma is no difference`() {
        assertEquals(emptyMap<IntRange, List<String>>(), wordAlternatives("Comprar pan, mañana", listOf("comprar pan mañana")))
        assertEquals(emptyMap<IntRange, List<String>>(), wordAlternatives("comprar pan", emptyList()))
    }

    @Test
    fun `a when kept as words is not read, and is written without its marks`() {
        val sentence = "llamar a Juan mañana a las 11"
        val kept = literally(sentence, sentence.indexOf("a las 11").let { it until it + 8 })
        assertEquals(sentence, plain(kept))
        val heard = hear(kept, now, zone, settings) as Heard.Read
        assertEquals("Llamar a Juan a las 11", heard.spoken.text)
        assertEquals(listOf(TriggerRule(Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS)))), heard.spoken.rules)
        // Not a doubt either: the person said so.
        assertEquals(emptyList<IntRange>(), heard.doubts)
    }

    @Test
    fun `a clock kept as words is no clock, not even half of one`() {
        val sentence = "revisar la alarma de las 11:30 mañana"
        val kept = literally(sentence, sentence.indexOf("11:30").let { it until it + 5 })
        val heard = hear(kept, now, zone, settings) as Heard.Read
        assertEquals("Revisar la alarma de las 11:30", heard.spoken.text)
    }

    @Test
    fun `a correction kept as words is not repaired again`() {
        val sentence = "comprar el pan, no, el de molde mañana"
        val struck = repaired(listOf(sentence), now, zone, settings)
        assertTrue(struck.dropped.isNotEmpty())
        val kept = literally(sentence, sentence.indexOf("el pan, no,").let { it until it + 11 })
        val again = repaired(listOf(kept), now, zone, settings)
        assertEquals(emptyList<IntRange>(), again.dropped)
        assertEquals(sentence, plain(again.text))
    }

    @Test
    fun `a word tapped as wrong is offered the person's names that sound like it`() {
        val vocabulary = Vocabulary(listOf("Vito", "Vicente", "Mercadona", "Bea"))
        assertEquals(listOf("Vito"), vocabulary.soundingLike("Bito"))
        assertEquals(listOf("Vito"), vocabulary.soundingLike("visto"))
        assertEquals(emptyList<String>(), vocabulary.soundingLike("Vito"))
        assertEquals(emptyList<String>(), vocabulary.soundingLike("pan"))
    }
}
