package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GeofenceIdsTest {

    private val uuid = "3f2a9c1e-7b4d-4f0a-9c2e-6d5b8a1f0c33"
    private val home = Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa")

    @Test
    fun `a geofence id carries the reminder, which of its places it is, and the circle itself`() {
        val id = GeofenceIds.encode(uuid, 2, home)
        assertEquals("$uuid#2@40.41690,-3.70350,200,E", id)
        assertEquals(uuid, GeofenceIds.reminderIdOf(id))
        assertEquals(2, GeofenceIds.triggerIndexOf(id))
    }

    @Test
    fun `the same rule index with a different circle is a different id`() {
        // Which is what stops an edited list of rules handing one circle another's memory.
        val moved = GeofenceIds.encode(uuid, 0, home.copy(lat = 40.4500))
        val wider = GeofenceIds.encode(uuid, 0, home.copy(radiusM = 300))
        val leaving = GeofenceIds.encode(uuid, 0, home.copy(presence = Presence.OUTSIDE))
        val same = GeofenceIds.encode(uuid, 0, home.copy(label = "Home"))
        assertEquals(GeofenceIds.encode(uuid, 0, home), same, "the label is not the circle")
        assertEquals(3, setOf(moved, wider, leaving).size)
        assertNotEquals(GeofenceIds.encode(uuid, 0, home), moved)
    }

    @Test
    fun `a condition's circle is never a trigger`() {
        val id = GeofenceIds.encodeCondition(uuid, 1, 0, Condition.AtPlace(40.4169, -3.7035, 200, "Casa", inside = false))
        assertEquals(uuid, GeofenceIds.reminderIdOf(id))
        assertNull(GeofenceIds.triggerIndexOf(id))
    }

    @Test
    fun `an id from somewhere else does not take a reminder down with it`() {
        assertEquals("stray", GeofenceIds.reminderIdOf("stray"))
        assertNull(GeofenceIds.triggerIndexOf("stray"))
        assertNull(GeofenceIds.triggerIndexOf("$uuid#x"))
    }

    @Test
    fun `an id is told from somebody's word for a place`() {
        val place = Trigger.Location(40.50074, -3.66413, 150, Presence.INSIDE, "club", onCrossing = true)
        assertTrue(GeofenceIds.looksLikeId(GeofenceIds.encode("7f1225fa-26c1-4100-9d5e-5185757a7996", 0, place)))
        assertTrue(GeofenceIds.looksLikeId(GeofenceIds.encode("r", 1, place.copy(presence = Presence.OUTSIDE, onCrossing = false))))
        assertTrue(GeofenceIds.looksLikeId(GeofenceIds.encodeCondition("r", 0, 1, Condition.AtPlace(40.5, -3.6, 150, "casa"))))
        // The two that used to reach a screen, verbatim.
        assertTrue(GeofenceIds.looksLikeId("7db1f491-3c2b-48e7-afc6-e800f8755d51#1@40.43000,-3.66601,50,E"))
        assertTrue(GeofenceIds.looksLikeId("7f1225fa-26c1-4100-9d5e-5185757a7996#0@40.50074,-3.66413,150,E"))
        // And what a person actually calls a place, including the awkward ones.
        for (name in listOf("club", "Casa", "Café #1 @ Sol", "C/ Mayor, 3", "40.5, -3.6", "@casa", "Oficina#2")) {
            assertFalse(GeofenceIds.looksLikeId(name), name)
        }
    }
}
