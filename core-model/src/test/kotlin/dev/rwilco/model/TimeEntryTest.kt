package dev.rwilco.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `typing an hour does not press the AM-PM buttons on the way past`() {
        // 1-3-0, meaning half past one in the morning: "13" is on its way somewhere, not a
        // request for the afternoon.
        assertFalse(afternoonAfterTyping("1", is24h = false, afternoon = false))
        assertFalse(afternoonAfterTyping("13", is24h = false, afternoon = false))
        assertFalse(afternoonAfterTyping("130", is24h = false, afternoon = false))
        assertEquals(LocalTime.of(1, 30), parseTypedTime("130", is24h = false, afternoon = false))
    }

    @Test
    fun `a complete hour past twelve takes the buttons with it`() {
        assertTrue(afternoonAfterTyping("1730", is24h = false, afternoon = false))
        assertTrue(afternoonAfterTyping("1300", is24h = false, afternoon = false))
        // And a morning hour typed while PM is lit stays where the button says.
        assertTrue(afternoonAfterTyping("930", is24h = false, afternoon = true))
        // On a 24-hour phone there are no buttons to press.
        assertFalse(afternoonAfterTyping("1730", is24h = true, afternoon = false))
    }
}
