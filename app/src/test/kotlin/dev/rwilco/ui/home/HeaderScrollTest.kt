package dev.rwilco.ui.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Where the top row sits as the list moves under it. See [HeaderScroll] for why it moves at all.
 *
 * Compared with a tolerance of nothing rather than by strict equality: clamping to a row that
 * has not been measured yet answers minus zero, which is the same offset and a different Float.
 */
class HeaderScrollTest {

    private val height = 120f

    @Test
    fun `scrolling down takes the row out, and never further than its own height`() {
        // A scroll down the list arrives negative: forty pixels of list is forty of row.
        assertEquals(-40f, headerOffsetAfter(offset = 0f, consumed = -40f, height = height), 0f)
        assertEquals(-120f, headerOffsetAfter(offset = -80f, consumed = -40f, height = height), 0f)
        assertEquals(-120f, headerOffsetAfter(offset = -120f, consumed = -900f, height = height), 0f, "gone is as far as it goes")
    }

    @Test
    fun `scrolling up brings it back, and never past the top of itself`() {
        assertEquals(-80f, headerOffsetAfter(offset = -120f, consumed = 40f, height = height), 0f)
        assertEquals(0f, headerOffsetAfter(offset = -30f, consumed = 40f, height = height), 0f)
        assertEquals(0f, headerOffsetAfter(offset = 0f, consumed = 900f, height = height), 0f, "there is no more than shown")
    }

    @Test
    fun `a list that scrolls nothing moves nothing`() {
        // What the list ate is what the row moves by, so a screen too short to scroll — two
        // reminders on a fresh phone — cannot have its row dragged off it.
        assertEquals(-40f, headerOffsetAfter(offset = -40f, consumed = 0f, height = height), 0f)
    }

    @Test
    fun `a row let go of half way finishes the journey it was on`() {
        assertEquals(0f, headerSettleTarget(offset = -59f, height = height), 0f, "more of it showing than not")
        assertEquals(-120f, headerSettleTarget(offset = -60f, height = height), 0f, "and past half way it goes")
        assertEquals(0f, headerSettleTarget(offset = 0f, height = height), 0f)
        assertEquals(-120f, headerSettleTarget(offset = -120f, height = height), 0f)
    }

    @Test
    fun `nothing moves before the row has been measured`() {
        assertEquals(0f, headerOffsetAfter(offset = 0f, consumed = -400f, height = 0f), 0f)
        assertEquals(0f, headerSettleTarget(offset = 0f, height = 0f), 0f)
    }
}
