package dev.rwilco.ui.editor.sheets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaceSearchTest {

    private val plaza = FoundPlace("Plaza Mayor", "Madrid, España", 40.4155, -3.7074)

    @Test
    fun `an answer is an answer, an empty one included, while the phone is online`() {
        assertEquals(PlaceSearch.Found(listOf(plaza)), placeSearchOutcome(listOf(plaza), online = true))
        assertEquals(PlaceSearch.Found(emptyList()), placeSearchOutcome(emptyList(), online = true), "no such address is a real answer")
    }

    @Test
    fun `no answer at all is not the same as no such address`() {
        // The geocoder missing, erroring or timing out all used to read as an empty list, and the
        // sheet said "no se encontró esa dirección" about an address it had never looked for.
        assertEquals(PlaceSearch.Unavailable, placeSearchOutcome(null, online = true))
        assertEquals(PlaceSearch.Unavailable, placeSearchOutcome(null, online = false))
    }

    @Test
    fun `offline, an empty answer is the network talking and not the address`() {
        // A geocoder with no connection tends to answer "nothing" rather than fail.
        assertEquals(PlaceSearch.Unavailable, placeSearchOutcome(emptyList(), online = false))
        // Whereas places that did come back are places, however they got here (a cached answer).
        assertEquals(PlaceSearch.Found(listOf(plaza)), placeSearchOutcome(listOf(plaza), online = false))
    }
}
