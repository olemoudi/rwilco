package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * The watch driven the way the phone drives it: a check, a wait it chose itself, the phone
 * somewhere else by then, another check. What these pin is the bargain — how few looks a
 * journey costs, how many a still night costs, and that a line is never crossed unseen.
 */
class PlaceWatchJourneyTest {

    private val homeLat = 40.4169
    private val homeLng = -3.7035
    // The doorway reading: a journey is about crossings, which is what this file walks.
    private val home = WatchedPlace("home", homeLat, homeLng, radiusM = 200, transition = Transition.ENTER, label = "Casa", onCrossing = true)
    private val leavingHome = home.copy(id = "leave", transition = Transition.EXIT)

    /** A fix [metres] south of home (negative is north), with a walking-grade accuracy. */
    private fun south(metres: Double, at: Instant, accuracy: Double = 15.0) =
        Fix(homeLat - metres / 111_195.0, homeLng, accuracy, at)

    /** A run of the watch. The phone moves at [speedMps] towards home, starting [startM] out. */
    private class Journey(val checks: Int, val gpsChecks: Int, val events: List<PlaceEvent>, val crossedAt: Instant?, val seenAt: Instant?)

    private fun approach(startM: Double, speedMps: Double, places: List<WatchedPlace>, maxHours: Long = 12): Journey {
        var state = PlaceWatchState()
        var clock = now
        var distance = startM
        var checks = 0
        var gps = 0
        val events = ArrayList<PlaceEvent>()
        var crossedAt: Instant? = null
        var seenAt: Instant? = null
        while (clock < now.plus(Duration.ofHours(maxHours))) {
            val step = stepPlaceWatch(state, south(distance, clock), places, clock)
            checks++
            if (state.tier == FixTier.PRECISE) gps++
            events += step.events
            if (step.events.isNotEmpty() && seenAt == null) seenAt = clock
            state = step.state
            val plan = step.plan ?: break
            if (distance <= 0.0 && seenAt != null) break
            // The phone keeps going while the watch looks away — and stops at the door, because
            // arriving is what this is. Driving straight through a 200 m circle at 90 km/h takes
            // sixteen seconds and is not arriving; that one is the geofence's to call.
            val wait = plan.wait
            val before = distance
            distance = (distance - speedMps * wait.seconds).coerceAtLeast(0.0)
            if (before > home.radiusM && distance <= home.radiusM && crossedAt == null) {
                crossedAt = clock.plusSeconds(((before - home.radiusM) / speedMps).toLong())
            }
            clock += wait
        }
        return Journey(checks, gps, events, crossedAt, seenAt)
    }

    @Test
    fun `driving in from thirty kilometres costs a handful of looks and one event`() {
        val trip = approach(startM = 30_000.0, speedMps = 25.0, places = listOf(home))
        assertEquals(listOf(PlaceEvent(home.id, Transition.ENTER)), trip.events)
        assertTrue(trip.checks <= 14, "${trip.checks} checks for a 20-minute drive")
        assertTrue(trip.gpsChecks <= 3, "${trip.gpsChecks} GPS checks; the GPS is for the last few hundred metres")
        // Seen within one wait of the crossing, and that wait was the floor.
        val lag = Duration.between(trip.crossedAt!!, trip.seenAt!!)
        assertTrue(lag <= PlaceWatchPolicy.MIN_WAIT, "seen $lag after crossing")
    }

    @Test
    fun `walking in from two kilometres is seen within two minutes of the door`() {
        val walk = approach(startM = 2_000.0, speedMps = 1.4, places = listOf(home))
        assertEquals(listOf(PlaceEvent(home.id, Transition.ENTER)), walk.events)
        assertTrue(walk.checks <= 12, "${walk.checks} checks for a 20-minute walk")
        val lag = Duration.between(walk.crossedAt!!, walk.seenAt!!)
        assertTrue(lag <= PlaceWatchPolicy.MIN_WAIT, "seen $lag after crossing")
    }

    @Test
    fun `a night standing still at home with a leaving rule costs two looks an hour, none of them GPS`() {
        var state = PlaceWatchState()
        var clock = now
        var checks = 0
        var gps = 0
        var lastWait = Duration.ZERO
        val end = now.plus(Duration.ofHours(8))
        while (clock < end) {
            // The same reading every time, give or take a few metres of wifi noise.
            val step = stepPlaceWatch(state, south(30.0 + (checks % 3) * 2.0, clock, accuracy = 25.0), listOf(leavingHome), clock)
            checks++
            if (state.tier == FixTier.PRECISE) gps++
            assertTrue(step.events.isEmpty(), "a phone that never left rang for leaving")
            state = step.state
            lastWait = step.plan!!.wait
            clock += lastWait
        }
        assertTrue(checks <= 8 * 2 + 2, "$checks checks in eight still hours")
        assertEquals(0, gps, "the GPS woke for a phone on a bedside table")
        assertEquals(PlaceWatchPolicy.LEAVING_MAX_WAIT, lastWait, "a phone that has not moved is not about to leave")
    }

    @Test
    fun `an evening lived inside a place with a leaving rule costs two looks an hour`() {
        // The budget this release is about. Not a phone on a bedside table — that one rests and
        // takes no fix at all — but a phone in a pocket, being carried about a flat all evening:
        // the sensor fires, so nothing rests, and every look is a real one. What must not happen
        // is the old answer, where a radius' worth of pacing an hour pinned the wait to its
        // five-minute floor and cost twelve fixes an hour until bedtime.
        var state = PlaceWatchState()
        var clock = now
        var checks = 0
        var gps = 0
        val hours = 6L
        val end = now.plus(Duration.ofHours(hours))
        val kitchen = listOf(60.0, -40.0, 20.0, -70.0, 50.0, -30.0)
        while (clock < end) {
            val step = stepPlaceWatch(state, south(kitchen[checks % kitchen.size], clock, accuracy = 20.0), listOf(leavingHome), clock, sensed = true)
            assertTrue(step.events.isEmpty(), "it rang for a leaving nobody made")
            if (state.tier == FixTier.PRECISE) gps++
            state = step.state
            clock += step.plan!!.wait
            checks++
        }
        assertTrue(checks <= hours * 2 + 1, "$checks looks in $hours hours of being at home")
        assertEquals(0, gps, "the GPS, for somebody making dinner")
    }

    @Test
    fun `leaving home in the morning is seen within one rested look, and only once`() {
        // A still night first, so the back-off is at its cap when the phone starts moving.
        var state = PlaceWatchState()
        var clock = now
        var lastWait = Duration.ZERO
        repeat(10) {
            val step = stepPlaceWatch(state, south(30.0, clock, accuracy = 25.0), listOf(leavingHome), clock)
            state = step.state
            lastWait = step.plan!!.wait
            clock += lastWait
        }
        assertEquals(PlaceWatchPolicy.LEAVING_MAX_WAIT, lastWait)
        // Then a walk out: 1.4 m/s from 30 m inside to well past the line by the next look.
        val walkStart = clock
        var distance = 30.0
        val events = ArrayList<PlaceEvent>()
        var seenAt: Instant? = null
        repeat(6) {
            val step = stepPlaceWatch(state, south(distance, clock), listOf(leavingHome), clock)
            events += step.events
            if (step.events.isNotEmpty() && seenAt == null) seenAt = clock
            state = step.state
            val wait = step.plan!!.wait
            distance += 1.4 * wait.seconds
            clock += wait
        }
        assertEquals(listOf(PlaceEvent(leavingHome.id, Transition.EXIT)), events)
        // Crossing 170 m out takes two minutes on foot; the watch, rested at half an hour, sees
        // it at its next look. That is the price of the rest, and it is the price knowingly paid:
        // the leaving that matters is the geofence's to report, within a minute of the line, and
        // this is the second opinion under it — cheap first, prompt second.
        assertTrue(Duration.between(walkStart, seenAt!!) <= PlaceWatchPolicy.LEAVING_MAX_WAIT, "seen ${Duration.between(walkStart, seenAt)} after setting off")
    }

    @Test
    fun `wobbling on the line changes nothing either way`() {
        var state = stepPlaceWatch(PlaceWatchState(), south(190.0, now, accuracy = 20.0), listOf(home, leavingHome), now).state
        assertEquals(true, state.inside[home.id])
        var clock = now
        // Readings 190–215 m out, each within 20 m of doubt: neither clearly out nor freshly in.
        val readings = listOf(215.0, 190.0, 210.0, 195.0, 218.0, 205.0)
        for (r in readings) {
            clock = clock.plusSeconds(120)
            val step = stepPlaceWatch(state, south(r, clock, accuracy = 20.0), listOf(home, leavingHome), clock)
            assertTrue(step.events.isEmpty(), "rang on a $r m wobble")
            assertEquals(true, step.state.inside[home.id])
            state = step.state
        }
        // A reading clearly beyond the line is leaving.
        val out = stepPlaceWatch(state, south(260.0, clock.plusSeconds(120), accuracy = 20.0), listOf(home, leavingHome), clock.plusSeconds(120))
        assertEquals(listOf(PlaceEvent(leavingHome.id, Transition.EXIT)), out.events)
    }

    @Test
    fun `two places apart are watched by whichever is nearer`() {
        val work = WatchedPlace("work", homeLat + 0.05, homeLng, radiusM = 150, transition = Transition.ENTER, label = "Trabajo")
        val nearHome = planNextCheck(south(1000.0, now), Movement(speedMps = 5.0, stillStreak = 0), listOf(home, work))!!
        assertEquals(home, nearHome.nearest)
        val nearWork = planNextCheck(south(-5_000.0, now), Movement(speedMps = 5.0, stillStreak = 0), listOf(home, work))!!
        assertEquals(work, nearWork.nearest)
    }

    @Test
    fun `whatever the input, the wait stays inside the policy and the GPS stays near`() {
        val random = Random(20260825)
        repeat(2_000) {
            val fix = Fix(
                lat = (homeLat + random.nextDouble(-40.0, 40.0)).coerceIn(-85.0, 85.0),
                lng = homeLng + random.nextDouble(-40.0, 40.0),
                accuracyM = random.nextDouble(3.0, 2_000.0),
                at = now,
            )
            val speed = if (random.nextInt(4) == 0) null else random.nextDouble(0.0, 40.0)
            val streak = random.nextInt(0, 30)
            val moved = if (random.nextInt(3) == 0) null else random.nextDouble(0.0, 5_000.0)
            val plan = planNextCheck(fix, Movement(speed, moved, stillStreak = streak), listOf(home, leavingHome))!!
            assertTrue(plan.wait >= PlaceWatchPolicy.MIN_WAIT, "${plan.wait} under the floor")
            // Distance is the only thing that may lift the hour, and never past what 120 km/h
            // would need to cover the gap.
            assertTrue(
                plan.wait <= maxOf(PlaceWatchPolicy.MAX_WAIT, reachCeiling(plan.gapM)),
                "${plan.wait} over the ceiling ${plan.gapM} m out",
            )
            if (plan.tier == FixTier.PRECISE) assertTrue(plan.gapM < PlaceWatchPolicy.NEAR_M, "GPS ${plan.gapM} m from the line")
            if (speed != null && speed <= PlaceWatchPolicy.STILL_MPS) assertFalse(plan.tier == FixTier.PRECISE, "GPS for a still phone")
        }
    }

    @Test
    fun `a doorway never rings on a place it has no history for, whatever the fix`() {
        val random = Random(42)
        repeat(500) {
            val fix = Fix(homeLat + random.nextDouble(-0.01, 0.01), homeLng + random.nextDouble(-0.01, 0.01), random.nextDouble(3.0, 300.0), now)
            val step = stepPlaceWatch(PlaceWatchState(), fix, listOf(home, leavingHome), now)
            assertTrue(step.events.isEmpty())
        }
    }
}
