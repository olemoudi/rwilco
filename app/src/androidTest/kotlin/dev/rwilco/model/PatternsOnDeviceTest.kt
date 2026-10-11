package dev.rwilco.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Every pattern the app reads words with, compiled **on the phone**.
 *
 * Android's `java.util.regex` is ICU's engine, not the JVM's, and it is stricter in places the JVM
 * never complains about: `[\d.,:]` reads as an unterminated POSIX property there, threw at class
 * initialisation and took every use of the file down with it — a `NoClassDefFoundError` while the
 * alert screen was being drawn — with a fully green JVM suite behind it (0.139.0).
 *
 * So each object that holds patterns is touched once here, through the entry point that forces its
 * initialisation. What the answers *are* is the JVM tests' business; what this asks is only that
 * asking does not throw.
 */
@RunWith(AndroidJUnit4::class)
class PatternsOnDeviceTest {

    private val zone: ZoneId = ZoneId.of("Europe/Madrid")
    private val now: Instant = LocalDateTime.of(2026, 8, 27, 15, 0).atZone(zone).toInstant()

    @Test
    fun theWordsInAReminderAreReadWithoutThrowing() {
        assertNotNull("the phone could not read the commonest sentence there is", whenInText("llamar a Marta mañana por la tarde", now, zone))
        // The shapes that exercise the other branches: a part of today, an hour, a date, a
        // weekday, a recurrence, English, and the guard that keeps "esta mañana" from being one.
        for (text in listOf("esta noche", "el lunes a las 8", "27/08 a las 21:30", "cada día a las 9", "in 3 days", "pasado mañana", "enviar la propuesta mañana")) {
            whenInText(text, now, zone)
        }
        // The stretches of the calendar and the ways of saying where (0.175.0), each family once.
        val casa = SavedPlace("Casa", 40.4, -3.7, 120, id = "p")
        assertNotNull("a saved place was not found by name on the phone", whenInText("comprar pan cuando llegue a casa", now, zone, places = listOf(casa)))
        for (text in listOf("esta semana", "el finde que viene", "este mes", "en algún momento de hoy", "al salir de casa", "mientras no esté en casa", "estando fuera de casa", "while I'm at casa", "when I leave casa")) {
            whenInText(text, now, zone, places = listOf(casa))
        }
        // A sentence said out loud (0.176.0): the lead-ins, "aquí", the routines and the check of
        // what is left all hold patterns of their own.
        val settings = AppSettings(savedPlaces = listOf(casa))
        assertNotNull("a dictated sentence was not read on the phone", hear("recuérdame comprar pan cuando llegue a casa", now, zone, settings))
        for (text in listOf("al salir de aquí regar", "rutina: mover el coche cada 3 semanas", "regar cada 5 días desde la última vez", "mañana cuando llegue al curro")) {
            hear(text, now, zone, settings)
        }
        // And the compound grammar (0.177.0): joins, stretches, rates, speeds, deadlines, chance, actions.
        for (text in listOf(
            "regar al llegar a casa y sólo si es entre las 18 y las 22", "a las 9 o al salir de casa, lo que pase antes",
            "regar cuando esté en casa y, a la vez, sea fin de semana", "al llevar 10 minutos en casa", "al salir de casa si voy en coche",
            "regar antes de las 22 y si en 2 horas no, olvídalo", "beber agua tres veces al día", "del 1 al 15 de octubre", "hasta el viernes",
            "despertar mañana a las 7 con pantalla completa, que suene hasta que conteste", "when I have been at casa for 10 min",
        )) {
            hear(text, now, zone, settings)
        }
        // What dictation learned in 0.181.0: fractions, approximate hours, routines, months, series.
        for (text in listOf(
            "en hora y media", "a las 9 menos cuarto", "sobre las 5", "a medianoche", "quarter past 7", "mañana temprano", "el lunes 14",
            "a final de mes", "en septiembre", "a finales de septiembre", "el primer lunes de cada mes", "el día 1 de cada mes",
            "de lunes a viernes a las 8", "entre semana a las 8", "todas las noches", "al levantarme", "antes de acostarme",
            "cuando vaya a casa", "la próxima vez que esté en casa", "que no se me olvide regar hoy", "twice a day",
        )) {
            hear(text, now, zone, settings)
        }
        // Gemini's half (0.178.0): the schema built and an answer read back, on the phone's JSON.
        assertNotNull(geminiSchema(settings.geminiPlaces))
        assertNotNull(geminiJson.decodeFromString(GeminiReading.serializer(), """{"text":"x","match":"ANY","rules":[{"event":{"kind":"ARRIVE","place":"Casa"}}]}""").toHeard(now, zone, settings, null, "aquí", "x"))
    }

    @Test
    fun theNumbersAndLinksInAReminderAreFoundWithoutThrowing() {
        val found = actionablesIn("Llamar al 91.234.56.78 o mirar https://example.com/a_(b), el 27.08.2026 a las 10.30")
        assertEquals(
            listOf("912345678", "example.com"),
            found.map {
                when (it) {
                    is Actionable.Phone -> it.dial
                    is Actionable.Link -> it.host
                }
            },
        )
    }

    @Test
    fun theSearchFoldsAndScoresWithoutThrowing() {
        assertEquals("manana", fold("Mañana"))
        assertNotNull(fuzzyScore("cmp", fold("comprar pan")))
    }
}
