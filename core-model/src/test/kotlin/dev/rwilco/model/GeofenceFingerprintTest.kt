package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class GeofenceFingerprintTest {

    private val home = "r1#0@40.50074,-3.66413,150,E"
    private val office = "r2#1@40.43000,-3.68000,50,X"

    @Test
    fun `the same fences in any order are the same registration`() {
        assertEquals(geofenceFingerprint(listOf(home, office), true), geofenceFingerprint(listOf(office, home), true))
        assertEquals(geofenceFingerprint(emptyList(), true), geofenceFingerprint(emptyList(), true))
    }

    @Test
    fun `a fence added, a fence gone, or the grant taken away is a different one`() {
        val both = geofenceFingerprint(listOf(home, office), true)
        assertNotEquals(both, geofenceFingerprint(listOf(home), true), "one fewer")
        assertNotEquals(both, geofenceFingerprint(listOf(home, office, "r3#0@1,1,50,E"), true), "one more")
        assertNotEquals(both, geofenceFingerprint(listOf(home, office), false), "same fences, no grant")
        // A moved pin is a new id, so it needs no special case here.
        assertNotEquals(both, geofenceFingerprint(listOf(home.replace("150", "200"), office), true), "a moved circle")
    }

    @Test
    fun `two ids that concatenate the same are still two ids`() {
        assertNotEquals(geofenceFingerprint(listOf("ab", "c"), true), geofenceFingerprint(listOf("a", "bc"), true))
    }
}
