package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * Being somewhere you already are, and arriving somewhere you already are.
 *
 * The two readings of a circle, held apart. **"Mientras esté en casa", written on the sofa,
 * rings on the sofa** — that is the whole of what a state means, and it is what people mean
 * almost every time. **"Al llegar a casa" waits for the doorway**: written on the sofa it says
 * nothing until the phone has been seen away and comes back. While it waits there is nothing to
 * catch, so it waits cheaply — half an hour between looks, no GPS — and stepping out for the bin
 * and back inside that half hour is not an arrival either, which is exactly why the coarse watch
 * costs nothing to be wrong about.
 */
class PlaceArrivalTest {

    private val homeLat = 40.4169
    private val homeLng = -3.7035
    /** "Al llegar a casa": the doorway reading, which is what this file is mostly about. */
    private val home = WatchedPlace("home", homeLat, homeLng, radiusM = 200, transition = Transition.ENTER, label = "Casa", onCrossing = true)
    private val leavingHome = home.copy(id = "leave", transition = Transition.EXIT)
    /** "Mientras esté en casa": the state reading, and the default. */
    private val atHome = home.copy(id = "at", onCrossing = false)

    /** A fix [metres] south of home; 0 is the middle of the kitchen. */
    private fun south(metres: Double, at: Instant, accuracy: Double = 15.0) =
        Fix(homeLat - metres / 111_195.0, homeLng, accuracy, at)

    @Test
    fun `al llegar, written at home, does not ring at home`() {
        val step = stepPlaceWatch(PlaceWatchState(), south(0.0, now), listOf(home), now)
        assertTrue(step.events.isEmpty(), "it rang for arriving where it was written")
        assertEquals(mapOf("home" to true), step.state.inside)
    }

    @Test
    fun `mientras este, written at home, rings at once`() {
        // The consequence of reading a place as a state, and the point of doing it: nobody who
        // writes "acuérdate cuando estés en casa" from the sofa means "espera a salir primero".
        val step = stepPlaceWatch(PlaceWatchState(), south(0.0, now), listOf(atHome), now)
        assertEquals(listOf(PlaceEvent("at", Transition.ENTER)), step.events)
    }

    @Test
    fun `mientras este, written away, waits until you are there`() {
        val away = stepPlaceWatch(PlaceWatchState(), south(1_500.0, now), listOf(atHome), now)
        assertTrue(away.events.isEmpty(), "it rang for a phone across town")
        val back = now.plus(Duration.ofMinutes(20))
        val arrived = stepPlaceWatch(away.state, south(0.0, back), listOf(atHome), back)
        assertEquals(listOf(PlaceEvent("at", Transition.ENTER)), arrived.events)
    }

    @Test
    fun `a state says so once, and not again while it stays true`() {
        // The watch reports the moment it becomes true and then holds its tongue. What stops a
        // second ring after that is the round it already rang in (ReminderFiring); what stops a
        // second ring every five minutes is this.
        var step = stepPlaceWatch(PlaceWatchState(), south(0.0, now), listOf(atHome), now)
        assertEquals(1, step.events.size)
        repeat(3) {
            val later = now.plus(Duration.ofHours(it + 1L))
            step = stepPlaceWatch(step.state, south(0.0, later), listOf(atHome), later)
            assertTrue(step.events.isEmpty(), "it said it again after ${it + 1}h on the sofa")
        }
    }

    @Test
    fun `mientras no este is the same thing from the other side`() {
        val away = atHome.copy(id = "away", transition = Transition.EXIT)
        // Written while out: true already, so it says so.
        val out = stepPlaceWatch(PlaceWatchState(), south(1_500.0, now), listOf(away), now)
        assertEquals(listOf(PlaceEvent("away", Transition.EXIT)), out.events)
        // Written at home: nothing until the phone actually leaves.
        val inside = stepPlaceWatch(PlaceWatchState(), south(0.0, now), listOf(away), now)
        assertTrue(inside.events.isEmpty())
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
            assertFalse(step.plan!!.tier == FixTier.PRECISE, "it woke the gps to watch a phone on a table")
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
    fun `a rule waiting for the leaving starts at half an hour and buys its way down by nearing the line`() {
        // Standing inside a place IS standing next to its line, so "time to the line" would ask
        // for the fastest cadence in the app, all evening, for a door nobody walks through.
        val standing = stepPlaceWatch(PlaceWatchState(), south(0.0, now), listOf(leavingHome), now)
        assertEquals(PlaceWatchPolicy.LEAVING_MAX_WAIT, standing.plan!!.wait)
        assertFalse(standing.plan!!.tier == FixTier.PRECISE, "the GPS, for a phone sitting inside its own place")
        // Half an hour later, 150 m nearer the line: 50 m of gap left, closed at 0.083 m/s, which
        // with headroom is ten minutes of walking away — under the floor, so the floor.
        val later = now.plusSeconds(1800)
        val leaving = stepPlaceWatch(standing.state, south(150.0, later), listOf(leavingHome), later)
        assertEquals(PlaceWatchPolicy.LEAVING_MIN_WAIT, leaving.plan!!.wait)
        assertTrue(leaving.events.isEmpty(), "still inside")
        assertFalse(leaving.plan!!.tier == FixTier.PRECISE)
        // A drift rather than a walk: 40 m nearer over the same half hour leaves 145 m of gap
        // being closed at 0.022 m/s, which is over an hour away — so the ceiling stands, and
        // barely moving towards the door buys nothing.
        val drifting = stepPlaceWatch(standing.state, south(40.0, later), listOf(leavingHome), later)
        assertEquals(PlaceWatchPolicy.LEAVING_MAX_WAIT, drifting.plan!!.wait)
    }

    @Test
    fun `moving about inside a place buys nothing, however much ground it covers`() {
        // The case this was rebuilt for. The old rule took the fraction of the radius the phone
        // had *moved* off the half hour, and a life being lived inside a place covers a radius
        // an hour without once nearing the door: the wait sat on its five-minute floor from tea
        // time to bed, twelve fixes an hour for a line nobody crossed. Ground covered is not
        // progress towards leaving; nearing the line is.
        var state = stepPlaceWatch(PlaceWatchState(), south(0.0, now), listOf(leavingHome), now).state
        var clock = now
        var checks = 0
        val end = now.plus(Duration.ofHours(6))
        // Round and round the kitchen: 100 m from the middle every time, on a different bearing,
        // so every step between two looks is a couple of hundred metres of real walking.
        val bearings = listOf(100.0, -100.0, 100.0, -100.0)
        while (clock < end) {
            val plan = stepPlaceWatch(state, south(bearings[checks % 4], clock), listOf(leavingHome), clock)
            assertTrue(plan.events.isEmpty(), "it rang for a leaving nobody made")
            assertEquals(PlaceWatchPolicy.LEAVING_MAX_WAIT, plan.plan!!.wait, "$checks: pacing about bought a faster look")
            state = plan.state
            clock += plan.plan!!.wait
            checks++
        }
        assertTrue(checks <= 6 * 2, "six hours of pottering cost $checks looks")
    }

    @Test
    fun `an errand across town still sets the pace while the phone is at home`() {
        val errand = WatchedPlace("shop", homeLat - 0.02, homeLng, radiusM = 100, transition = Transition.ENTER, label = "Tienda", onCrossing = true)
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
