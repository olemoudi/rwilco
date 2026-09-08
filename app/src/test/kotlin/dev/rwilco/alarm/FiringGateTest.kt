package dev.rwilco.alarm

import dev.rwilco.model.Condition
import dev.rwilco.model.Fix
import dev.rwilco.model.PlaceWatchPolicy
import dev.rwilco.model.PlaceWatchState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The gate every firing comes through: the fences only a position can answer, judged against what
 * the place watch remembers ([firstFailingWhere]).
 *
 * It had no test at all, and that is exactly where the app lost "y sólo si voy en coche": the
 * split was `it is Condition.AtPlace`, so a speed fence was asked with no fix and answered *true*
 * for ever. [dev.rwilco.model.Condition.holdsAt] was right the whole time and its own test passed;
 * the journey harness (`Simulation`) only ever evaluates the fences a clock can settle, so nothing
 * in the suite ran this line.
 */
class FiringGateTest {

    private val zone: ZoneId = ZoneId.of("Europe/Madrid")
    private val now: Instant = LocalDateTime.of(2026, 8, 27, 19, 0).atZone(zone).toInstant()

    private val home = Condition.AtPlace(40.4168, -3.7038, 150, "casa", inside = true)
    private val driving = Condition.Moving()
    private val onTheMove = Condition.Moving(PlaceWatchPolicy.WALK_MPS)

    /** A fix at the pin, so distance never decides the place cases; the speed is what varies. */
    private fun fix(speed: Double?, at: Instant = now) = Fix(home.lat, home.lng, 20.0, at, speedMps = speed)

    private fun watch(speed: Double?, at: Instant = now) = PlaceWatchState(lastFix = fix(speed, at))

    private fun failing(conditions: List<Condition>, watch: PlaceWatchState, moment: Instant = now) =
        firstFailingWhere(conditions, now, moment, zone, watch)

    /** The whole gate, counting how many times it went to the watch for a position. */
    private fun gate(
        conditions: List<Condition>,
        watch: PlaceWatchState,
        askAll: Boolean = true,
        moment: Instant = now,
    ): Pair<Condition?, Int> {
        var reads = 0
        val failed = runBlocking {
            firstFailing(conditions, askAll, now, moment, zone) { reads++; watch }
        }
        return failed to reads
    }

    @Test
    fun `a walk does not clear the fence that asks for a car`() {
        // The bug, in one line: 1,2 m/s is a walk, the rule says "en coche", and the firing has
        // to be dropped. It used to ring — and worse, a place that counts a routine as done
        // counted "mover el coche" done on a stroll past the car, and the app then said nothing
        // for three weeks.
        assertEquals(driving, failing(listOf(driving), watch(1.2)))
        assertEquals(driving, failing(listOf(driving), watch(0.0)), "standing still clears nothing")
        assertNull(failing(listOf(driving), watch(12.0)), "43 km/h is a car")
        assertEquals(onTheMove, failing(listOf(onTheMove), watch(0.2)), "and the walking floor still has a floor")
        assertNull(failing(listOf(onTheMove), watch(1.6)))
    }

    @Test
    fun `what nobody can vouch for holds`() {
        // The house rule, and the whole reason the fence reads the way round it does: ringing
        // once too often is a failure somebody can see and dismiss.
        assertNull(failing(listOf(driving), PlaceWatchState()), "no fix at all")
        assertNull(failing(listOf(driving), watch(null)), "a fix with no speed on it — the first of a run")
        val stale = watch(1.2, at = now.minus(PlaceWatchPolicy.SPEED_MEMORY).minusSeconds(60))
        assertNull(failing(listOf(driving), stale), "a fix too old to speak for the moment")
    }

    @Test
    fun `a speed is asked of the moment the firing is about, not of the hour it arrives`() {
        // "Al salir de casa, y sólo si voy en coche", slept through at nine and caught up at
        // noon, is a question about nine o'clock. A fix taken at noon does not answer it, so the
        // fence holds and the reminder rings late rather than never.
        val missed = now.minus(PlaceWatchPolicy.SPEED_MEMORY).minusSeconds(3600)
        assertNull(failing(listOf(driving), watch(1.2), moment = missed), "nothing could say how fast, then")
        // And a fix that does still speak for that moment answers for it.
        val nearby = now.minusSeconds(600)
        assertEquals(driving, failing(listOf(driving), watch(1.2, at = nearby), moment = nearby))
    }

    @Test
    fun `a place is asked of the watch's memory, and the first refusal is the one named`() {
        val away = Condition.AtPlace(41.3874, 2.1686, 150, "oficina", inside = true)
        val inside = PlaceWatchState(lastFix = fix(12.0))
        assertNull(failing(listOf(home), inside), "the fix is at the pin")
        assertEquals(away, failing(listOf(away), inside), "and four hundred kilometres away is not")
        // Which one says no is the whole point of naming it: the place comes first here because
        // it is first in the list, not because places outrank speeds.
        assertEquals(away, failing(listOf(away, driving), watch(1.2)))
        assertEquals(driving, failing(listOf(driving, away), watch(1.2)))
    }

    @Test
    fun `the gate routes every fence a clock cannot settle to the watch`() {
        // The bug was in the *split*, not in the judging: a speed left among the hours is asked
        // with no fix and holds for ever. So the whole gate is walked here, and the split is
        // [Condition.knownInAdvance] rather than a list of types — the same predicate the
        // scheduler leaves them out by, which is what keeps the two ends agreeing.
        assertEquals(driving, gate(listOf(driving), watch(1.2)).first, "a speed is not something a clock can settle")
        assertEquals(home, gate(listOf(home), PlaceWatchState(lastFix = fix(12.0, at = now).copy(lat = 41.3874, lng = 2.1686))).first)
        val afternoon = Condition.TimeWindow(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(10, 0))
        assertEquals(afternoon, gate(listOf(afternoon, driving), watch(12.0)).first, "the hour is asked first")
    }

    @Test
    fun `the watch is only asked once something needs a position`() {
        // An hour that has passed costs nothing to check; a position costs a store read. Order,
        // not just outcome: it is half of what this gate does.
        assertEquals(0, gate(listOf(Condition.OnDays(setOf(java.time.DayOfWeek.MONDAY))), watch(12.0)).second, "a fence a clock settles asks nobody")
        val shut = Condition.TimeWindow(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(10, 0))
        assertEquals(0, gate(listOf(shut, driving), watch(12.0)).second, "and one that already said no stops the walk")
        assertEquals(1, gate(listOf(driving), watch(12.0)).second, "a speed needs the watch")
        assertEquals(1, gate(listOf(home, driving), watch(12.0)).second, "and a place and a speed need it once between them")
    }

    @Test
    fun `an alarm asks only what nothing could settle in advance, a place asks the lot`() {
        // askAll false is the clock's own firing: the moment was armed inside its window, and
        // asking the hours again would silence a moment the phone slept through. The speed and
        // the place are still asked, because nothing ever could ask them.
        val shut = Condition.TimeWindow(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(10, 0))
        assertNull(gate(listOf(shut), watch(12.0), askAll = false).first, "the window was judged when it was armed")
        assertEquals(driving, gate(listOf(shut, driving), watch(1.2), askAll = false).first, "the speed never was")
        assertEquals(shut, gate(listOf(shut, driving), watch(1.2), askAll = true).first, "a place trigger asks the lot")
    }

    @Test
    fun `a place and a speed on the same rule both have to hold`() {
        assertNull(failing(listOf(home, driving), watch(12.0)), "at home, in a car")
        assertEquals(driving, failing(listOf(home, driving), watch(1.2)), "at home, on foot")
        assertNull(failing(emptyList(), watch(1.2)), "and a rule with no fences of this kind never fails here")
    }
}
