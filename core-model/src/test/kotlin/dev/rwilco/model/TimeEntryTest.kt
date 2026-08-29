package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalTime

class TimeEntryTest {

    @Test
    fun `digits read the way a time is said on a 24-hour clock`() {
        assertEquals(LocalTime.of(7, 0), parseTypedTime("7", is24h = true, afternoon = false))
        assertEquals(LocalTime.of(9, 30), parseTypedTime("930", is24h = true, afternoon = false))
        assertEquals(LocalTime.of(17, 30), parseTypedTime("1730", is24h = true, afternoon = false))
        assertEquals(LocalTime.of(0, 5), parseTypedTime("0005", is24h = true, afternoon = false))
        assertEquals(LocalTime.of(23, 0), parseTypedTime("23", is24h = true, afternoon = false))
    }

    @Test
    fun `on a 12-hour clock the half of the day comes from the button, unless the hour says otherwise`() {
        assertEquals(LocalTime.of(12, 30), parseTypedTime("1230", is24h = false, afternoon = true))
        assertEquals(LocalTime.of(0, 30), parseTypedTime("1230", is24h = false, afternoon = false))
        assertEquals(LocalTime.of(21, 0), parseTypedTime("9", is24h = false, afternoon = true))
        assertEquals(LocalTime.of(17, 30), parseTypedTime("1730", is24h = false, afternoon = false))
        assertNull(parseTypedTime("0", is24h = false, afternoon = false))
    }

    @Test
    fun `digits that make no time are nothing`() {
        assertNull(parseTypedTime("", is24h = true, afternoon = false))
        assertNull(parseTypedTime("2460", is24h = true, afternoon = false))
        assertNull(parseTypedTime("24", is24h = true, afternoon = false))
        assertNull(parseTypedTime("12345", is24h = true, afternoon = false))
        assertNull(parseTypedTime("9a", is24h = true, afternoon = false))
    }
}
