package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Arriving somewhere you already are.
 *
 * "Cuando llegue a casa", written on the sofa, must not ring on the sofa. It waits for the
 * phone to leave — and while it is waiting there is nothing to catch, so it waits cheaply: half
 * an hour between looks, no GPS. Stepping out for the bin and back inside that half hour is not
 * an arrival either, which is exactly why the coarse watch costs nothing to be wrong about.
 */
class PlaceArrivalTest {

    private val homeLat = 40.4169
    private val homeLng = -3.7035
    private val home = WatchedPlace("home", homeLat, homeLng, radiusM = 200, transition = Transition.ENTER, label = "Casa")
    private val leavingHome = home.copy(id = "leave", transition = Transition.EXIT)

    /** A fix [metres] south of home; 0 is the middle of the kitchen. */
    private fun south(metres: Double, at: Instant, accuracy: Double = 15.0) =
        Fix(homeLat - metres / 111_195.0, homeLng, accuracy, at)

    @Test
    fun `a reminder written at home does not ring at home`() {
        val step = stepPlaceWatch(PlaceWatchState(), south(0.0, now), listOf(home), now)
        assertTrue(step.events.isEmpty(), "it rang for arriving where it was written")
        assertEquals(mapOf("home" to true), step.state.inside)
    }

    @Test
    fun `waiting for the phone to leave costs half an hour a look, and no gps`() {
        var state = PlaceWatchState()
        var clock = now
        var checks = 0
        // A whole evening on the sofa.
        while (clock < now.plus(Duration.ofHours(6))) {
            val step = stepPlaceWatch(state, south(0.0, clock), listOf(home), clock)
            assertTrue(step.events.isEmpty(), "it rang while nobody moved")
            assertFalse(step.plan!!.precise, "it woke the gps to watch a phone on a table")
            assertTrue(
                step.plan!!.wait >= PlaceWatchPolicy.INSIDE_MIN_WAIT,
                "a look every ${step.plan!!.wait.toMinutes()} min while sitting at home",
            )
            state = step.state
            clock += step.plan!!.wait
            checks++
        }
        assertTrue(checks <= 12, "six hours indoors cost $checks looks")
    }

    @Test
    fun `once it has seen you leave, coming back rings`() {
        var state = stepPlaceWatch(PlaceWatchState(), south(0.0, now), listOf(home), now).state
        // Out.
        val away = now.plus(Duration.ofMinutes(30))
        val out = stepPlaceWatch(state, south(1_500.0, away), listOf(home), away)
        assertTrue(out.events.isEmpty(), "leaving rang an arrival")
        assertFalse(out.state.inside.getValue("home"))
        state = out.state
        // And back.
        val back = away.plus(Duration.ofMinutes(90))
        val home = stepPlaceWatch(state, south(0.0, back), listOf(this.home), back)
        assertEquals(listOf(PlaceEvent("home", Transition.ENTER)), home.events)
    }

    @Test
    fun `a rule waiting for the leaving starts at half an hour and buys its way down by moving`() {
        // Standing inside a place IS standing next to its line, so "time to the line" would ask
        // for the fastest cadence in the app, all evening, for a door nobody walks through.
        val standing = stepPlaceWatch(PlaceWatchState(), south(0.0, now), listOf(leavingHome), now)
        assertEquals(PlaceWatchPolicy.LEAVING_MAX_WAIT, standing.plan!!.wait)
        assertFalse(standing.plan!!.precise, "the GPS, for a phone sitting inside its own place")
        // Sixty per cent of the radius crossed since the last look takes sixty per cent off the
        // half hour. (150 m of ground, less the 30 m the two fixes' own doubt eats: 120 of 200.)
        val later = now.plusSeconds(1800)
        val stirring = stepPlaceWatch(standing.state, south(150.0, later), listOf(leavingHome), later)
        assertEquals(Duration.ofMinutes(12), stirring.plan!!.wait)
        assertTrue(stirring.events.isEmpty(), "still inside")
        // And a whole radius' worth of it lands on the floor, which is as fast as this ever goes.
        val sooner = later.plusSeconds(720)
        val leaving = stepPlaceWatch(standing.state, south(200.0, sooner), listOf(leavingHome), sooner)
        assertEquals(PlaceWatchPolicy.LEAVING_MIN_WAIT, leaving.plan!!.wait)
        assertFalse(leaving.plan!!.precise)
    }

    @Test
    fun `an errand across town still sets the pace while the phone is at home`() {
        val errand = WatchedPlace("shop", homeLat - 0.02, homeLng, radiusM = 100, transition = Transition.ENTER, label = "Tienda")
        val inside = mapOf("home" to true)
        val driving = planNextCheck(south(0.0, now), Movement(speedMps = 12.0, stillStreak = 0), listOf(home, errand), inside = inside)
        assertEquals("shop", driving!!.nearest.id, "the sofa planned the drive")
        assertTrue(driving.wait < PlaceWatchPolicy.INSIDE_MIN_WAIT)
    }

    @Test
    fun `a geofence saying you arrived where you already were is not news`() {
        val state = PlaceWatchState(lastFix = south(0.0, now), inside = mapOf("home" to true))
        assertFalse(crossingIsNews(state, "home", Transition.ENTER, now))
        assertTrue(crossingIsNews(state, "home", Transition.EXIT, now), "leaving from inside is news")
    }

    @Test
    fun `a geofence arrival after the watch saw you away is news`() {
        val state = PlaceWatchState(lastFix = south(1_500.0, now), inside = mapOf("home" to false))
        assertTrue(crossingIsNews(state, "home", Transition.ENTER, now))
        assertFalse(crossingIsNews(state, "home", Transition.EXIT, now), "leaving what it had already left")
    }

    @Test
    fun `what the watch cannot vouch for rings`() {
        val old = now.minus(Duration.ofHours(4))
        val stale = PlaceWatchState(lastFix = south(0.0, old), inside = mapOf("home" to true))
        assertTrue(crossingIsNews(stale, "home", Transition.ENTER, now), "a four-hour-old fix answered for now")
        assertTrue(crossingIsNews(PlaceWatchState(), "home", Transition.ENTER, now), "no fix at all")
        val unjudged = PlaceWatchState(lastFix = south(0.0, now))
        assertTrue(crossingIsNews(unjudged, "home", Transition.ENTER, now), "a place never judged")
    }

    @Test
    fun `a crossing written down is old news to the other eye`() {
        val state = PlaceWatchState(lastFix = south(0.0, now)).remembering("home", Transition.ENTER)
        assertFalse(crossingIsNews(state, "home", Transition.ENTER, now))
        assertTrue(crossingIsNews(state.remembering("home", Transition.EXIT), "home", Transition.ENTER, now))
    }

    @Test
    fun `a place that has rung is owed a leaving before it rings again`() {
        // Strict: the geofence's word alone is not enough the second time round; the app has
        // to have seen the phone on the other side of the line since.
        val id = "r1#0@x"
        val now = Instant.parse("2026-08-27T18:00:00Z")
        val unknown = PlaceWatchState()
        assertTrue(crossingIsNews(unknown, id, Transition.ENTER, now), "the first time, the doubt rings")
        assertFalse(crossingIsNews(unknown, id, Transition.ENTER, now, strict = true), "the second time, the doubt does not")
        val seenOutside = PlaceWatchState(inside = mapOf(id to false))
        val seenInside = PlaceWatchState(inside = mapOf(id to true))
        assertTrue(crossingIsNews(seenOutside, id, Transition.ENTER, now, strict = true))
        assertFalse(crossingIsNews(seenInside, id, Transition.ENTER, now, strict = true), "still inside as far as the app knows: not an arrival")
        assertTrue(crossingIsNews(seenInside, id, Transition.EXIT, now, strict = true))
        assertFalse(crossingIsNews(seenOutside, id, Transition.EXIT, now, strict = true))
        // And a stale fix, which the lenient reading forgives, forgives nothing here.
        val stale = PlaceWatchState(lastFix = Fix(0.0, 0.0, 10.0, now.minusSeconds(4 * 3600)), inside = mapOf(id to true))
        assertTrue(crossingIsNews(stale, id, Transition.ENTER, now))
        assertFalse(crossingIsNews(stale, id, Transition.ENTER, now, strict = true))
    }
}
