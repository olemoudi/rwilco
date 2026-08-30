package dev.rwilco

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DestinationsTest {

    @Test
    fun `a notification's own extra wins over everything`() {
        assertEquals("settings", Destinations.of("android.intent.action.SEND", "text/plain", "settings", "hola"))
    }

    @Test
    fun `the launcher shortcut lands on a blank reminder`() {
        assertEquals(Destinations.NEW, Destinations.of(Destinations.ACTION_NEW, null, null, null))
        assertNull(Destinations.sharedTextIn(Destinations.NEW))
    }

    @Test
    fun `a shared line becomes the words of a new reminder, trimmed`() {
        val destination = Destinations.of("android.intent.action.SEND", "text/plain", null, "  Comprar filtros \n")
        assertEquals("Comprar filtros", Destinations.sharedTextIn(destination))
    }

    @Test
    fun `a preset shortcut names its preset, and nothing without one`() {
        val destination = Destinations.of(Destinations.ACTION_PRESET, null, null, null, presetId = "p1")
        assertEquals("p1", Destinations.presetIdIn(destination))
        assertNull(Destinations.sharedTextIn(destination))
        assertNull(Destinations.of(Destinations.ACTION_PRESET, null, null, null, presetId = " "))
        assertNull(Destinations.presetIdIn(Destinations.NEW))
    }

    @Test
    fun `anything else is nowhere in particular`() {
        assertNull(Destinations.of("android.intent.action.MAIN", null, null, null))
        assertNull(Destinations.of("android.intent.action.SEND", "image/png", null, null))
        assertNull(Destinations.of("android.intent.action.SEND", "text/plain", null, "   "))
        assertNull(Destinations.sharedTextIn("reminder:abc"))
        assertNull(Destinations.sharedTextIn(null))
    }
}
