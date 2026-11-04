package dev.rwilco.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FitAutoSizeTest {

    @Test
    fun `every step fitting gives the top one`() {
        assertEquals(8, largestFitting(8) { true })
    }

    @Test
    fun `no step fitting falls back to the smallest`() {
        assertEquals(0, largestFitting(8) { false })
    }

    @Test
    fun `the answer is the last step that fits, wherever the edge is`() {
        for (edge in 0..8) {
            assertEquals(edge, largestFitting(8) { it <= edge }, "edge at $edge")
        }
    }

    @Test
    fun `the smallest step is never laid out, because it is the answer when nothing fits`() {
        val probed = mutableListOf<Int>()
        largestFitting(8) { probed += it; false }
        assertTrue(0 !in probed, "probed $probed")
    }

    @Test
    fun `a search over one size lays nothing out`() {
        assertEquals(0, largestFitting(0) { error("laid out") })
    }

    @Test
    fun `the search halves, it does not walk`() {
        // A strip's title steps 16→36 sp by 2: eleven sizes, four layouts at most, not eleven.
        var layouts = 0
        largestFitting(10) { layouts++; it <= 5 }
        assertTrue(layouts <= 4, "$layouts layouts")
    }
}
