package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * "Al llegar a casa, y cuando lleve diez minutos allí."
 *
 * What these pin is the bargain the rate makes: it never rings early, it forgives a position or
 * two on the wrong side, it gives up on somebody who actually left, and when it cannot be
 * measured at all it says so instead of going quiet. The arithmetic is in time and not in
 * readings, so every one of these is written as a sequence of looks at chosen moments — which is
 * exactly what the watch does, and what a percentage of a series could never have survived.
 */
class PlaceDwellTest {

    private val homeLat = 40.4169
    private val homeLng = -3.7035
    private val ten: Duration = Duration.ofMinutes(10)
    private val home = WatchedPlace(
        "r1#0", homeLat, homeLng, radiusM = 200, transition = Transition.ENTER,
        label = "Casa", onCrossing = true, dwell = ten,
    )

    /** A fix [metres] due north of home; inside the circle under 200 m. */
    private fun north(metres: Double, at: Instant, accuracy: Double = 10.0) =
        Fix(homeLat + metres / 111_195.0, homeLng, accuracy, at)

    /** One look at [at]: the phone [metres] from the centre of the circle. */
    private fun look(state: PlaceWatchState, metres: Double, at: Instant) =
        stepPlaceWatch(state, north(metres, at), listOf(home), at)

    /** Outside, then in: the state a count starts from, and the moment it started. */
    private fun arrived(): Pair<PlaceWatchState, Instant> {
        val outside = look(PlaceWatchState(), 900.0, now).state
        val step = look(outside, 50.0, now.plusSeconds(120))
        assertTrue(step.events.isEmpty(), "a rate must not ring at the doorstep")
        assertEquals(1, step.state.dwelling.size, "the crossing opens a count")
        return step.state to now.plusSeconds(120)
    }

    @Test
    fun `the crossing starts a count and the count is what rings`() {
        var (state, at) = arrived()
        // dwellWait is 2m30 for a ten-minute rate, so four looks carry it.
        val rung = mutableListOf<Instant>()
        repeat(4) {
            at = at.plus(dwellWait(ten))
            val step = look(state, 50.0, at)
            state = step.state
            step.events.forEach { rung += at }
        }
        assertEquals(1, rung.size, "it rings once, and once only")
        assertEquals(now.plusSeconds(120).plus(ten), rung.single(), "ten minutes after the door, to the second")
        assertTrue(state.dwelling.isEmpty(), "a count that rang is over")
    }

    @Test
    fun `it never rings before the rate is up`() {
        var (state, at) = arrived()
        repeat(3) {
            at = at.plus(dwellWait(ten))
            val step = look(state, 50.0, at)
            assertTrue(step.events.isEmpty(), "three looks is seven and a half minutes, not ten")
            state = step.state
        }
        assertEquals(Duration.ofMinutes(7).plusSeconds(30).toMillis(), state.dwelling.getValue(home.id).heldMs)
    }

    @Test
    fun `one position on the wrong side is forgiven`() {
        var (state, at) = arrived()
        // Four looks inside, one of which lands outside and comes back: 2m30 of straying, which
        // is inside the 3m20 budget, so the count goes on and simply takes a look longer.
        val where = listOf(50.0, 400.0, 50.0, 50.0, 50.0)
        val rang = mutableListOf<Instant>()
        for (metres in where) {
            at = at.plus(dwellWait(ten))
            val step = look(state, metres, at)
            state = step.state
            step.events.forEach { rang += at }
        }
        assertEquals(1, rang.size, "a trip to the bins is not leaving")
        assertEquals(now.plusSeconds(120).plus(ten).plus(dwellWait(ten)), rang.single(), "and it costs exactly the look it lost")
    }

    @Test
    fun `somebody who leaves is given up on, in silence`() {
        var (state, at) = arrived()
        // Two looks away is five minutes, which is past the third of the rate the budget allows.
        repeat(2) {
            at = at.plus(dwellWait(ten))
            state = look(state, 900.0, at).state
        }
        assertTrue(state.dwelling.isEmpty(), "the count is dropped")
        // And nothing rings afterwards for simply coming back: an arrival has to be arrived at.
        at = at.plus(dwellWait(ten))
        val back = look(state, 50.0, at)
        assertTrue(back.events.isEmpty(), "coming back is a crossing, and a crossing starts a count")
        assertEquals(1, back.state.dwelling.size)
    }

    @Test
    fun `a look cannot vouch for longer than the count asked it to wait`() {
        val (state, at) = arrived()
        // The process was gone for an hour. One look inside, and a credit of two and a half
        // minutes — not sixty, which would ring for a rate nobody measured.
        val step = look(state, 50.0, at.plus(Duration.ofHours(1)))
        assertTrue(step.events.isEmpty(), "an hour's silence is not ten minutes at home")
        val counted = step.state.dwelling.getValue(home.id)
        assertEquals(dwellWait(ten).toMillis(), counted.heldMs)
        assertEquals(Duration.ofHours(1).minus(dwellWait(ten)).toMillis(), counted.blindMs)
    }

    @Test
    fun `a rate nothing could measure is given up on out loud`() {
        var (state, at) = arrived()
        // The battery floor holds every look an hour apart. Each credits its two and a half
        // minutes, the blind time piles up, and at the two-hour ceiling it stops.
        var said: List<WatchedPlace> = emptyList()
        repeat(3) {
            at = at.plus(Duration.ofHours(1))
            val step = look(state, 50.0, at)
            state = step.state
            if (step.unmeasured.isNotEmpty()) said = step.unmeasured
        }
        assertEquals(listOf(home), said, "nothing rang, and that is worth saying")
        assertTrue(state.dwelling.isEmpty())
    }

    @Test
    fun `a leaving rate counts the other side`() {
        val leaving = home.copy(id = "r2#0", transition = Transition.EXIT)
        var state = stepPlaceWatch(PlaceWatchState(), north(50.0, now), listOf(leaving), now).state
        var at = now.plusSeconds(120)
        val out = stepPlaceWatch(state, north(900.0, at), listOf(leaving), at)
        assertTrue(out.events.isEmpty(), "going out of the door is not being out")
        state = out.state
        val rang = mutableListOf<Instant>()
        repeat(4) {
            at = at.plus(dwellWait(ten))
            val step = stepPlaceWatch(state, north(900.0, at), listOf(leaving), at)
            state = step.state
            step.events.forEach { rang += at }
        }
        assertEquals(1, rang.size, "ten minutes away is ten minutes away")
        // And stepping back inside spends the budget exactly as straying out does the other way.
        assertTrue(state.dwelling.isEmpty())
    }

    @Test
    fun `a count outranks every floor the watch has, and never wakes the satellites`() {
        val (state, at) = arrived()
        val step = look(state, 50.0, at.plus(dwellWait(ten)))
        val plan = step.plan!!
        // At most what the count asks for, and the ordinary arithmetic is free to ask sooner —
        // what it may never do is the half hour INSIDE_MIN_WAIT would have given a phone that
        // has just come home, which measures a ten-minute stay as nothing at all.
        assertTrue(plan.wait <= dwellWait(ten), "${plan.wait} is not measuring ten minutes")
        assertEquals(FixTier.BALANCED, plan.tier, "inside a circle the satellites answer nothing")
        // Even under the "todos" floor, which is an hour.
        val slowed = listOf(home.copy(floor = PlaceWatchPolicy.MAX_WAIT))
        val floored = stepPlaceWatch(state, north(50.0, at.plus(dwellWait(ten))), slowed, at.plus(dwellWait(ten)))
        assertEquals(dwellWait(ten), floored.plan!!.wait)
    }

    @Test
    fun `the battery still has the last word`() {
        val (state, at) = arrived()
        val step = stepPlaceWatch(state, north(50.0, at.plus(dwellWait(ten))), listOf(home), at.plus(dwellWait(ten)), charge = 0.20)
        assertEquals(PlaceWatchPolicy.MAX_WAIT, step.plan!!.wait, "an empty battery is not measured out of")
    }

    @Test
    fun `a count is never rested through`() {
        val (state, at) = arrived()
        // A phone that has been still for a while: the sensor felt nothing and the fixes agree.
        val still = state.copy(stillStreak = 3)
        assertNull(
            stepWithoutLooking(still, listOf(home), at.plusSeconds(60), sensed = false),
            "the one thing there is to see is the count",
        )
        // With no rate on the circle the same state rests as it always did.
        val plain = listOf(home.copy(dwell = null))
        assertTrue(stepWithoutLooking(still, plain, at.plusSeconds(60), sensed = false) != null)
    }

    @Test
    fun `what a rate costs is four looks, and the circle is cheap again after them`() {
        // Driven the way the phone drives it: a look, the wait it chose itself, another look.
        // The phone arrives home and stays put, which is the ordinary case and the one whose
        // price is worth knowing — the whole feature is four wifi positions after an arrival.
        var state = look(PlaceWatchState(), 900.0, now).state
        var at = now.plusSeconds(120)
        var looks = 0
        var rangAt: Instant? = null
        val tiers = mutableSetOf<FixTier>()
        while (at < now.plus(Duration.ofHours(2))) {
            val step = look(state, 50.0, at)
            looks++
            tiers += state.tier
            if (step.events.isNotEmpty() && rangAt == null) rangAt = at
            state = step.state
            val plan = step.plan ?: break
            if (rangAt != null && at > rangAt.plus(Duration.ofMinutes(20))) break
            at = at.plus(plan.wait)
        }
        // Never early, and never more than one look late: the count is measured in vouched time,
        // so the ring lands on the first look whose credit carries it past the rate.
        val door = now.plusSeconds(120)
        assertTrue(rangAt != null && rangAt!! >= door.plus(ten), "rang at $rangAt, before ten minutes were up")
        assertTrue(rangAt!! <= door.plus(ten).plus(dwellWait(ten)), "rang at $rangAt, more than a look late")
        assertTrue(looks in 5..8, "an arrival and four or five looks: was $looks")
        assertTrue(FixTier.PRECISE !in tiers, "nothing here is worth the satellites")
        // And once it has rung, the circle is back to the cheapest watch there is: the only
        // thing that can happen indoors is going out, and that is half an hour away.
        val after = look(state, 50.0, at).plan!!
        assertEquals(PlaceWatchPolicy.INSIDE_MIN_WAIT, after.wait)
    }

    @Test
    fun `the numbers say what they claim to say`() {
        assertEquals(Duration.ofSeconds(150), dwellWait(ten))
        assertEquals(Duration.ofSeconds(200), dwellTolerance(ten))
        // A quarter of the series at the finishing line, which is the promise: 3:20 of 13:20.
        assertEquals(0.25, dwellTolerance(ten).toMillis().toDouble() / (ten + dwellTolerance(ten)).toMillis(), 1e-9)
        assertEquals(Duration.ofSeconds(800), dwellCeiling(ten, blindMs = 0))
        // A rate under four times MIN_WAIT still gets MIN_WAIT, never less.
        assertEquals(PlaceWatchPolicy.MIN_WAIT, dwellWait(Duration.ofMinutes(5)))
        // And the longest rate anybody may ask for fits inside the ceiling with its tolerance.
        val longest = Duration.ofMinutes(MAX_DWELL_MINUTES.toLong())
        assertEquals(PlaceWatchPolicy.DWELL_CEILING, longest + dwellTolerance(longest))
    }
}
