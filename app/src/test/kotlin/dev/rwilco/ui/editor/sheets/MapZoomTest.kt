package dev.rwilco.ui.editor.sheets

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.pow

/**
 * The map opens on a circle that fits in it. What this pins is the thing the old bucket table
 * got wrong: a zoom is only right if the ground it shows is bigger than the circle drawn on it.
 */
class MapZoomTest {

    private val madrid = 40.4169
    private val height = 260f

    /** The metres of ground the map covers top to bottom at this zoom. */
    private fun viewportMetres(zoom: Double, latitude: Double = madrid, heightDp: Float = height) =
        heightDp * 156543.03392 * cos(Math.toRadians(latitude)) / 2.0.pow(zoom)

    @Test
    fun `the circle the map opens for fits in the map, with air around it`() {
        // 300 m of radius is 600 m of circle, and it has to be inside a 260dp map.
        val span = viewportMetres(zoomFittingCircle(300, madrid))
        assertTrue(span > 600.0, "a 300 m circle did not fit: $span m of view")
        assertTrue(span < 900.0, "and it should not be lost in the middle of the county: $span m")
    }

    @Test
    fun `every radius the slider offers fits`() {
        for (radius in 50..2000 step 50) {
            val span = viewportMetres(zoomFittingCircle(radius, madrid))
            assertTrue(span > 2.0 * radius, "$radius m did not fit in $span m of view")
        }
    }

    @Test
    fun `a wider circle is a wider view`() {
        val close = zoomFittingCircle(50, madrid)
        val far = zoomFittingCircle(1000, madrid)
        assertTrue(close > far, "a fifty-metre doorway must be nearer than a kilometre: $close vs $far")
    }

    @Test
    fun `latitude is part of the answer, and the poles do not divide by zero`() {
        // Mercator stretches the far north, so the same circle is drawn bigger there and the
        // map has to stand further back to fit it: less zoom, not more.
        assertTrue(
            zoomFittingCircle(300, 60.0) < zoomFittingCircle(300, 0.0),
            "the same circle takes more room at 60 degrees than at the equator",
        )
        // Both still fit, which is the thing that actually matters.
        assertTrue(viewportMetres(zoomFittingCircle(300, 60.0), latitude = 60.0) > 600.0)
        assertTrue(zoomFittingCircle(300, 90.0) in 3.0..19.0, "a pole is an answer, not an infinity")
    }

    @Test
    fun `nothing is asked for past the tiles that exist`() {
        assertTrue(zoomFittingCircle(1, madrid) <= 19.0, "MAPNIK has no tiles past 19")
    }
}
