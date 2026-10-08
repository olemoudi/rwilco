package dev.rwilco.geo

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When the places are put back without anybody asking: location switched off drops every fence,
 * the re-registration that follows fails with it still off, and nothing used to try again until
 * the six-hourly worker. A look that got a fix, or somebody opening the app, now asks.
 */
class GeofenceRetryTest {

    @Test
    fun `fences left out by the last attempt are put back once location is on again`() {
        assertTrue(fencesWorthRetrying(registered = null, permitted = true, locationOn = true))
    }

    @Test
    fun `nothing is asked while it would only fail again, or when nothing was lost`() {
        assertFalse(fencesWorthRetrying(registered = null, permitted = true, locationOn = false), "location still off")
        assertFalse(fencesWorthRetrying(registered = null, permitted = false, locationOn = true), "not allowed in the background")
        assertFalse(fencesWorthRetrying(registered = "fp", permitted = true, locationOn = true), "the fences are in")
    }
}
