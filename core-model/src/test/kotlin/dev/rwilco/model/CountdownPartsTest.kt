package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class CountdownPartsTest {

    @Test
    fun `parts split a duration and know when it is under an hour`() {
        val parts = partsBetween(now, now.plus(Duration.ofDays(3).plusHours(4).plusMinutes(5).plusSeconds(6)))
        assertEquals(CountdownParts(3, 4, 5, 6, overdue = false), parts)
        assertEquals(3 * 24 * 60 + 4 * 60 + 5L, parts.totalMinutes)
        assertFalse(parts.underAnHour)
        assertTrue(partsBetween(now, now.plus(Duration.ofMinutes(59).plusSeconds(59))).underAnHour)
        assertFalse(partsBetween(now, now.plus(Duration.ofMinutes(60))).underAnHour)
    }

    @Test
    fun `a moment behind us is overdue with positive parts`() {
        val parts = partsBetween(now, now.minus(Duration.ofMinutes(5)))
        assertTrue(parts.overdue)
        assertEquals(5, parts.minutes)
        assertEquals(0, parts.seconds)
    }
}
