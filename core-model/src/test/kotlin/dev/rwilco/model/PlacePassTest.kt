package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.math.cos

/**
 * Walking past a small circle without stopping (0.199.0): "sacar dinero, al llegar a la Caixa",
 * fifty metres, walked past on the way home with two looks either side of it and neither inside.
 * Two things answer it — a look often enough to land inside, and a line between two looks that
 * goes through it — and this file pins both, then walks the street to show they add up.
 */
class PlacePassTest {

    // Puerta del Sol, Madrid; the cash machine.
    private val atmLat = 40.4169
    private val atmLng = -3.7035
    private val atm = WatchedPlace("r1#0", atmLat, atmLng, radiusM = 50, transition = Transition.ENTER, label = "Caixa", onCrossing = true)

    /** A fix [east] and [north] metres from the cash machine. */
    private fun at(east: Double, north: Double, at: Instant, accuracy: Double = 10.0) =
        Fix(atmLat + north / 111_195.0, atmLng + east / (111_195.0 * cos(Math.toRadians(atmLat))), accuracy, at)

    /** The phone seen outside [place] once, as the look before the one under test leaves it. */
    private fun seenOutside(fix: Fix, vararg places: WatchedPlace) =
        PlaceWatchState(lastFix = fix, inside = places.associate { it.id to false })

    private val twoMinutesAgo = now.minusSeconds(120)

    // ---- the line between two looks ---------------------------------------------------------

    @Test
    fun `two looks either side of a small circle are a walk through it`() {
        // 85 m west to 85 m east, thirty metres north of the middle: two minutes at 1.4 m/s, a
        // chord of 80 m inside the circle, and both looks outside it.
        val before = at(-85.0, 30.0, twoMinutesAgo)
        val after = at(85.0, 30.0, now)
        assertTrue(passedThrough(atm, before, after))

        val step = stepPlaceWatch(seenOutside(before, atm), after, listOf(atm), now)
        assertEquals(listOf(PlaceEvent(atm.id, Transition.ENTER)), step.events)
        assertEquals(listOf(atm), step.passed)
        assertEquals(false, step.state.inside[atm.id], "the phone is outside now, and the watch says so")
    }

    @Test
    fun `a street that only comes near the circle is not a walk through it`() {
        // Sixty metres north of the middle: ten metres clear of the line all the way along.
        val before = at(-85.0, 60.0, twoMinutesAgo)
        val after = at(85.0, 60.0, now)
        assertFalse(passedThrough(atm, before, after))
        assertTrue(stepPlaceWatch(seenOutside(before, atm), after, listOf(atm), now).events.isEmpty())
        // And forty metres north is inside it, just: the chord is sixty metres.
        assertTrue(passedThrough(atm, at(-85.0, 40.0, twoMinutesAgo), at(85.0, 40.0, now)))
    }

    @Test
    fun `a line that stops short of the circle is not a walk through it`() {
        // Heading straight for it and not there yet: the segment ends 20 m outside the line.
        assertFalse(passedThrough(atm, at(-150.0, 0.0, twoMinutesAgo), at(-70.0, 0.0, now)))
    }

    @Test
    fun `a line too long or too slow to be the way the phone went is not taken`() {
        // 300 m between two looks: round the block is as likely as straight past.
        assertFalse(passedThrough(atm, at(-150.0, 0.0, twoMinutesAgo), at(150.0, 0.0, now)))
        // Six minutes for 170 m: somebody stopped somewhere, and nobody knows where.
        assertFalse(passedThrough(atm, at(-85.0, 0.0, now.minusSeconds(360)), at(85.0, 0.0, now)))
        // The same fix twice, or an older one handed back after a newer: no walk at all.
        assertFalse(passedThrough(atm, at(-85.0, 0.0, now), at(85.0, 0.0, now)))
        assertFalse(passedThrough(atm, at(-85.0, 0.0, now), at(85.0, 0.0, twoMinutesAgo)))
    }

    @Test
    fun `a fix sloppier than the circle draws no line through it`() {
        // The same bar an arrival is held to (insideAfter): 60 m of doubt cannot say which side
        // of a 50 m circle anything was on.
        assertFalse(passedThrough(atm, at(-85.0, 0.0, twoMinutesAgo, accuracy = 60.0), at(85.0, 0.0, now)))
        assertFalse(passedThrough(atm, at(-85.0, 0.0, twoMinutesAgo), at(85.0, 0.0, now, accuracy = 60.0)))
        assertTrue(passedThrough(atm, at(-85.0, 0.0, twoMinutesAgo, accuracy = 50.0), at(85.0, 0.0, now, accuracy = 50.0)))
    }

    @Test
    fun `only an arrival that rings is owed a walk past`() {
        val before = at(-85.0, 0.0, twoMinutesAgo)
        val after = at(85.0, 0.0, now)
        val walkedPast = listOf(
            "a leaving" to atm.copy(id = "exit", transition = Transition.EXIT),
            "a stay" to atm.copy(id = "stay", dwell = Duration.ofMinutes(10)),
            "a routine's question" to atm.copy(id = "asks", crossing = Crossing.ASKS),
            "a routine's reset" to atm.copy(id = "resets", crossing = Crossing.RESETS),
            "a tick taken back" to atm.copy(id = "back", crossing = Crossing.TAKES_BACK),
            "a condition" to atm.copy(id = "cond", crossing = Crossing.NOTHING),
            "hours not open yet" to atm.copy(id = "early", ringsFrom = now.plusSeconds(600)),
        )
        for ((what, place) in walkedPast) {
            val step = stepPlaceWatch(seenOutside(before, place), after, listOf(place), now)
            assertTrue(step.events.isEmpty(), "$what rang for a walk past")
        }
        // A doorway nobody has judged has no side to have passed from.
        assertTrue(stepPlaceWatch(PlaceWatchState(lastFix = before), after, listOf(atm), now).events.isEmpty())
        // A listener never rings, whatever it hears.
        assertTrue(stepPlaceWatch(seenOutside(before, atm), after, emptyList(), now, listening = listOf(atm)).events.isEmpty())
        // A state that rings ("mientras esté en la Caixa") does: being there for forty seconds is being there.
        val state = atm.copy(id = "state", onCrossing = false)
        assertEquals(listOf(PlaceEvent("state", Transition.ENTER)), stepPlaceWatch(seenOutside(before, state), after, listOf(state), now).events)
    }

    @Test
    fun `a look that takes no fix walks nowhere`() {
        // A rest re-reads the stored fix: the same instant twice is no line at all.
        val still = at(-85.0, 0.0, twoMinutesAgo)
        val state = seenOutside(still, atm).copy(stillStreak = 2)
        assertTrue(stepWithoutLooking(state, listOf(atm), now, sensed = false)?.events.orEmpty().isEmpty())
    }

    // ---- how often to look ------------------------------------------------------------------

    private fun walking(speed: Double?) = Movement(speedMps = speed, stillStreak = 0)

    @Test
    fun `on foot and closing on a small circle the look comes before the phone could be through it`() {
        // 300 m south two minutes ago, 150 m south now: 1.25 m/s, closing. The line is 90 m off
        // (less 10 m of doubt), 48 s at the planning speed; the circle is 100 m across, 53 s.
        val previous = at(0.0, -300.0, twoMinutesAgo)
        val fix = at(0.0, -150.0, now)
        val plan = planNextCheck(fix, movementSince(previous, fix, null, 0), listOf(atm), mapOf(atm.id to false), previous = previous)!!
        assertEquals(Duration.ofSeconds(53), plan.wait)
        assertEquals(FixTier.PRECISE, plan.tier)
        // Right at the line the floor is all there is, and never under thirty seconds however
        // brisk the walk: a seven-second fix would be most of the wait.
        val brisk = planNextCheck(at(0.0, -60.0, now), walking(4.5), listOf(atm), mapOf(atm.id to false), previous = at(0.0, -600.0, twoMinutesAgo))!!
        assertEquals(PlaceWatchPolicy.PASS_MIN_WAIT, brisk.wait)
    }

    @Test
    fun `everything else keeps the two-minute floor`() {
        val previous = at(0.0, -300.0, twoMinutesAgo)
        val fix = at(0.0, -150.0, now)
        val inside = mapOf(atm.id to false)
        fun waitFor(place: WatchedPlace = atm, movement: Movement = walking(1.25), from: Fix? = previous, charge: Double? = null) =
            planNextCheck(fix, movement, listOf(place), inside.mapKeys { place.id }, charge, previous = from)!!.wait
        assertEquals(PlaceWatchPolicy.MIN_WAIT, waitFor(place = atm.copy(radiusM = 150)), "a circle wider than two minutes' walk")
        assertEquals(PlaceWatchPolicy.MIN_WAIT, waitFor(movement = walking(8.0)), "a car: no look lands inside at that speed")
        assertEquals(PlaceWatchPolicy.MIN_WAIT, waitFor(movement = walking(null)), "no speed to go on")
        assertEquals(PlaceWatchPolicy.MIN_WAIT, waitFor(from = null), "no earlier look to say it is closing")
        assertEquals(PlaceWatchPolicy.MIN_WAIT, waitFor(from = at(0.0, -100.0, twoMinutesAgo)), "walking away from it")
        assertEquals(PlaceWatchPolicy.MIN_WAIT, waitFor(place = atm.copy(transition = Transition.EXIT)), "a leaving")
        assertEquals(PlaceWatchPolicy.MIN_WAIT, waitFor(place = atm.copy(crossing = Crossing.ASKS)), "a routine's question")
        assertTrue(waitFor(charge = 0.40) > PlaceWatchPolicy.MIN_WAIT, "under half a battery, the battery has the last word")
        assertEquals(Duration.ofSeconds(53), waitFor(charge = 0.60), "with charge to spare it has nothing to say")
    }

    // ---- the street, walked --------------------------------------------------------------------

    /** What a walk along an east-west street [north] metres from the cash machine came to. */
    private class Walk(val events: List<PlaceEvent>, val looks: Int, val quickLooks: Int)

    /**
     * The phone walks east at [speed] from 1.5 km west of the cash machine to 1.5 km east of it,
     * and the watch looks when it planned to — the same loop the phone runs, with every fix
     * taken where the walker actually is by then.
     */
    private fun walkPast(north: Double, speed: Double = 1.4, accuracy: Double = 15.0): Walk {
        var state = PlaceWatchState()
        var clock = now
        var east = -1_500.0
        var looks = 0
        var quick = 0
        val events = ArrayList<PlaceEvent>()
        while (east < 1_500.0) {
            val step = stepPlaceWatch(state, at(east, north, clock, accuracy), listOf(atm), clock)
            looks++
            events += step.events
            state = step.state
            val wait = step.plan!!.wait
            if (wait < PlaceWatchPolicy.MIN_WAIT) quick++
            east += speed * wait.seconds
            clock += wait
        }
        return Walk(events, looks, quick)
    }

    @Test
    fun `walking past a fifty-metre circle rings it once, wherever the street crosses it`() {
        for (north in listOf(0.0, 20.0, 35.0, 45.0)) {
            val walk = walkPast(north)
            assertEquals(listOf(PlaceEvent(atm.id, Transition.ENTER)), walk.events, "$north m from the middle")
            // What it costs: a handful of quick looks on the last stretch, not a quick watch.
            assertTrue(walk.quickLooks <= 4, "${walk.quickLooks} quick looks $north m from the middle")
            assertTrue(walk.looks <= 20, "${walk.looks} looks for a 36-minute walk")
        }
    }

    @Test
    fun `walking down the next street rings nothing`() {
        assertTrue(walkPast(north = 70.0).events.isEmpty())
        assertTrue(walkPast(north = 120.0).events.isEmpty())
    }

    @Test
    fun `a brisk walk past is caught too`() {
        assertEquals(listOf(PlaceEvent(atm.id, Transition.ENTER)), walkPast(north = 30.0, speed = 2.0).events)
    }
}
