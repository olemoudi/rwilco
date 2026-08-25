package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class PlaceWatchTest {

    // Puerta del Sol, Madrid.
    private val homeLat = 40.4169
    private val homeLng = -3.7035
    private val home = WatchedPlace("r1#0", homeLat, homeLng, radiusM = 200, transition = Transition.ENTER, label = "Casa")
    private val work = WatchedPlace("r2#0", 40.4500, -3.6900, radiusM = 150, transition = Transition.EXIT, label = "Trabajo")

    /** A point [metres] due north of home: one degree of latitude is ~111.2 km everywhere. */
    private fun north(metres: Double, accuracy: Double = 10.0, at: Instant = now) =
        Fix(homeLat + metres / 111_195.0, homeLng, accuracy, at)

    @Test
    fun `haversine agrees with the map`() {
        // Madrid to Barcelona, city centre to city centre: about 505 km.
        val madridBarcelona = distanceMeters(40.4169, -3.7035, 41.3874, 2.1686)
        assertTrue(madridBarcelona in 500_000.0..510_000.0, "$madridBarcelona")
        assertEquals(0.0, distanceMeters(homeLat, homeLng, homeLat, homeLng), 1e-6)
        assertEquals(1000.0, distanceMeters(homeLat, homeLng, north(1000.0).lat, homeLng), 1.0)
    }

    @Test
    fun `speed needs an earlier fix that is recent, and a step bigger than the noise`() {
        val earlier = north(0.0, at = now.minusSeconds(100))
        assertNull(speedBetween(null, north(500.0)))
        assertNull(speedBetween(north(0.0, at = now.minusSeconds(3600)), north(500.0)), "an hour-old fix says nothing")
        assertEquals(0.0, speedBetween(north(0.0, at = now), north(0.0, at = now)), "the same fix handed back twice is a phone that has not moved")
        assertNull(speedBetween(north(0.0, at = now), north(500.0, at = now.minusSeconds(10))), "a fix older than the last is noise")
        assertEquals(0.0, speedBetween(earlier, north(15.0)), "15 m with 10 m fixes is standing still")
        assertEquals(5.0, speedBetween(earlier, north(500.0))!!, 0.01)
    }

    @Test
    fun `the gap to a line is measured from either side and eaten by the fix's own doubt`() {
        assertEquals(790.0, gapToLine(home, north(1000.0)), 1.0, "800 m to the line, less 10 m of doubt")
        assertEquals(140.0, gapToLine(home, north(50.0)), 1.0, "50 m inside a 200 m circle is 150 m from the line")
        assertEquals(0.0, gapToLine(home, north(250.0, accuracy = 80.0)), "50 m out with 80 m of doubt is on the line")
        assertEquals(700.0, gapToLine(home, north(1000.0, accuracy = 100.0)), 1.0)
    }

    @Test
    fun `far away and no idea of speed, the wait is bounded by the ceiling`() {
        // Sixty kilometres south: home is the nearer of the two (work is north of it).
        val plan = planNextCheck(north(-60_000.0), speedMps = null, places = listOf(home, work), stillStreak = 0)!!
        assertEquals(PlaceWatchPolicy.MAX_WAIT, plan.wait)
        assertFalse(plan.precise)
        assertEquals(home, plan.nearest)
    }

    @Test
    fun `unknown speed a kilometre out plans for a slow car`() {
        // 790 m to the line at 8 m/s is 99 s, which the floor lifts to two minutes.
        val near = planNextCheck(north(1000.0), speedMps = null, places = listOf(home), stillStreak = 0)!!
        assertEquals(PlaceWatchPolicy.MIN_WAIT, near.wait)
        // 4.79 km to the line at 8 m/s: just under ten minutes.
        val farther = planNextCheck(north(5000.0), speedMps = null, places = listOf(home), stillStreak = 0)!!
        assertEquals(Duration.ofSeconds(598), farther.wait)
    }

    @Test
    fun `a walker gets headroom, and the floor never drops under two minutes`() {
        // 1.79 km to the line, walking at 1.4 m/s: planned at 2.1 m/s, ~14 minutes.
        val walking = planNextCheck(north(2000.0), speedMps = 1.4, places = listOf(home), stillStreak = 0)!!
        assertEquals(Duration.ofSeconds(852), walking.wait)
        assertFalse(walking.precise)
        // Driving at 20 m/s, 1.8 km out: a minute at 30 m/s, so the floor.
        val driving = planNextCheck(north(2000.0), speedMps = 20.0, places = listOf(home), stillStreak = 0)!!
        assertEquals(PlaceWatchPolicy.MIN_WAIT, driving.wait)
    }

    @Test
    fun `moving near a line means GPS and the fastest cadence`() {
        val plan = planNextCheck(north(400.0), speedMps = 1.4, places = listOf(home), stillStreak = 0)!!
        assertEquals(PlaceWatchPolicy.MIN_WAIT, plan.wait)
        assertTrue(plan.precise)
    }

    @Test
    fun `standing still near a line backs off, doubling to a quarter of an hour`() {
        val waits = (0..5).map { streak -> planNextCheck(north(50.0), speedMps = 0.0, places = listOf(home), stillStreak = streak)!! }
        assertEquals(listOf(2L, 4L, 8L, 15L, 15L, 15L), waits.map { it.wait.toMinutes() })
        assertTrue(waits.none { it.precise }, "a phone that is not moving does not need the GPS")
    }

    @Test
    fun `standing still far away backs off all the way to the ceiling`() {
        val plan = planNextCheck(north(3000.0), speedMps = 0.0, places = listOf(home), stillStreak = 10)!!
        assertEquals(PlaceWatchPolicy.MAX_WAIT, plan.wait)
    }

    @Test
    fun `nothing to watch is no plan`() {
        assertNull(planNextCheck(north(0.0), speedMps = null, places = emptyList(), stillStreak = 0))
    }

    @Test
    fun `getting in needs a fix that is inside and not sloppier than the place`() {
        assertTrue(insideAfter(wasInside = false, place = home, fix = north(100.0, accuracy = 30.0)))
        assertFalse(insideAfter(wasInside = false, place = home, fix = north(100.0, accuracy = 500.0)), "could be anywhere")
        assertFalse(insideAfter(wasInside = false, place = home, fix = north(210.0, accuracy = 30.0)))
    }

    @Test
    fun `getting out needs a fix clearly beyond the line`() {
        assertTrue(insideAfter(wasInside = true, place = home, fix = north(220.0, accuracy = 30.0)), "20 m out with 30 m of doubt: still in")
        assertFalse(insideAfter(wasInside = true, place = home, fix = north(300.0, accuracy = 30.0)))
    }

    @Test
    fun `with no history the plain answer, and no event`() {
        val step = stepPlaceWatch(PlaceWatchState(), north(50.0), listOf(home, work), now)
        assertEquals(mapOf(home.id to true, work.id to false), step.state.inside)
        assertTrue(step.events.isEmpty(), "standing at home when the rule is written is not arriving")
        assertNotNull(step.plan)
        assertEquals(now + step.plan!!.wait, step.state.nextCheckAt)
    }

    @Test
    fun `a new place is baselined by the next fix, the rest keep their history`() {
        val known = PlaceWatchState(inside = mapOf(home.id to false))
        val step = stepPlaceWatch(known, north(50.0), listOf(home, work), now)
        assertEquals(listOf(PlaceEvent(home.id, Transition.ENTER)), step.events)
        assertEquals(false, step.state.inside[work.id])
    }

    @Test
    fun `only the crossing a rule waits for is an event`() {
        val leavingHome = home.copy(transition = Transition.EXIT)
        val outside = PlaceWatchState(inside = mapOf(home.id to false))
        assertTrue(stepPlaceWatch(outside, north(50.0), listOf(leavingHome), now).events.isEmpty(), "arriving at a leaving rule")
        val inside = PlaceWatchState(inside = mapOf(home.id to true))
        assertEquals(
            listOf(PlaceEvent(home.id, Transition.EXIT)),
            stepPlaceWatch(inside, north(400.0), listOf(leavingHome), now).events,
        )
        assertTrue(stepPlaceWatch(inside, north(400.0), listOf(home), now).events.isEmpty(), "leaving an arriving rule")
    }

    @Test
    fun `a still streak counts up and a move resets it`() {
        val start = stepPlaceWatch(PlaceWatchState(), north(3000.0, at = now), listOf(home), now)
        val later = now.plusSeconds(600)
        val still = stepPlaceWatch(start.state, north(3005.0, at = later), listOf(home), later)
        assertEquals(1, still.state.stillStreak)
        val moved = stepPlaceWatch(still.state, north(2000.0, at = later.plusSeconds(600)), listOf(home), later.plusSeconds(600))
        assertEquals(0, moved.state.stillStreak)
    }

    @Test
    fun `the next look is planned from now, not from an old fix`() {
        val stale = north(2000.0, at = now.minusSeconds(1800))
        val step = stepPlaceWatch(PlaceWatchState(), stale, listOf(home), now)
        assertTrue(step.state.nextCheckAt!! > now)
    }

    @Test
    fun `the state survives a round trip through json`() {
        val state = stepPlaceWatch(PlaceWatchState(), north(50.0), listOf(home), now).state
        assertEquals(state, ReminderCodec.decodePlaceWatch(ReminderCodec.encodePlaceWatch(state)))
        assertEquals(PlaceWatchState(), ReminderCodec.decodePlaceWatch("not json"))
    }

    @Test
    fun `saved places ride along in the settings`() {
        val settings = AppSettings(savedPlaces = listOf(SavedPlace("Casa", homeLat, homeLng, 200)))
        assertEquals(settings, ReminderCodec.decodeSettings(ReminderCodec.encodeSettings(settings)))
    }
}
