package dev.rwilco.voice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

/** The ear warmed while the finger holds the microphone, and handed to the sheet as it is (0.209.0). */
class EarlyEarTest {

    /** A recognizer that says what it is told to, and counts how it was asked. */
    private class FakeInner : Listener {
        var starts = 0
        var stops = 0
        var cancels = 0
        var onEvent: ((VoiceEvent) -> Unit)? = null
        override fun start(locale: Locale, words: List<String>, onEvent: (VoiceEvent) -> Unit) {
            starts++
            this.onEvent = onEvent
        }
        override fun stop() {
            stops++
        }
        override fun cancel() {
            cancels++
            onEvent = null
        }
        fun say(event: VoiceEvent) = onEvent?.invoke(event)
    }

    private val locale = Locale.forLanguageTag("es-ES")

    @Test
    fun `what a warmed ear heard before the sheet took it is handed over, in order, and the rest goes straight on`() {
        val inner = FakeInner()
        val ear = EarlyEar { inner }
        ear.warm(locale, emptyList())
        inner.say(VoiceEvent.Ready)
        inner.say(VoiceEvent.Level(2f))
        inner.say(VoiceEvent.Partial("comprar"))
        inner.say(VoiceEvent.Level(5f))
        assertTrue(ear.warm)

        val heard = mutableListOf<VoiceEvent>()
        ear.start(locale, emptyList()) { heard += it }
        // Taken, not started a second time: the recognizer already open stays open.
        assertEquals(1, inner.starts)
        assertFalse(ear.warm)
        // Only the latest level: how loud it was before anybody could see is not news.
        assertEquals(listOf(VoiceEvent.Ready, VoiceEvent.Partial("comprar"), VoiceEvent.Level(5f)), heard)

        inner.say(VoiceEvent.Partial("comprar pan"))
        assertEquals(VoiceEvent.Partial("comprar pan"), heard.last())
    }

    @Test
    fun `an ear nobody warmed starts when the sheet asks, as before`() {
        val inner = FakeInner()
        val ear = EarlyEar { inner }
        val heard = mutableListOf<VoiceEvent>()
        ear.start(locale, emptyList()) { heard += it }
        assertEquals(1, inner.starts)
        inner.say(VoiceEvent.Ready)
        assertEquals(listOf<VoiceEvent>(VoiceEvent.Ready), heard)
    }

    @Test
    fun `a warmed ear nobody took is let go of, and one the sheet took is left alone`() {
        val inner = FakeInner()
        val ear = EarlyEar { inner }
        ear.warm(locale, emptyList())
        ear.cool()
        assertEquals(1, inner.cancels)
        assertFalse(ear.warm)

        ear.warm(locale, emptyList())
        ear.start(locale, emptyList()) {}
        ear.cool()
        // The sheet's ear: a late "let go" from the button is not the sheet's to hear.
        assertEquals(1, inner.cancels)
        ear.stop()
        assertEquals(1, inner.stops)
    }

    @Test
    fun `warming twice opens one recognizer, and cooling a cold ear does nothing`() {
        val inner = FakeInner()
        val ear = EarlyEar { inner }
        ear.cool()
        assertEquals(0, inner.cancels)
        ear.warm(locale, emptyList())
        ear.warm(locale, emptyList())
        assertEquals(1, inner.starts)
    }

    @Test
    fun `after a cancel nothing kept from before reaches the next listening`() {
        val inner = FakeInner()
        val ear = EarlyEar { inner }
        ear.warm(locale, emptyList())
        inner.say(VoiceEvent.Partial("lo de antes"))
        ear.cancel()

        val heard = mutableListOf<VoiceEvent>()
        ear.start(locale, emptyList()) { heard += it }
        assertEquals(2, inner.starts)
        inner.say(VoiceEvent.Ready)
        assertEquals(listOf<VoiceEvent>(VoiceEvent.Ready), heard)
        // And "Terminado" on an ear that is no longer listening asks nothing of the recognizer.
        ear.cancel()
        ear.stop()
        assertEquals(0, inner.stops)
    }

    @Test
    fun `the recognizer is made at first use, not before`() {
        var made = 0
        val ear = EarlyEar { made++; FakeInner() }
        assertEquals(0, made)
        ear.cool()
        ear.cancel()
        assertEquals(0, made)
        ear.warm(locale, emptyList())
        ear.cancel()
        ear.start(locale, emptyList()) {}
        assertEquals(1, made)
    }
}
