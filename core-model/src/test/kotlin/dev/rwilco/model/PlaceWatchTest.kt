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
        assertNull(speedBetween(north(0.0, at = now.minusSeconds(2 * 3600)), north(500.0)), "a two-hour-old fix says nothing")
        assertEquals(500.0 / 3600, speedBetween(north(0.0, at = now.minusSeconds(3600)), north(500.0))!!, 0.001, "an hour-old one still gives the average")
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
    fun `blind near home the wait is a quarter of an hour, blind far from it the drive is`() {
        // Ten kilometres out with nothing known: a slow car would take twenty minutes, but an
        // hour blind is ninety motorway kilometres, so the unknown-speed ceiling holds it to a
        // quarter of one.
        val near = planNextCheck(north(-10_000.0), Movement(), listOf(home))!!
        assertEquals(PlaceWatchPolicy.UNKNOWN_MAX_WAIT, near.wait)
        // Sixty kilometres out, the same argument says the opposite: nobody covers sixty in
        // fifteen minutes. (Home is the nearer of the two; work is north of it.)
        val plan = planNextCheck(north(-60_000.0), Movement(), listOf(home, work))!!
        assertEquals(Duration.ofSeconds(1790), plan.wait, "59.79 km at 120 km/h")
        assertFalse(plan.precise)
        assertEquals(home, plan.nearest)
    }

    @Test
    fun `far away and known to be slow, the wait goes all the way to the ceiling`() {
        val plan = planNextCheck(north(-60_000.0), Movement(speedMps = 1.4, stillStreak = 0), listOf(home, work))!!
        assertEquals(PlaceWatchPolicy.MAX_WAIT, plan.wait, "the hour, which sixty kilometres cannot lift")
    }

    @Test
    fun `distance buys sleep by the hour, until a flight could be covering it`() {
        // A gap the plain hour already covers buys nothing: this only ever raises a ceiling.
        assertTrue(reachCeiling(60_000.0) < PlaceWatchPolicy.MAX_WAIT)
        // Four hundred kilometres is three and a third hours of motorway, and nothing on a road
        // gets there sooner, so nothing needs looking at sooner.
        assertEquals(Duration.ofSeconds(11_976), reachCeiling(400_000.0))
        // Past 500 km a flight is on the table and no road speed bounds anything, so it is back
        // to the plain hour — which, next to any flight door to door, is still short. Madrid to
        // Barcelona is over that line by five kilometres.
        assertEquals(PlaceWatchPolicy.MAX_WAIT, reachCeiling(distanceMeters(homeLat, homeLng, 41.3874, 2.1686)))
        assertEquals(PlaceWatchPolicy.MAX_WAIT, reachCeiling(9_000_000.0))
    }

    @Test
    fun `a phone at home with a place three provinces away sleeps the afternoon`() {
        // 300 km out and standing still: nobody drives that in under two and a half hours, so
        // the hourly look is two hours of radio spent on a question with a known answer.
        val far = WatchedPlace("far", homeLat + 2.7, homeLng, radiusM = 200, transition = Transition.ENTER, label = "Lejos")
        val plan = planNextCheck(north(0.0), Movement(speedMps = 0.0, movedM = 0.0, stillStreak = 10), listOf(far))!!
        assertTrue(plan.wait > Duration.ofHours(2), "${plan.wait}")
        assertEquals(reachCeiling(plan.gapM), plan.wait)
        assertFalse(plan.precise)
    }

    @Test
    fun `unknown speed a kilometre out plans for a slow car`() {
        // 790 m to the line at 8 m/s is 99 s, which the floor lifts to two minutes.
        val near = planNextCheck(north(1000.0), Movement(speedMps = null, stillStreak = 0), listOf(home))!!
        assertEquals(PlaceWatchPolicy.MIN_WAIT, near.wait)
        // 4.79 km to the line at 8 m/s: just under ten minutes.
        val farther = planNextCheck(north(5000.0), Movement(speedMps = null, stillStreak = 0), listOf(home))!!
        assertEquals(Duration.ofSeconds(598), farther.wait)
    }

    @Test
    fun `a walker gets headroom, and the floor never drops under two minutes`() {
        // 1.79 km to the line, walking at 1.4 m/s: planned at 2.1 m/s, ~14 minutes.
        val walking = planNextCheck(north(2000.0), Movement(speedMps = 1.4, stillStreak = 0), listOf(home))!!
        assertEquals(Duration.ofSeconds(852), walking.wait)
        assertFalse(walking.precise)
        // Driving at 20 m/s, 1.8 km out: a minute at 30 m/s, so the floor.
        val driving = planNextCheck(north(2000.0), Movement(speedMps = 20.0, stillStreak = 0), listOf(home))!!
        assertEquals(PlaceWatchPolicy.MIN_WAIT, driving.wait)
    }

    @Test
    fun `moving near a line means GPS and the fastest cadence`() {
        val plan = planNextCheck(north(400.0), Movement(speedMps = 1.4, stillStreak = 0), listOf(home))!!
        assertEquals(PlaceWatchPolicy.MIN_WAIT, plan.wait)
        assertTrue(plan.precise)
    }

    @Test
    fun `near a line but with no speed to go on, the cadence is the fastest and the GPS stays off`() {
        // The first look of a session at home: a phone on a bedside table, most nights.
        val plan = planNextCheck(north(400.0), Movement(speedMps = null, stillStreak = 0), listOf(home))!!
        assertEquals(PlaceWatchPolicy.MIN_WAIT, plan.wait)
        assertFalse(plan.precise)
    }

    @Test
    fun `standing still near a line backs off, doubling to a quarter of an hour`() {
        val outside = home.copy(radiusM = 20)
        val waits = (0..5).map { streak -> planNextCheck(north(50.0), Movement(speedMps = 0.0, stillStreak = streak), listOf(outside))!! }
        assertEquals(listOf(2L, 4L, 8L, 15L, 15L, 15L), waits.map { it.wait.toMinutes() })
        assertTrue(waits.none { it.precise }, "a phone that is not moving does not need the GPS")
    }

    @Test
    fun `the motion sensor's word is taken one way only`() {
        val outside = home.copy(radiusM = 20)
        val fix = north(50.0)
        // It felt something: whatever the two fixes made of it, this is not a still phone, and
        // the back-off it had been earning is gone.
        val stirred = planNextCheck(fix, Movement(speedMps = 0.0, sensed = true, stillStreak = 5), listOf(outside))!!
        assertEquals(PlaceWatchPolicy.MIN_WAIT, stirred.wait)
        assertFalse(stirred.precise, "and still not a reason to wake the GPS")
        // It felt nothing, and the fixes agree: now the near-a-line cap comes off, because a
        // phone that has neither moved nor been jostled is a phone on a table.
        val settled = planNextCheck(fix, Movement(speedMps = 0.0, sensed = false, stillStreak = 5), listOf(outside))!!
        assertEquals(PlaceWatchPolicy.MAX_WAIT, settled.wait)
        // On its own it decides nothing: a train glides, and its passengers' phones feel nothing.
        val gliding = planNextCheck(fix, Movement(speedMps = 30.0, sensed = false, stillStreak = 0), listOf(outside))!!
        assertEquals(PlaceWatchPolicy.MIN_WAIT, gliding.wait)
    }

    @Test
    fun `a look nothing could have changed is not taken`() {
        val at = north(50.0, at = now)
        val resting = PlaceWatchState(lastFix = at, inside = mapOf(home.id to true), stillStreak = 3)
        val later = now.plusSeconds(1800)
        val rest = stepWithoutLooking(resting, listOf(home), later, sensed = false)!!
        assertEquals(at, rest.state.lastFix, "the stored fix stands; nothing newer was bought")
        assertEquals(4, rest.state.stillStreak)
        assertTrue(rest.events.isEmpty(), "a rested step invented a crossing")
        assertEquals(later + rest.plan!!.wait, rest.state.nextCheckAt)
        assertFalse(rest.plan!!.precise)
    }

    @Test
    fun `every other answer takes the look`() {
        val at = north(50.0, at = now)
        val resting = PlaceWatchState(lastFix = at, inside = mapOf(home.id to true), stillStreak = 3)
        val soon = now.plusSeconds(600)
        assertNull(stepWithoutLooking(resting, listOf(home), soon, sensed = true), "it felt something")
        assertNull(stepWithoutLooking(resting, listOf(home), soon, sensed = null), "nobody was listening")
        assertNull(
            stepWithoutLooking(resting.copy(stillStreak = 0), listOf(home), soon, sensed = false),
            "the fixes had not agreed it was still; the sensor does not decide alone",
        )
        assertNull(stepWithoutLooking(resting.copy(lastFix = null), listOf(home), soon, sensed = false), "nothing in hand")
        assertNull(stepWithoutLooking(resting, emptyList(), soon, sensed = false), "nothing to watch")
        // And a rest is never allowed to leave the fix too old to speak for the present: an hour
        // and a half is the bound everything downstream reads it by.
        val stale = now.plus(PlaceWatchPolicy.SPEED_MEMORY).minusSeconds(60)
        assertNull(stepWithoutLooking(resting, listOf(home), stale, sensed = false), "a rest outliving the speed memory")
    }

    @Test
    fun `a battery half gone raises the floor under everything, geometrically`() {
        val full = listOf(null, 1.0, 0.75, PlaceWatchPolicy.SPARING_FROM)
        assertTrue(full.all { batteryFloor(it) == PlaceWatchPolicy.MIN_WAIT }, "nothing to spare before there is")
        // From half to a quarter the floor climbs from two minutes to the hour, and it climbs
        // slowly at first and then all at once: the first half of the fall buys a quarter of it.
        val minutes = listOf(0.45, 0.40, 0.375, 0.35, 0.30, 0.26).map { batteryFloor(it).toMinutes() }
        assertEquals(listOf(3L, 7L, 10L, 15L, 30L, 52L), minutes)
        // Geometric, not linear: halfway down (37.5% left) a straight line would be 31 minutes,
        // and this is ten. The whole shape of it is that the cheap half of the fall is cheap.
        assertTrue(batteryFloor(0.375) < Duration.ofMinutes(31), "${batteryFloor(0.375)} is a straight line")
        // And at a quarter left, the hour, with nothing below it to fall to.
        for (low in listOf(PlaceWatchPolicy.SPARING_FLOOR, 0.10, 0.0)) {
            assertEquals(PlaceWatchPolicy.MAX_WAIT, batteryFloor(low))
        }
    }

    @Test
    fun `a low battery holds every plan back, and takes the GPS at the bottom`() {
        val walkingUp = Movement(speedMps = 1.4, stillStreak = 0)
        val atTheDoor = north(400.0)
        val healthy = planNextCheck(atTheDoor, walkingUp, listOf(home))!!
        assertEquals(PlaceWatchPolicy.MIN_WAIT, healthy.wait)
        assertTrue(healthy.precise, "the last few hundred metres are what the GPS is for")
        // A third of a battery left: the same approach, watched five times less often.
        val sparing = planNextCheck(atTheDoor, walkingUp, listOf(home), charge = 0.35)!!
        assertEquals(batteryFloor(0.35), sparing.wait)
        assertTrue(sparing.precise, "still an approach, still the last few hundred metres")
        // A quarter left: the hour, and no GPS — an hourly look is not an approach.
        val bottom = planNextCheck(atTheDoor, walkingUp, listOf(home), charge = 0.20)!!
        assertEquals(PlaceWatchPolicy.MAX_WAIT, bottom.wait)
        assertFalse(bottom.precise)
    }

    @Test
    fun `what distance already bought, a low battery does not take back`() {
        // A floor, never a cap: three hundred kilometres has bought two and a half hours on the
        // arithmetic of how fast anybody drives, and an empty battery is no reason to look sooner.
        val far = WatchedPlace("far", homeLat + 2.7, homeLng, radiusM = 200, transition = Transition.ENTER, label = "Lejos")
        val resting = Movement(speedMps = 0.0, movedM = 0.0, stillStreak = 10)
        val plan = planNextCheck(north(0.0), resting, listOf(far), charge = 0.05)!!
        assertEquals(reachCeiling(plan.gapM), plan.wait)
        assertTrue(plan.wait > PlaceWatchPolicy.MAX_WAIT)
    }

    @Test
    fun `a phone that stirs is looked at no sooner than a leaving already allowed`() {
        assertEquals(PlaceWatchPolicy.LEAVING_MIN_WAIT, stirredWait(null))
        assertEquals(PlaceWatchPolicy.LEAVING_MIN_WAIT, stirredWait(0.80))
        // Once the battery floor passes it, the floor wins; at the bottom a stir buys nothing,
        // which is the right answer there.
        assertEquals(batteryFloor(0.30), stirredWait(0.30))
        assertEquals(PlaceWatchPolicy.MAX_WAIT, stirredWait(0.20))
    }

    @Test
    fun `a check that got nothing asks less and less often`() {
        val first = Duration.ofMinutes(10)
        assertEquals(listOf(10L, 20L, 40L, 60L, 60L), (0..4).map { blindRetry(it, first).toMinutes() })
        assertEquals(first, blindRetry(-1, first), "a streak that cannot be")
    }

    @Test
    fun `standing still far away backs off all the way to the ceiling`() {
        val plan = planNextCheck(north(3000.0), Movement(speedMps = 0.0, stillStreak = 10), listOf(home))!!
        assertEquals(PlaceWatchPolicy.MAX_WAIT, plan.wait)
    }

    @Test
    fun `nothing to watch is no plan`() {
        assertNull(planNextCheck(north(0.0), Movement(), places = emptyList()))
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
    fun `a sloppy first fix baselines on the side that rings nothing`() {
        // A cell fix a kilometre wide, centred 300 m from home: it cannot say which side of the
        // line the phone is on. Read as "outside", the next good fix at home would ring an
        // arrival at somebody who never left; read as "inside", the real arrival still comes
        // after the watch has seen them leave.
        val sloppy = north(300.0, accuracy = 1200.0)
        assertTrue(insideAfter(null, home, sloppy), "waiting for an arrival: could be inside, so inside")
        assertFalse(insideAfter(null, work.copy(lat = homeLat, lng = homeLng, radiusM = 200), sloppy), "waiting for a leaving: not clearly inside, so outside")
        val settled = stepPlaceWatch(PlaceWatchState(inside = mapOf(home.id to true)), north(50.0), listOf(home), now)
        assertTrue(settled.events.isEmpty(), "a good fix at home after a sloppy one is not an arrival")
        // A good fix reads as the plain answer either way.
        assertTrue(insideAfter(null, home, north(50.0)))
        assertFalse(insideAfter(null, home, north(300.0)))
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
