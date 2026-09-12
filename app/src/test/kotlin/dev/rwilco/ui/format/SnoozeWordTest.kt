package dev.rwilco.ui.format

import dev.rwilco.alarm.LATER_DETAIL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which word a line of history filed as a snooze gets.
 *
 * All four readings come off one column, and one of them was being read as another: a routine
 * answered with "todavía no" is written down as a snooze because there is nothing else to write
 * it as, and the history then said the routine had been postponed, which nobody did.
 */
class SnoozeWordTest {

    @Test
    fun `a said-not-yet is not a postponement`() {
        assertEquals(SnoozeWord.NOT_YET, snoozeWordOf(LATER_DETAIL))
    }

    @Test
    fun `a snooze to a place is one`() {
        assertEquals(SnoozeWord.UNTIL_PLACE, snoozeWordOf("arrive:Casa"))
        assertEquals(SnoozeWord.UNTIL_PLACE, snoozeWordOf("leave:La oficina"))
    }

    @Test
    fun `a snooze to a moment is one`() {
        assertEquals(SnoozeWord.UNTIL_MOMENT, snoozeWordOf("2026-09-12T08:30:00Z"))
    }

    @Test
    fun `a snooze with nothing written down is a plain one`() {
        assertEquals(SnoozeWord.PLAIN, snoozeWordOf(null))
        // Not a moment, not a place, not the word: whatever it is, it is not worth guessing at.
        assertEquals(SnoozeWord.PLAIN, snoozeWordOf("mañana"))
    }
}
