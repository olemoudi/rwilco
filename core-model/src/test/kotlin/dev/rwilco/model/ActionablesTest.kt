package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What in a reminder's words can be acted on: a number to ring, a link to open.
 *
 * The refusals matter more than the finds. A row offering to "call 270820261030" is the app
 * reading a date as a telephone, and one of those costs the trust in every row after it — the
 * same bargain the words-reading chip makes (`WhenInTextTest`): a false silence costs a tap, a
 * false offer costs belief.
 */
class ActionablesTest {

    private fun phones(text: String) = actionablesIn(text).filterIsInstance<Actionable.Phone>().map { it.dial }
    private fun links(text: String) = actionablesIn(text).filterIsInstance<Actionable.Link>().map { it.url }

    @Test
    fun `a telephone is found however it was typed, and handed over as a dialer wants it`() {
        val table = listOf(
            "Llamar al dentista 912345678" to "912345678",
            "Llamar a Marta: 612 345 678" to "612345678",
            "fontanero 612-345-678, por la mañana" to "612345678",
            "Taller 91 234 56 78" to "912345678",
            "Ana +34 612 345 678" to "+34612345678",
            "Hotel 0034 912 345 678." to "0034912345678",
            "gestoría (612.345.678)" to "612345678",
        )
        for ((text, dial) in table) assertEquals(listOf(dial), phones(text), text)
        // What the row says is what was written, not what the dialer is handed.
        assertEquals("612 345 678", (actionablesIn("Llamar a Marta: 612 345 678").single() as Actionable.Phone).raw)
    }

    @Test
    fun `numbers that are not telephones are left alone`() {
        val notPhones = listOf(
            "Comprar las 3 bolsas",
            "piso 3 puerta 4",
            "son 12.50 €",
            "Cita el 27.08.2026 10.30",
            "reunión 26/8 a las 17:30",
            "del 01/09/2026 al 15/09/2026",
            "comprar 600 g de harina y 2 kilos de azúcar",
            "pagar 1200 euros",
            "tarjeta 4111 1111 1111 1111",
            "IBAN ES12 3456 7890 1234 5678 9012",
            "ref A123456789B",
            "",
        )
        for (text in notPhones) assertEquals(emptyList<String>(), phones(text), text)
    }

    @Test
    fun `a link is found with or without its scheme, and loses the full stop after it`() {
        assertEquals(listOf("https://example.com/torrijas"), links("Receta — https://example.com/torrijas"))
        assertEquals(listOf("https://www.renfe.com/horarios"), links("mirar www.renfe.com/horarios."))
        assertEquals(listOf("http://192.168.1.1/admin"), links("router http://192.168.1.1/admin, clave en la pegatina"))
        assertEquals(listOf("https://example.com/a?b=1&c=2"), links("(ver https://example.com/a?b=1&c=2)"))
        val link = actionablesIn("mirar www.renfe.com/horarios.").single() as Actionable.Link
        assertEquals("renfe.com", link.host)
        assertEquals("www.renfe.com/horarios", link.raw)
    }

    @Test
    fun `only the web is opened, and a number inside a link is not a telephone`() {
        // The words may have arrived from another app's share: nothing but http and https.
        assertEquals(emptyList<String>(), links("intent://evil#Intent;end"))
        assertEquals(emptyList<String>(), links("file:///sdcard/secret.txt"))
        assertEquals(emptyList<String>(), links("javascript:alert(1)"))
        assertEquals(emptyList<Actionable>(), actionablesIn("sin nada que tocar"))
        assertEquals(emptyList<String>(), phones("pedido https://tienda.example/pedidos/612345678"))
    }

    @Test
    fun `they come in the order they were written, once each, and only a handful`() {
        val found = actionablesIn("Llamar 612345678 o mirar www.example.com y si no 612 345 678 o 911222333")
        assertEquals(listOf("612345678", "https://www.example.com", "911222333"), found.map { if (it is Actionable.Phone) it.dial else (it as Actionable.Link).url })
        assertEquals(2, actionablesIn("a 612345678 b 911222333 c 933444555", limit = 2).size)
    }
}
