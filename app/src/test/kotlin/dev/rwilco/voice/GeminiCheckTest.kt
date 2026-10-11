package dev.rwilco.voice

import dev.rwilco.model.AppSettings
import dev.rwilco.model.GeminiReading
import dev.rwilco.model.SavedPlace
import dev.rwilco.model.geminiSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** "Probar la clave": a sample sentence, asked the way dictation asks, and what each answer means. */
class GeminiCheckTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private val now: Instant = LocalDateTime.of(2026, 8, 27, 15, 0).atZone(zone).toInstant()
    private val settings = AppSettings(savedPlaces = listOf(SavedPlace("Casa", 40.40, -3.70, 120, id = "p-casa")))
    private val reading = GeminiAnswer.Reading(GeminiReading(text = "Comprar pan"))

    @Test
    fun `the sample is asked as a dictation asks, with the key cleaned as it would be sent`() = runBlocking {
        var key = ""
        var sentence = ""
        var instruction = ""
        var schema = JsonObject(emptyMap())
        val result = tryGeminiKey(" AIzaSyA-b_c ​\n", now, zone, settings) { k, s, i, sc ->
            key = k; sentence = s; instruction = i; schema = sc
            reading
        }
        assertEquals(GeminiCheck.WORKS, result)
        assertEquals("AIzaSyA-b_c", key)
        assertEquals(CHECK_SENTENCE, sentence)
        // The person's places, as dictation sends them: a schema that breaks on one breaks here too.
        assertEquals(geminiSchema(listOf("Casa")), schema)
        assertTrue("Casa" in instruction)
    }

    @Test
    fun `a refused key and no answer are told apart`() = runBlocking {
        assertEquals(GeminiCheck.KEY_REFUSED, tryGeminiKey("k", now, zone, settings) { _, _, _, _ -> GeminiAnswer.KeyRefused })
        assertEquals(GeminiCheck.NO_ANSWER, tryGeminiKey("k", now, zone, settings) { _, _, _, _ -> GeminiAnswer.Failed })
    }
}
