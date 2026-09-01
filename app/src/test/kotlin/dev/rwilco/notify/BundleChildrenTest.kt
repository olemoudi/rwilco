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
        assertEquals(0, bundleChildren(listed = listOf(7), cancelled = setOf(7)))
        // The system got round to it before we asked: the same answer either way.
        assertEquals(0, bundleChildren(listed = emptyList(), cancelled = setOf(7)))
        assertEquals(1, bundleChildren(listed = listOf(7, 9), cancelled = setOf(7)))
    }

    @Test
    fun `both of a reminder's cards go down in one breath`() {
        // The ring and the net's word about it. Declared together they leave nothing, whatever
        // the system's list still says.
        assertEquals(0, bundleChildren(listed = listOf(7, 8), cancelled = setOf(7, 8)))
        assertEquals(0, bundleChildren(listed = emptyList(), cancelled = setOf(7, 8)))
        // Somebody else's alert is not ours to take down with them.
        assertEquals(1, bundleChildren(listed = listOf(7, 8, 9), cancelled = setOf(7, 8)))
    }

    @Test
    fun `declaring one card at a time is what left the line over nothing`() {
        // The bug, written down: cancel() took both cards down and then counted the bundle
        // twice, once per id. The second pass subtracted the note from a list the system had
        // not caught up with — the ring still in it — counted one, and put the summary back
        // over a bundle that was by then empty.
        assertEquals(1, bundleChildren(listed = listOf(7), cancelled = setOf(8)))
        // Which is why there is one pass now, and it knows about both.
        assertEquals(0, bundleChildren(listed = listOf(7), cancelled = setOf(7, 8)))
    }

    @Test
    fun `the one just posted counts, however the system answers`() {
        assertEquals(1, bundleChildren(listed = emptyList(), posted = 7))
        assertEquals(1, bundleChildren(listed = listOf(7), posted = 7))
        assertEquals(2, bundleChildren(listed = listOf(9), posted = 7))
    }

    @Test
    fun `with nothing of our own to declare it is the system's list`() {
        // Which is the sweep at launch: nothing posted, nothing cancelled, and a summary left
        // standing over an empty bundle is taken down on the strength of it.
        assertEquals(0, bundleChildren(listed = emptyList()))
        assertEquals(3, bundleChildren(listed = listOf(1, 2, 3)))
    }
}
