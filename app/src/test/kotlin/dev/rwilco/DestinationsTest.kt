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
    fun `a shared page keeps its title, which a browser sends beside the link and not in it`() {
        fun shared(text: String?, subject: String?) =
            Destinations.sharedTextIn(Destinations.of("android.intent.action.SEND", "text/plain", null, text, subject = subject))

        // A browser's share: the link alone in the text, the page's name in the subject. The
        // reminder used to be a bare URL, which says nothing on a card three days later.
        assertEquals("Receta de torrijas — https://example.com/torrijas", shared("https://example.com/torrijas", "Receta de torrijas"))
        assertEquals("Horarios — www.renfe.com/horarios", shared(" www.renfe.com/horarios ", "  Horarios  "))
        // Words of somebody's own are the reminder: a subject is somebody else's heading for them.
        assertEquals("Mira esto https://example.com", shared("Mira esto https://example.com", "Compartido"))
        assertEquals("Comprar filtros", shared("Comprar filtros", "Nota"))
        // Nothing to add is nothing added, and a title that is only the link again is not one.
        assertEquals("https://example.com", shared("https://example.com", null))
        assertEquals("https://example.com", shared("https://example.com", "   "))
        assertEquals("https://example.com", shared("https://example.com", "https://example.com"))
    }

    @Test
    fun `a long title gives way so that the link survives whole`() {
        val link = "https://example.com/" + "a".repeat(60)
        val words = Destinations.sharedTextIn(Destinations.of("android.intent.action.SEND", "text/plain", null, link, subject = "T".repeat(900)))!!
        assertEquals(Destinations.MAX_SHARED_LENGTH, words.length)
        assertEquals(true, words.endsWith(" — $link"), "the title is what is cut, never the link")
        // A link that is itself longer than a reminder holds is left alone: there is no room.
        val huge = "https://example.com/" + "b".repeat(600)
        assertEquals(huge, Destinations.sharedTextIn(Destinations.of("android.intent.action.SEND", "text/plain", null, huge, subject = "Título")))
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
