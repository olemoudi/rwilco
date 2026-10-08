package dev.rwilco.geo

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * When the places are put back without anybody asking: location switched off drops every fence,
 * the re-registration that follows fails with it still off, and nothing used to try again until
 * the six-hourly worker. A look that got a fix, or somebody opening the app, now asks.
 */
class GeofenceRetryTest {

    @Test
    fun `fences left out by the last attempt are put back once location is on again`() {
        assertTrue(fencesWorthRetrying(registered = null, permitted = true, locationOn = true, lastTriedAt = null, now = now))
        assertTrue(fencesWorthRetrying(registered = null, permitted = true, locationOn = true, lastTriedAt = now - RETRY_FENCES_EVERY, now = now))
    }

    /**
     * Play Services can refuse with location on (Google's location accuracy switched off says
     * the same "not available"), and a look comes every few minutes near a place: each one was a
     * full remove-and-add, and the fences that were in went out for the length of it.
     */
    @Test
    fun `an attempt that failed with location on is not made again at every look`() {
        assertFalse(fencesWorthRetrying(registered = null, permitted = true, locationOn = true, lastTriedAt = now.minusSeconds(120), now = now))
    }

    @Test
    fun `nothing is asked while it would only fail again, or when nothing was lost`() {
        assertFalse(fencesWorthRetrying(registered = null, permitted = true, locationOn = false, lastTriedAt = null, now = now), "location still off")
        assertFalse(fencesWorthRetrying(registered = null, permitted = false, locationOn = true, lastTriedAt = null, now = now), "not allowed in the background")
        assertFalse(fencesWorthRetrying(registered = "fp", permitted = true, locationOn = true, lastTriedAt = null, now = now), "the fences are in")
    }

    private val now = Instant.parse("2026-10-08T18:00:00Z")
}
