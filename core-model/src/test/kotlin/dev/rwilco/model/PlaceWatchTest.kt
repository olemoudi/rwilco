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
    // The doorway reading throughout: this file is about what the watch SEES, and a crossing is
    // the only thing it has to see. The state reading has its own tests below and in
    // PlaceArrivalTest.
    private val home = WatchedPlace("r1#0", homeLat, homeLng, radiusM = 200, transition = Transition.ENTER, label = "Casa", onCrossing = true)
    private val work = WatchedPlace("r2#0", 40.4500, -3.6900, radiusM = 150, transition = Transition.EXIT, label = "Trabajo", onCrossing = true)

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
        assertFalse(plan.tier == FixTier.PRECISE)
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
        assertFalse(plan.tier == FixTier.PRECISE)
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
        assertFalse(walking.tier == FixTier.PRECISE)
        // Driving at 20 m/s, 1.8 km out: a minute at 30 m/s, so the floor.
        val driving = planNextCheck(north(2000.0), Movement(speedMps = 20.0, stillStreak = 0), listOf(home))!!
        assertEquals(PlaceWatchPolicy.MIN_WAIT, driving.wait)
    }

    @Test
    fun `moving near a line means GPS and the fastest cadence`() {
        val plan = planNextCheck(north(400.0), Movement(speedMps = 1.4, stillStreak = 0), listOf(home))!!
        assertEquals(PlaceWatchPolicy.MIN_WAIT, plan.wait)
        assertTrue(plan.tier == FixTier.PRECISE)
    }

    @Test
    fun `near a line but with no speed to go on, the cadence is the fastest and the GPS stays off`() {
        // The first look of a session at home: a phone on a bedside table, most nights.
        val plan = planNextCheck(north(400.0), Movement(speedMps = null, stillStreak = 0), listOf(home))!!
        assertEquals(PlaceWatchPolicy.MIN_WAIT, plan.wait)
        assertFalse(plan.tier == FixTier.PRECISE)
    }

    @Test
    fun `standing still near a line backs off, doubling to a quarter of an hour`() {
        val outside = home.copy(radiusM = 20)
        val waits = (0..5).map { streak -> planNextCheck(north(50.0), Movement(speedMps = 0.0, stillStreak = streak), listOf(outside))!! }
        assertEquals(listOf(2L, 4L, 8L, 15L, 15L, 15L), waits.map { it.wait.toMinutes() })
        assertTrue(waits.none { it.tier == FixTier.PRECISE }, "a phone that is not moving does not need the GPS")
    }

    @Test
    fun `being carried about a house is not an approach`() {
        // The hole this closes: a phone two hundred metres from a watched line, carried about
        // its own four walls all evening. The sensor fires every time, so the phone is never
        // "still"; the fixes wobble without ever getting nearer the line; and "time to the
        // line" is the two-minute floor, over and over — thirty looks an hour, which is more
        // than the log's own idea of a fault ([PlaceWatchPolicy.BUSY_POLLS]).
        val shop = home.copy(id = "shop#0", radiusM = 50)
        var state = PlaceWatchState()
        var at = now
        val waits = (1..6).map { look ->
            // Thirty metres of wobble either side of the same spot, two hundred metres out.
            val fix = north(200.0 + if (look % 2 == 0) 30.0 else -30.0, accuracy = 30.0, at = at)
            val step = stepPlaceWatch(state, fix, listOf(shop), at, sensed = true)
            state = step.state
            at += step.plan!!.wait
            step.plan!!.wait.toMinutes()
        }
        assertEquals(listOf(2L, 2L, 4L, 8L, 15L, 15L), waits, "it backs off exactly as a still phone does")
        assertEquals(5, state.stillStreak, "and the count is what does it")

        // And the moment somebody actually sets off towards the line, the count starts again:
        // this is a back-off about *progress*, not about movement.
        val setOff = stepPlaceWatch(state, north(70.0, accuracy = 30.0, at = at), listOf(shop), at, sensed = true)
        assertEquals(PlaceWatchPolicy.MIN_WAIT, setOff.plan!!.wait, "one look that closes on the line and it is back to two minutes")
        assertEquals(0, setOff.state.stillStreak)

        // And so does a look that changed a side, whatever the distances made of it: walking
        // *into* a place is getting further from its line, and it is the one moment in the
        // evening worth hurrying for.
        val walkedIn = stepPlaceWatch(setOff.state, north(10.0, accuracy = 30.0, at = at), listOf(shop), at, sensed = true)
        assertEquals(true, walkedIn.state.inside[shop.id], "it is inside now")
        assertEquals(0, walkedIn.state.stillStreak, "a crossing is never a look that found nothing")
    }

    @Test
    fun `a circle waiting to be left is never slept past its own half hour`() {
        // [leavingWait] says half an hour is what that case is worth and why — the geofence is
        // the prompt eye and this is the cheap second opinion. The still back-off used to
        // double straight past it to the hour, so a phone resting inside a "cuando salga de
        // aquí" looked once an hour, which is what the owner's log showed with a place snooze
        // pending.
        val leaving = work.copy(radiusM = 150)
        val inside = mapOf(leaving.id to true)
        val fix = Fix(leaving.lat, leaving.lng, 30.0, now)
        val settled = Movement(speedMps = 0.0, sensed = false, stillStreak = 6)
        assertEquals(
            PlaceWatchPolicy.LEAVING_MAX_WAIT,
            planNextCheck(fix, settled, listOf(leaving), inside, previous = fix)!!.wait,
            "the back-off may not outsleep the case's own ceiling",
        )
        // The cap is on the back-off and never on the distance answer: deep inside a place
        // kilometres wide, time-to-the-line is the better number and still wins.
        val wide = leaving.copy(radiusM = 3_000)
        assertTrue(
            planNextCheck(fix, settled, listOf(wide), mapOf(wide.id to true), previous = fix)!!.wait > PlaceWatchPolicy.LEAVING_MAX_WAIT,
            "a three-kilometre circle is not left in half an hour of sitting still",
        )
        // A circle waiting to be *arrived* at, already inside, is the cheapest watch there is
        // and keeps every bit of its sleep.
        val arriving = leaving.copy(transition = Transition.ENTER)
        assertEquals(
            PlaceWatchPolicy.MAX_WAIT,
            planNextCheck(fix, settled, listOf(arriving), mapOf(arriving.id to true), previous = fix)!!.wait,
        )
    }

    @Test
    fun `the motion sensor's word is taken one way only`() {
        val outside = home.copy(radiusM = 20)
        val fix = north(50.0)
        // It felt something: whatever the two fixes made of it, this is not a still phone, and
        // the back-off it had been earning is gone.
        val stirred = planNextCheck(fix, Movement(speedMps = 0.0, sensed = true, stillStreak = 5), listOf(outside))!!
        assertEquals(PlaceWatchPolicy.MIN_WAIT, stirred.wait)
        assertFalse(stirred.tier == FixTier.PRECISE, "and still not a reason to wake the GPS")
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
        assertFalse(rest.plan!!.tier == FixTier.PRECISE)
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
        assertTrue(healthy.tier == FixTier.PRECISE, "the last few hundred metres are what the GPS is for")
        // A third of a battery left: the same approach, watched five times less often.
        val sparing = planNextCheck(atTheDoor, walkingUp, listOf(home), charge = 0.35)!!
        assertEquals(batteryFloor(0.35), sparing.wait)
        assertTrue(sparing.tier == FixTier.PRECISE, "still an approach, still the last few hundred metres")
        // A quarter left: the hour, and no GPS — an hourly look is not an approach.
        val bottom = planNextCheck(atTheDoor, walkingUp, listOf(home), charge = 0.20)!!
        assertEquals(PlaceWatchPolicy.MAX_WAIT, bottom.wait)
        assertFalse(bottom.tier == FixTier.PRECISE)
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
    fun `stirs that keep coming to nothing are worth less each time`() {
        // Inside a place, significant motion is a kitchen as often as it is a departure, and
        // every one of them used to buy a look at five minutes' notice. Each stir a look then
        // finds on the same side of the same line doubles the next one's notice.
        assertEquals(listOf(5L, 10L, 20L, 30L, 30L), (0..4).map { stirredWait(null, it).toMinutes() })
        assertEquals(PlaceWatchPolicy.LEAVING_MAX_WAIT, stirredWait(null, 9), "and never past where the case started")
        assertEquals(PlaceWatchPolicy.LEAVING_MIN_WAIT, stirredWait(null, -1), "a streak that cannot be")
        // The battery floor still has the last word, in both directions.
        assertEquals(batteryFloor(0.30), stirredWait(0.30, 0))
        assertEquals(PlaceWatchPolicy.MAX_WAIT, stirredWait(0.20, 3))
    }

    @Test
    fun `closing is measured against the line, not the ground covered`() {
        // Two hundred metres of walking, from one side of the kitchen to the other: both fixes
        // are a hundred metres from the middle, so the line is exactly as far away as it was and
        // nothing has been closed. This is what the old rule read as a radius' worth of progress.
        val across = closingM(home, north(100.0, accuracy = 10.0), north(-100.0, accuracy = 10.0))!!
        assertEquals(0.0, across, 1.0)
        // A hundred metres of it *towards* the line closes a hundred metres of gap.
        assertEquals(100.0, closingM(home, north(0.0, accuracy = 0.0), north(100.0, accuracy = 0.0))!!, 1.0)
        // Under the two fixes' own doubt is not a step, in either direction.
        assertEquals(0.0, closingM(home, north(100.0, accuracy = 10.0), north(115.0, accuracy = 10.0)))
        // Deeper in is a negative: the door is further away than it was.
        assertTrue(closingM(home, north(150.0, accuracy = 0.0), north(50.0, accuracy = 0.0))!! < 0.0)
        assertNull(closingM(home, null, north(100.0)))
    }

    @Test
    fun `the towers alone answer a line ten kilometres off, and never one nearby`() {
        val far = WatchedPlace("far", homeLat + 0.5, homeLng, radiusM = 200, transition = Transition.ENTER, label = "Lejos")
        val driving = Movement(speedMps = 25.0, stillStreak = 0)
        assertEquals(FixTier.COARSE, planNextCheck(north(0.0), driving, listOf(far))!!.tier)
        // Five kilometres out is inside the threshold, and the ordinary blend answers it.
        val nearer = WatchedPlace("near", homeLat + 0.045, homeLng, radiusM = 200, transition = Transition.ENTER, label = "Cerca")
        assertEquals(FixTier.BALANCED, planNextCheck(north(0.0), driving, listOf(nearer))!!.tier)
        // Inside a place it is the blend whatever else is true: a fix vaguer than the circle has
        // nothing to say about which side of it you are on. (The tier is the winning circle's,
        // like the cadence — a far errand setting the pace brings its own answer with it.)
        val inside = planNextCheck(north(0.0), driving, listOf(home), mapOf(home.id to true))!!
        assertEquals(FixTier.BALANCED, inside.tier)
        // And a low battery takes the satellites away without reaching for the towers instead.
        val approach = planNextCheck(north(400.0), Movement(speedMps = 1.4, stillStreak = 0), listOf(home), charge = 0.20)!!
        assertEquals(FixTier.BALANCED, approach.tier)
    }

    @Test
    fun `a fix already in hand answers a look it is fresh and sharp enough for`() {
        val hour = Duration.ofMinutes(60)
        // An hourly watch is asking an hour-wide question; five minutes old is fresh for it.
        assertTrue(north(0.0, accuracy = 20.0, at = now.minusSeconds(240)).answersFor(now, hour, gapM = 900.0))
        // But never more than the cache's own ceiling, however long the wait.
        assertFalse(north(0.0, accuracy = 20.0, at = now.minusSeconds(600)).answersFor(now, hour, gapM = 900.0))
        // A two-minute watch is walking up to a door, and nothing but now will do.
        assertFalse(north(0.0, accuracy = 20.0, at = now.minusSeconds(240)).answersFor(now, PlaceWatchPolicy.MIN_WAIT, gapM = 900.0))
        // Doubt that reaches the line is not an answer about the line, at any age.
        assertFalse(north(0.0, accuracy = 200.0, at = now).answersFor(now, hour, gapM = 100.0))
        assertFalse(north(0.0, accuracy = 20.0, at = now).answersFor(now, hour, gapM = null))
        // A fix from the future is a clock that moved, not an answer.
        assertFalse(north(0.0, accuracy = 20.0, at = now.plusSeconds(60)).answersFor(now, hour, gapM = 900.0))
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
    fun `with no history the plain answer, and no event for a crossing`() {
        val step = stepPlaceWatch(PlaceWatchState(), north(50.0), listOf(home, work), now)
        assertEquals(mapOf(home.id to true, work.id to false), step.state.inside)
        assertTrue(step.events.isEmpty(), "standing at home when a doorway rule is written is not arriving")
        assertNotNull(step.plan)
        assertEquals(now + step.plan!!.wait, step.state.nextCheckAt)
    }

    @Test
    fun `a doorway a fix cannot settle is left unjudged rather than guessed at`() {
        // A cell fix a kilometre wide, centred 300 m from home: it cannot say which side of the
        // line the phone is on, and the lean it used to be read with wrote "inside" — which for
        // a doorway is silence until the phone has been seen to leave and come back.
        val sloppy = north(300.0, accuracy = 1200.0)
        assertFalse(sloppy.settlesFirstSideOf(home), "1200 m of doubt about a 200 m circle settles nothing")
        val step = stepPlaceWatch(PlaceWatchState(), sloppy, listOf(home), now)
        assertTrue(home.id !in step.state.inside, "an unjudgeable doorway keeps no entry at all")
        assertTrue(step.events.isEmpty(), "and says nothing happened")
        // A fix as tight as the circle settles it, and then the lean is the plain answer again.
        assertTrue(north(50.0).settlesFirstSideOf(home))
        assertTrue(insideAfter(null, home, north(50.0)))
        assertFalse(insideAfter(null, home, north(300.0)))
    }

    @Test
    fun `the arrival a sloppy baseline used to swallow rings`() {
        // The phone at the far end of the street with a network fix, then at the door with a
        // good one. Baselined "inside" off the first, the arrival was not an arrival and the
        // reminder waited for a leaving that was never going to come.
        val approaching = north(300.0, accuracy = 1200.0)
        val outside = stepPlaceWatch(PlaceWatchState(), approaching, listOf(home), now)
        val arriving = stepPlaceWatch(outside.state, north(240.0, accuracy = 30.0), listOf(home), now)
        assertEquals(false, arriving.state.inside[home.id], "the first fix that can answer is the baseline")
        assertTrue(arriving.events.isEmpty(), "and a baseline is not a crossing")
        val arrived = stepPlaceWatch(arriving.state, north(50.0, accuracy = 30.0), listOf(home), now)
        assertEquals(listOf(PlaceEvent(home.id, Transition.ENTER)), arrived.events)
    }

    @Test
    fun `a state and a snooze still lean on a fix that settles nothing`() {
        // Only a doorway waits. "Mientras esté en casa" written at home rings on its first
        // judgement, and that ring is what the lean is for.
        val sloppy = north(300.0, accuracy = 1200.0)
        val state = home.copy(id = "r9#0", onCrossing = false)
        assertTrue(sloppy.settlesFirstSideOf(state))
        assertTrue(insideAfter(null, state, sloppy), "a state leans towards the side it is about")
        // A snooze's first side is the person's own word; no fix is needed to know it.
        val snoozeId = GeofenceIds.encodeSnooze("r1", Trigger.Location(homeLat, homeLng, 200, Presence.INSIDE, "Casa", onCrossing = true))
        val snooze = WatchedPlace(snoozeId, homeLat, homeLng, radiusM = 200, transition = Transition.ENTER, label = "Casa", onCrossing = true)
        assertTrue(sloppy.settlesFirstSideOf(snooze))
        assertFalse(insideAfter(null, snooze, sloppy), "waiting to arrive still starts outside")
    }

    @Test
    fun `a side already seen is never forgotten by a bad fix`() {
        // The skip is for a first judgement only: what the watch knows, it keeps, and
        // insideAfter's own hysteresis is what refuses to move it.
        val sloppy = north(300.0, accuracy = 1200.0)
        val step = stepPlaceWatch(PlaceWatchState(inside = mapOf(home.id to false)), sloppy, listOf(home), now)
        assertEquals(false, step.state.inside[home.id])
        assertTrue(step.events.isEmpty())
    }

    @Test
    fun `a new place is baselined by the next fix, the rest keep their history`() {
        val known = PlaceWatchState(inside = mapOf(home.id to false))
        val step = stepPlaceWatch(known, north(50.0), listOf(home, work), now)
        assertEquals(listOf(PlaceEvent(home.id, Transition.ENTER)), step.events)
        assertEquals(false, step.state.inside[work.id])
    }

    @Test
    fun `a state needs no history, and a crossing does`() {
        // The one line between the two readings: what "nothing known yet" counts as. For a
        // state it is not the other side — the phone is where it is — and for a crossing it is
        // not the other side either, which is why the crossing waits.
        val being = home.copy(onCrossing = false)
        assertEquals(listOf(PlaceEvent(home.id, Transition.ENTER)), stepPlaceWatch(PlaceWatchState(), north(50.0), listOf(being), now).events)
        assertTrue(stepPlaceWatch(PlaceWatchState(), north(50.0), listOf(home), now).events.isEmpty())
        // Once it holds, neither says it twice.
        val held = PlaceWatchState(inside = mapOf(home.id to true))
        assertTrue(stepPlaceWatch(held, north(50.0), listOf(being), now).events.isEmpty())
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
    fun `a circle that only listens is judged by a fix it did not pay for`() {
        // The forty-days-out case: its gate is shut, so it buys nothing — but a fix somebody
        // else's doorstep paid for is already in hand, and judging one more circle against it
        // costs arithmetic. What that buys is the baseline for the morning the gate opens.
        val gated = home.copy(id = "r3#0")
        val step = stepPlaceWatch(PlaceWatchState(), north(3000.0), listOf(work), now, listening = listOf(gated))
        assertEquals(mapOf(work.id to false, gated.id to false), step.state.inside)
    }

    @Test
    fun `a listener neither rings nor asks for a look of its own`() {
        // Standing at its own door and walking through it. An asking circle would ring and
        // would hold the cadence at the floor; a listener does neither — it cannot ring,
        // because the reminder behind it cannot, and the look it would ask for is the one its
        // gate exists to save.
        val gated = home.copy(id = "r3#0")
        val far = work.copy(lat = homeLat + 2.7, transition = Transition.ENTER)
        val outside = PlaceWatchState(inside = mapOf(gated.id to false))
        val listened = stepPlaceWatch(outside, north(50.0), listOf(far), now, listening = listOf(gated))
        assertTrue(listened.events.isEmpty(), "a circle nobody is waiting on reported a crossing")
        assertEquals(true, listened.state.inside[gated.id], "but it does know where the phone is")
        assertEquals(far.id, listened.plan!!.nearest.id, "a listener voted on the cadence")
        assertTrue(listened.plan!!.wait > PlaceWatchPolicy.STILL_NEAR_MAX, "and pulled the look forward")
        // The same circle, asking: the ring and the two-minute cadence both come back.
        val asked = stepPlaceWatch(outside, north(50.0), listOf(far, gated), now)
        assertEquals(listOf(PlaceEvent(gated.id, Transition.ENTER)), asked.events)
        assertEquals(gated.id, asked.plan!!.nearest.id)
        assertTrue(asked.plan!!.wait < listened.plan!!.wait, "the near circle should have taken the cadence over")
    }

    @Test
    fun `a look that takes no fix at all still keeps its listeners up to date`() {
        // The cheapest check there is — the sensor felt nothing, so the stored fix is read
        // again — and the listeners are judged by it like everybody else. Free twice over.
        val gated = home.copy(id = "r3#0")
        val resting = PlaceWatchState(lastFix = north(3000.0, at = now), inside = mapOf(work.id to false), stillStreak = 3)
        val step = stepWithoutLooking(resting, listOf(work), now.plusSeconds(600), sensed = false, listening = listOf(gated))!!
        assertEquals(false, step.state.inside[gated.id])
        assertTrue(step.events.isEmpty())
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

    @Test
    fun `a snooze circle's first side is the person's word, not the fix`() {
        // "Al llegar a casa" said from the metro: the wait starts OUTSIDE whatever a doubtful
        // fix reads, because geometry leaning "could be inside, so inside" baselined the wait
        // as already over — and a snooze has no clock and no net behind that silence.
        val arriveId = GeofenceIds.encodeSnooze("r1", Trigger.Location(homeLat, homeLng, 200, Presence.INSIDE, "Casa", onCrossing = true))
        val arrive = WatchedPlace(arriveId, homeLat, homeLng, radiusM = 200, transition = Transition.ENTER, label = "Casa", onCrossing = true)
        assertFalse(insideAfter(null, arrive, north(100.0, accuracy = 500.0)), "waiting to arrive starts outside, however sloppy the fix")
        assertFalse(insideAfter(null, arrive, north(50.0)), "even a good fix does not outrank the person's word")
        // "Al salir de aquí": the wait starts INSIDE — the leaving is still ahead.
        val leaveId = GeofenceIds.encodeSnooze("r1", Trigger.Location(homeLat, homeLng, 150, Presence.OUTSIDE, "aquí", onCrossing = true))
        val leave = WatchedPlace(leaveId, homeLat, homeLng, radiusM = 150, transition = Transition.EXIT, label = "aquí", onCrossing = true)
        assertTrue(insideAfter(null, leave, north(5000.0)), "waiting to leave starts inside")
    }

    @Test
    fun `with a side already seen a snooze circle is judged like any other`() {
        val arriveId = GeofenceIds.encodeSnooze("r1", Trigger.Location(homeLat, homeLng, 200, Presence.INSIDE, "Casa", onCrossing = true))
        val arrive = WatchedPlace(arriveId, homeLat, homeLng, radiusM = 200, transition = Transition.ENTER, label = "Casa", onCrossing = true)
        assertTrue(insideAfter(false, arrive, north(50.0, accuracy = 30.0)), "a clear arrival flips it, and that flip is the ring")
        assertFalse(insideAfter(false, arrive, north(100.0, accuracy = 500.0)), "and doubt still changes nothing")
    }
}
