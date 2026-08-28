package dev.rwilco.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

class SystemZoneClockTest {

    @Test
    fun `the zone is read when it is asked for, not when the clock was built`() {
        val before = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
            val live = SystemZoneClock()
            val frozen = Clock.systemDefaultZone()
            assertEquals(ZoneId.of("Europe/Madrid"), live.zone)
            // The phone lands in Tokyo.
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals(ZoneId.of("Asia/Tokyo"), live.zone, "the live clock follows the phone")
            assertEquals(ZoneId.of("Europe/Madrid"), frozen.zone, "which is what the system's own clock does not do")
            // And back.
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
            assertEquals(ZoneId.of("Europe/Madrid"), live.zone)
        } finally {
            TimeZone.setDefault(before)
        }
    }

    @Test
    fun `it tells the time, and a zone asked for by name is that zone`() {
        val clock = SystemZoneClock()
        assertTrue(Duration.between(Instant.now(), clock.instant()).abs() < Duration.ofSeconds(5))
        assertTrue(Math.abs(System.currentTimeMillis() - clock.millis()) < 5_000)
        assertEquals(ZoneId.of("America/Mexico_City"), clock.withZone(ZoneId.of("America/Mexico_City")).zone)
    }
}
