package dev.rwilco.geo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GeofenceIdsTest {

    private val uuid = "3f2a9c1e-7b4d-4f0a-9c2e-6d5b8a1f0c33"

    @Test
    fun `a geofence id carries the reminder and which of its places it is`() {
        val id = GeofenceIds.encode(uuid, 2)
        assertEquals("$uuid#2", id)
        assertEquals(uuid, GeofenceIds.reminderIdOf(id))
        assertEquals(2, GeofenceIds.triggerIndexOf(id))
    }

    @Test
    fun `an id from somewhere else does not take a reminder down with it`() {
        assertEquals("stray", GeofenceIds.reminderIdOf("stray"))
        assertNull(GeofenceIds.triggerIndexOf("stray"))
        assertNull(GeofenceIds.triggerIndexOf("$uuid#x"))
    }
}
