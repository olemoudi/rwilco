package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * A dictation said calmly (0.187.0): corrections, starts said again after a pause, hesitations
 * and words said twice — and the sentences that only look like one and must be left alone. The
 * clock is [Fixtures.now], Thursday 27 August 2026 at 15:00 in Madrid.
 */
class SpokenRepairTest {

    private val casa = SavedPlace("Casa", 40.40, -3.70, 120, id = "p-casa")
    private val trabajo = SavedPlace("Trabajo", 40.41, -3.69, 150, id = "p-trabajo")
    private val settings = AppSettings(savedPlaces = listOf(casa, trabajo))

    private fun repair(vararg stretches: String) = repaired(stretches.toList(), now, zone, settings)

    private fun meant(vararg stretches: String) = repair(*stretches).text

    @Test
    fun `a correction takes the place of what it corrects`() {
        val cases = mapOf(
            listOf("llamar a Juan mañana a las cinco no a las seis") to "llamar a Juan mañana a las seis",
            listOf("llamar a Juan mañana a las cinco, no, a las seis") to "llamar a Juan mañana a las seis",
            listOf("llamar a Juan mañana a las cinco", "no, a las seis") to "llamar a Juan mañana a las seis",
            listOf("llamar a Juan mañana a las cinco, mejor a las seis") to "llamar a Juan mañana a las seis",
            listOf("llamar a Juan mañana a las cinco, no, mejor a las seis") to "llamar a Juan mañana a las seis",
            listOf("llamar a Juan mañana, no, pasado mañana") to "llamar a Juan pasado mañana",
            listOf("llamar a Juan mañana no pasado mañana") to "llamar a Juan pasado mañana",
            listOf("comprar pan el martes, digo, el miércoles") to "comprar pan el miércoles",
            listOf("comprar pan al llegar a casa no al salir del trabajo") to "comprar pan al salir del trabajo",
            listOf("comprar pan al llegar a casa, perdón, al trabajo") to "comprar pan al llegar al trabajo",
            listOf("comprar leche, no, comprar pan cuando llegue a casa") to "comprar pan cuando llegue a casa",
            listOf("comprar leche no comprar pan cuando llegue a casa") to "comprar pan cuando llegue a casa",
            listOf("llamar a Ana, digo, a Marta, mañana") to "llamar a Marta, mañana",
            listOf("comprar leche", "perdón, pan mañana") to "comprar pan mañana",
            listOf("call John tomorrow at five, no, at six") to "call John tomorrow at six",
            listOf("call John tomorrow, I mean, on Friday") to "call John on Friday",
            listOf("buy milk", "sorry, bread") to "buy bread",
        )
        for ((stretches, expected) in cases) assertEquals(expected, meant(*stretches.toTypedArray()), stretches.toString())
    }

    @Test
    fun `a correction of who or what keeps the when said before it`() {
        assertEquals("llamar mañana a las cinco, a Pedro", meant("llamar a Juan mañana a las cinco, no, a Pedro"))
        assertEquals("mañana, comprar leche desnatada", meant("comprar leche mañana, no, comprar leche desnatada"))
    }

    @Test
    fun `several corrections in a row each take the place of the one before`() {
        assertEquals("a las siete llamar a Juan", meant("a las cinco no a las seis no a las siete llamar a Juan"))
    }

    @Test
    fun `a start said again after a pause takes the place of the first try`() {
        assertEquals("comprar pan y leche mañana", meant("comprar pan", "comprar pan y leche mañana"))
        assertEquals("recuérdame comprar pan mañana", meant("recuérdame comprar", "recuérdame comprar pan mañana"))
        assertEquals("a las seis llamar a Juan", meant("a las cinco", "a las seis llamar a Juan"))
        assertEquals("recordar el cumpleaños de Ana el día 15", meant("recordar el cumpleaños de Ana el día", "el día 15"))
        // The "when" of the first try stays when the second says none.
        assertEquals("mañana comprar pan y leche", meant("comprar pan mañana", "comprar pan y leche"))
    }

    @Test
    fun `hesitations and words said twice go`() {
        assertEquals("comprar pan mañana", meant("eh comprar pan mmm mañana"))
        assertEquals("el martes llamar a Juan", meant("el el martes llamar a Juan"))
        assertEquals("comprar pan y leche", meant("comprar pan comprar pan y leche"))
        assertEquals("comprar pan mañana", meant("bueno, comprar pan", "mañana").replace(",", ""))
        assertEquals("recuérdame comprar pan", meant("recuérdame", "recuérdame comprar pan"))
        assertEquals("no olvidar las llaves", meant("no, no olvidar las llaves"))
    }

    @Test
    fun `what only looks like a correction is left as said`() {
        val sentences = listOf(
            "no olvidar las llaves mañana",
            "comprar pan si no llueve mañana",
            "que no se me olvide comprar pan mañana",
            "llamar al 600 600 600 mañana",
            "pedir perdón a Ana mañana",
            "recuérdame pedirle perdón a mamá al llegar a casa",
            "comprar el pan no el de molde mañana",
            "decirle a Juan lo que digo siempre mañana",
            "ir a la sala de espera mañana",
            "comprar el mejor vino mañana",
            "es mejor llamar mañana",
            "llamar a Juan no a Pedro",
            "tomar la pastilla a las 9 y a las 21",
            "comprar pan y leche al llegar a casa y llevar 10 minutos allí",
            "a las cinco no",
        )
        for (sentence in sentences) assertEquals(sentence, meant(sentence))
        // Said in stretches, nothing said again: joined and nothing more.
        assertEquals("comprar pan al llegar a casa y llevar 10 minutos allí", meant("comprar pan al llegar a casa", "y llevar 10 minutos allí"))
        assertEquals("llamar a Juan a las cinco", meant("llamar a Juan", "a las cinco"))
        assertEquals("comprar pan y leche y huevos mañana", meant("comprar pan y leche", "y huevos mañana"))
    }

    @Test
    fun `what was taken out is where it was heard`() {
        val repair = repair("llamar a Juan mañana a las cinco,", "no, a las seis")
        assertEquals("llamar a Juan mañana a las cinco, no, a las seis", repair.heard)
        assertEquals(listOf("a las cinco, no,"), repair.dropped.map { repair.heard.substring(it) })
        val six = repair.text.indexOf("a las seis")
        assertEquals("a las seis", repair.heard.substring(repair.inHeard(six until six + "a las seis".length)))
        val juan = repair.text.indexOf("Juan mañana")
        assertEquals("Juan mañana", repair.heard.substring(repair.inHeard(juan until juan + "Juan mañana".length)))
    }

    @Test
    fun `a correction kept apart from its when shows as two runs`() {
        val repair = repair("llamar a Juan mañana a las cinco, no, a Pedro")
        assertEquals(listOf("a Juan", "no,"), repair.dropped.map { repair.heard.substring(it) })
    }

    @Test
    fun `nothing to repair is the stretches joined`() {
        val repair = repair(" comprar pan ", "", "mañana")
        assertEquals("comprar pan mañana", repair.heard)
        assertEquals("comprar pan mañana", repair.text)
        assertEquals(emptyList<IntRange>(), repair.dropped)
        assertEquals(0..5, repair.inHeard(0..5))
    }

    @Test
    fun `the repaired sentence is the one that is read`() {
        val heard = hear(meant("llamar a Juan mañana a las cinco", "no, a las seis"), now, zone, settings) as Heard.Read
        assertEquals("Llamar a Juan", heard.spoken.text)
        assertEquals(listOf(TriggerRule(Trigger.RelativeDate(RelativeDay.In(1, RelativeUnit.DAYS), java.time.LocalTime.of(18, 0)))), heard.spoken.rules)
        val place = hear(meant("comprar pan al llegar a casa, no, al salir del trabajo"), now, zone, settings) as Heard.Read
        assertEquals(Presence.OUTSIDE, (place.spoken.rules.single().trigger as Trigger.Location).presence)
        assertEquals(LocalDateTime.of(2026, 8, 27, 15, 0), now.atZone(zone).toLocalDateTime())
    }
}
