package dev.rwilco.model

import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/**
 * "Al llegar a casa, y cuando lleve **al menos** diez minutos allí, entre las 19:00 y las 21:30."
 *
 * A rate met is met for as long as the phone stays. What these pin is the case that used to fall
 * on the floor: home at 18:45, ten minutes at 18:55, and the hours not open until 19:00 — the
 * firing refused the arrival for being early and nothing ever offered it again (the owner's
 * phone, 2026-09-30). Now the watch holds it and hands it on when the hours open, as long as
 * nobody has left in between; and it hands it on once.
 */
class PlaceHoldTest {

    private val homeLat = 40.4169
    private val homeLng = -3.7035
    private val ten: Duration = Duration.ofMinutes(10)

    /** Wednesday 2026-09-30, the day it happened. */
    private fun wed(hour: Int, minute: Int, second: Int = 0): Instant = local(2026, 9, 30, hour, minute).plusSeconds(second.toLong())

    private val opening = wed(19, 0)
    private val home = WatchedPlace(
        "r1#0", homeLat, homeLng, radiusM = 50, transition = Transition.ENTER,
        label = "Casa", onCrossing = true, dwell = ten,
    )

    /** The circle as the gate hands it over at [at]: waiting for the hours until they open. */
    private fun gated(at: Instant) = home.copy(ringsFrom = opening.takeIf { it > at })

    /** A fix [metres] due north of home. */
    private fun north(metres: Double, at: Instant, accuracy: Double = 10.0) =
        Fix(homeLat + metres / 111_195.0, homeLng, accuracy, at)

    private fun look(state: PlaceWatchState, metres: Double, at: Instant) =
        stepPlaceWatch(state, north(metres, at), listOf(gated(at)), at)

    /** Outside at 18:40, in at 18:45, and four looks of a count: met at 18:55, before the hours. */
    private fun metEarly(): WatchStep {
        var state = look(PlaceWatchState(), 900.0, wed(18, 40)).state
        state = look(state, 10.0, wed(18, 45)).state
        assertEquals(1, state.dwelling.size, "the arrival opens a count")
        var at = wed(18, 45)
        var step: WatchStep? = null
        repeat(4) {
            at = at.plus(dwellWait(ten))
            step = look(state, 10.0, at)
            state = step!!.state
        }
        assertEquals(wed(18, 55), at)
        return step!!
    }

    @Test
    fun `a rate met before its hours is held, not rung`() {
        val step = metEarly()
        assertTrue(step.events.isEmpty(), "the hours are not open yet")
        assertEquals(wed(18, 55), step.state.held[home.id], "held from the moment it was met")
        assertTrue(step.state.dwelling.isEmpty(), "the count is over; what is left is the hold")
        assertEquals(listOf(home.id), step.held.map { it.id }, "and the step says so, for the log")
    }

    @Test
    fun `the watch looks again at the moment the hours open`() {
        val step = metEarly()
        assertEquals(opening, step.heldUntil)
        assertEquals(opening, step.state.nextCheckAt, "the plan ends at the opening, not half an hour past it")
    }

    @Test
    fun `still there when the hours open, it rings then, and once`() {
        val held = metEarly().state
        val open = look(held, 10.0, opening)
        assertEquals(listOf(PlaceEvent(home.id, Transition.ENTER)), open.events)
        assertTrue(open.state.held.isEmpty(), "a hold rings once")
        assertNull(open.heldUntil)

        val later = look(open.state, 10.0, opening.plus(Duration.ofMinutes(30)))
        assertTrue(later.events.isEmpty(), "staying on is not arriving again")
    }

    @Test
    fun `a look before the opening leaves the hold where it is`() {
        val held = metEarly().state
        val step = look(held, 10.0, wed(18, 58))
        assertTrue(step.events.isEmpty())
        assertEquals(wed(18, 55), step.state.held[home.id], "the hold keeps the moment it was met")
        assertEquals(opening, step.state.nextCheckAt)
    }

    @Test
    fun `leaving before the hours open lets the hold go`() {
        val held = metEarly().state
        val out = look(held, 900.0, wed(18, 58))
        assertTrue(out.state.held.isEmpty(), "somebody who left has to arrive again")
        val back = look(out.state, 10.0, opening)
        assertTrue(back.events.isEmpty(), "coming back is a crossing, and a crossing starts a count")
        assertEquals(1, back.state.dwelling.size)
    }

    @Test
    fun `a position on the line keeps the hold, as it keeps the side`() {
        // 70 m out with 30 m of doubt could be inside a 50 m circle: the hysteresis holds the
        // phone where it was, and so does the hold.
        val held = metEarly().state
        val step = stepPlaceWatch(held, north(70.0, wed(18, 58), accuracy = 30.0), listOf(gated(wed(18, 58))), wed(18, 58))
        assertEquals(wed(18, 55), step.state.held[home.id])
    }

    @Test
    fun `a hold goes with the circle it belongs to`() {
        // Dealt with, edited, its gate shut: a circle nobody is asking a position for any more
        // cannot ring, and a hold is a ring waiting to happen — the same reason a count goes.
        val held = metEarly().state
        val gone = stepPlaceWatch(held, north(10.0, wed(18, 58)), emptyList(), wed(18, 58), listening = listOf(gated(wed(18, 58))))
        assertTrue(gone.state.held.isEmpty())
    }

    @Test
    fun `a rest never swallows a hold that is due`() {
        // A rest reports nothing by contract, so a hold released in one would be written off
        // unrung. Before the opening a rest is fine: the plan still ends there.
        val held = metEarly().state.copy(stillStreak = 3)
        assertNull(stepWithoutLooking(held, listOf(gated(opening)), opening, sensed = false))
        val early = stepWithoutLooking(held, listOf(gated(wed(18, 57))), wed(18, 57), sensed = false)
        assertNotNull(early)
        assertTrue(early!!.events.isEmpty())
        assertEquals(opening, early.state.nextCheckAt)
    }

    @Test
    fun `a rate met inside its hours rings at once, as it always did`() {
        var state = look(PlaceWatchState(), 900.0, wed(19, 10)).state
        state = look(state, 10.0, wed(19, 15)).state
        var at = wed(19, 15)
        val rang = mutableListOf<Instant>()
        repeat(4) {
            at = at.plus(dwellWait(ten))
            val step = look(state, 10.0, at)
            state = step.state
            if (step.events.isNotEmpty()) rang += at
        }
        assertEquals(listOf(wed(19, 25)), rang)
        assertTrue(state.held.isEmpty())
    }

    @Test
    fun `the system's loitering can hold one too`() {
        val state = PlaceWatchState().holding(home.id, wed(18, 55))
        assertEquals(wed(18, 55), state.held[home.id])
    }

    @Test
    fun `a hold survives the store, and a blob from before holds reads as none`() {
        // The look at seven may be the first thing a fresh process does.
        val held = metEarly().state
        assertEquals(held, ReminderCodec.decodePlaceWatch(ReminderCodec.encodePlaceWatch(held)))
        val old = ReminderCodec.encodePlaceWatch(held.copy(held = emptyMap())).replace(",\"held\":{}", "")
        assertTrue(ReminderCodec.decodePlaceWatch(old).held.isEmpty())
    }

    // ---- the whole of it, the way the phone had it -----------------------------------------

    /** The owner's reminder: home as a doorway with ten minutes, beside "de 19:00 a 21:30, L–J". */
    private val thermos = Reminder(
        id = "b72eb124",
        text = "limpiar termo cafe",
        rules = listOf(
            TriggerRule(Trigger.Location(homeLat, homeLng, radiusM = 50, presence = Presence.INSIDE, label = "Casa", onCrossing = true, dwellMinutes = 10)),
            TriggerRule(Trigger.Interval(LocalTime.of(19, 0), LocalTime.of(21, 30), setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY))),
        ),
        ruleMatch = RuleMatch.TOGETHER,
        recurrence = Recurrence.After(1, RecurrenceUnit.DAYS),
        status = Status.ACTIVE,
        createdAt = wed(9, 0),
        updatedAt = wed(9, 0),
    )

    /** One look as the phone takes it: the gate decides what is asked, the step judges it. */
    private fun phoneLook(state: PlaceWatchState, metres: Double, at: Instant): WatchStep {
        val circles = thermos.watchedCircles(at, zone, Fixtures.defaultTime)
        return stepPlaceWatch(
            state, north(metres, at), circles.filter { it.opensAt == null }.map { it.place }, at,
            listening = circles.filter { it.opensAt != null }.map { it.place },
        )
    }

    @Test
    fun `home at 18_45, ten minutes at 18_55, and the ring at 19_00`() {
        var state = phoneLook(PlaceWatchState(), 900.0, wed(17, 5)).state
        state = phoneLook(state, 10.0, wed(18, 45)).state
        var at = wed(18, 45)
        repeat(4) {
            at = at.plus(dwellWait(ten))
            val step = phoneLook(state, 10.0, at)
            assertTrue(step.events.isEmpty(), "nothing rings before seven")
            state = step.state
        }
        assertEquals(1, state.held.size, "ten minutes at home, held for the hours")
        assertEquals(opening, state.nextCheckAt)

        val seven = phoneLook(state, 10.0, opening)
        assertEquals(1, seven.events.size, "the hours open with the phone still at home")
        val event = seven.events.single()
        assertEquals(0, GeofenceIds.triggerIndexOf(event.placeId))
        // And the firing that event reaches judges the set at that moment: it holds.
        val judged = thermos.ruleInSet(0)!!
        assertTrue(judged.conditions.allHoldAt(opening, zone), "the firing would ring it")
    }

    @Test
    fun `home at 18_45 and out again at 18_58 rings nothing at 19_00`() {
        var state = phoneLook(PlaceWatchState(), 900.0, wed(17, 5)).state
        state = phoneLook(state, 10.0, wed(18, 45)).state
        var at = wed(18, 45)
        repeat(4) {
            at = at.plus(dwellWait(ten))
            state = phoneLook(state, 10.0, at).state
        }
        state = phoneLook(state, 900.0, wed(18, 58)).state
        assertTrue(phoneLook(state, 900.0, opening).events.isEmpty())
    }
}
