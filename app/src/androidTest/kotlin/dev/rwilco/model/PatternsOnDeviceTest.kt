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
