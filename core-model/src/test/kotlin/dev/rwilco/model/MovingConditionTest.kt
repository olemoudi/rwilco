package dev.rwilco.model

import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * "Y sólo si voy en coche" ([Condition.Moving]).
 *
 * The fence that tells leaving for the evening from walking to the bins, and the one the app is
 * asked twice about: a ring lets an unanswerable speed through (nobody could say, so it rings),
 * and a place that counts a routine as done does not (nobody could say, so it keeps its hands
 * off a three-week count). Both readings are here.
 */
class MovingConditionTest {

    private val now: Instant = local(2026, 8, 27, 19, 0)
    private val driving = Condition.Moving()
    private val walking = Condition.Moving(PlaceWatchPolicy.WALK_MPS)

    private fun fix(speed: Double?, at: Instant = now) = Fix(40.4, -3.7, 20.0, at, speedMps = speed)

    @Test
    fun `a speed over the fence holds and one under it does not`() {
        assertTrue(driving.holdsAt(now, zone, fix(12.0)), "43 km/h is a car")
        assertFalse(driving.holdsAt(now, zone, fix(1.6)), "a walk is not")
        assertTrue(driving.holdsAt(now, zone, fix(PlaceWatchPolicy.DRIVING_MPS)), "the fence itself counts")
        assertTrue(walking.holdsAt(now, zone, fix(1.6)), "and a walk clears the walking fence")
        assertFalse(walking.holdsAt(now, zone, fix(0.2)), "standing still clears nothing")
    }

    @Test
    fun `what nobody can vouch for holds`() {
        assertTrue(driving.holdsAt(now, zone, where = null), "no fix at all")
        assertTrue(driving.holdsAt(now, zone, fix(null)), "a fix with no speed on it — the first of a run")
        // Too old to speak for the moment: the watch's memory is the same one a place uses.
        val stale = fix(12.0, at = now.minus(PlaceWatchPolicy.SPEED_MEMORY).minusSeconds(60))
        assertTrue(driving.holdsAt(now, zone, stale))
        assertNull(speedAt(now, stale), "and nothing can be read off it")
        assertEquals(12.0, speedAt(now, fix(12.0)))
    }

    @Test
    fun `a reset asks the other way round`() {
        val fenced = listOf<Condition>(driving)
        assertTrue(fenced.speedUnvouched(now, where = null), "no fix: the app keeps its hands off")
        assertTrue(fenced.speedUnvouched(now, fix(null)))
        assertFalse(fenced.speedUnvouched(now, fix(0.1)), "a speed it can read is an answer, even a slow one")
        assertFalse(fenced.speedUnvouched(now, fix(12.0)))
        // A rule with no speed fence on it is never held up by this.
        assertFalse(listOf(Condition.OnMonthDays(setOf(1))).speedUnvouched(now, where = null))
        assertFalse(emptyList<Condition>().speedUnvouched(now, where = null))
    }

    @Test
    fun `nothing knows how fast anybody will be going next Tuesday`() {
        assertFalse(driving.knownInAdvance, "so the scheduler leaves it out and asks at the ring")
        assertEquals(emptySet<java.time.DayOfWeek>(), (driving as Condition).namedDays)
        assertNull(driving.place)
    }

    @Test
    fun `a speed no road reaches, and one nothing is under, are refused`() {
        assertEquals(TriggerProblem.SPEED_OUT_OF_RANGE, problemOf(Condition.Moving(0.0)))
        assertEquals(TriggerProblem.SPEED_OUT_OF_RANGE, problemOf(Condition.Moving(-1.0)))
        assertEquals(TriggerProblem.SPEED_OUT_OF_RANGE, problemOf(Condition.Moving(100.0)))
        assertNull(problemOf(driving))
        assertNull(problemOf(walking))
    }

    @Test
    fun `the watch writes the speed it measured onto the fix it keeps`() {
        // Two looks, three kilometres apart, five minutes between them — 36 km/h, which is a
        // car through town. The second fix carries the speed, which is what "y sólo si voy en
        // coche" is read from later. (A kilometre in those five minutes is 12 km/h and reads
        // as a bicycle: under the fence, on purpose.)
        val first = Fix(40.4000, -3.7000, 15.0, now.minusSeconds(300))
        val second = Fix(40.4270, -3.7000, 15.0, now)
        val state = PlaceWatchState(lastFix = first)
        val step = stepPlaceWatch(state, second, emptyList(), now = now, charge = 0.8)
        val kept = step.state.lastFix
        assertTrue(kept?.speedMps != null, "the fix remembers how fast the phone was going")
        assertTrue(kept!!.speedMps!! > PlaceWatchPolicy.DRIVING_MPS, "a kilometre in five minutes is not a walk")
        assertTrue(driving.holdsAt(now, zone, kept))
    }
}
