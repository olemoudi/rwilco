package dev.rwilco.notify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What the shade's summary is counted from — and why it is not just what the system lists.
 *
 * Cancelling a notification is handed to a thread of the system's own: ask it what is posted
 * immediately afterwards and it can still say the one on its way out is there. The summary was
 * then posted for a bundle about to be empty and stayed behind on its own — a line reading
 * "1 recordatorio" over nothing, left in the shade of somebody who had just postponed a
 * reminder and had to swipe it away by hand.
 */
class BundleChildrenTest {

    @Test
    fun `the one being cancelled does not count, however the system answers`() {
        assertEquals(0, bundleChildren(listed = listOf(7), posted = null, cancelled = 7))
        // The system got round to it before we asked: the same answer either way.
        assertEquals(0, bundleChildren(listed = emptyList(), posted = null, cancelled = 7))
        assertEquals(1, bundleChildren(listed = listOf(7, 9), posted = null, cancelled = 7))
    }

    @Test
    fun `the one just posted counts, however the system answers`() {
        assertEquals(1, bundleChildren(listed = emptyList(), posted = 7, cancelled = null))
        assertEquals(1, bundleChildren(listed = listOf(7), posted = 7, cancelled = null))
        assertEquals(2, bundleChildren(listed = listOf(9), posted = 7, cancelled = null))
    }

    @Test
    fun `with nothing of our own to declare it is the system's list`() {
        assertEquals(0, bundleChildren(listed = emptyList(), posted = null, cancelled = null))
        assertEquals(3, bundleChildren(listed = listOf(1, 2, 3), posted = null, cancelled = null))
    }
}
