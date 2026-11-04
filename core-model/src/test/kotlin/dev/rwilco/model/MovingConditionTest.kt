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
    private val driving = movingOf(MovingKind.DRIVING)
    private val onFoot = movingOf(MovingKind.ON_FOOT)
    private val either = movingOf(MovingKind.ANY)

    private fun fix(speed: Double?, at: Instant = now) = Fix(40.4, -3.7, 20.0, at, speedMps = speed)

    @Test
    fun `a speed over the fence holds and one under it does not`() {
        assertTrue(driving.holdsAt(now, zone, fix(12.0)), "43 km/h is a car")
        assertFalse(driving.holdsAt(now, zone, fix(1.6)), "a walk is not")
        assertTrue(driving.holdsAt(now, zone, fix(PlaceWatchPolicy.DRIVING_MPS)), "the fence itself counts")
        assertTrue(either.holdsAt(now, zone, fix(1.6)), "and a walk clears the one that takes either")
        assertFalse(either.holdsAt(now, zone, fix(0.2)), "standing still clears nothing")
    }

    @Test
    fun `andando is a band, which is what makes the word honest`() {
        // A floor alone cannot say "andando": a car clears the walking one too, so the label
        // would read as *and not driving* and be false. The ceiling is the whole of the fix.
        assertTrue(onFoot.holdsAt(now, zone, fix(1.6)), "a walk")
        assertFalse(onFoot.holdsAt(now, zone, fix(0.2)), "standing still is not walking")
        assertFalse(onFoot.holdsAt(now, zone, fix(12.0)), "and a car is not walking either")
        // The two bands meet at the vehicle floor without overlapping: exclusive at the top.
        assertFalse(onFoot.holdsAt(now, zone, fix(PlaceWatchPolicy.DRIVING_MPS)))
        assertTrue(driving.holdsAt(now, zone, fix(PlaceWatchPolicy.DRIVING_MPS)))
        // And "cualquiera" is the union of the two, which is what a bare floor already was.
        for (speed in listOf(1.6, 4.9, 5.0, 12.0)) assertTrue(either.holdsAt(now, zone, fix(speed)), "$speed m/s is moving")
    }

    @Test
    fun `the three answers read back as themselves, and the old shape is the one that took either`() {
        for (kind in MovingKind.entries) assertEquals(kind, movingOf(kind).kind)
        // Every speed fence written before the ceiling existed is a bare floor, and a bare floor
        // at walking pace IS "moving, either way" — so nothing on a phone changes meaning.
        assertEquals(MovingKind.ANY, Condition.Moving(PlaceWatchPolicy.WALK_MPS).kind)
        assertEquals(MovingKind.DRIVING, Condition.Moving(PlaceWatchPolicy.DRIVING_MPS).kind)
        // The ceiling is never written unless it is asked for, so the old shape stays byte for
        // byte — which is what makes "nothing already on a phone changes" true by construction.
        fun shapeOf(condition: Condition): String =
            ReminderCodec.encodeRules(listOf(TriggerRule(Trigger.Weekday(setOf(java.time.DayOfWeek.MONDAY)), listOf(condition))))
        // The walking floor the chips write is 0.7 m/s since 2026-11-04 (the owner's call); every
        // fence written before carries 1.5 and is read as 0.7 (`floorMps`).
        assertTrue(shapeOf(either).contains("""{"type":"moving","minMps":0.7}"""), shapeOf(either))
        assertTrue(shapeOf(onFoot).contains("""{"type":"moving","minMps":0.7,"maxMps":5.0}"""), shapeOf(onFoot))
        assertTrue(shapeOf(driving).contains(""""type":"moving""""), "the vehicle floor is the default: " + shapeOf(driving))
        // And it reads back as what it was.
        for (kind in MovingKind.entries) {
            val rule = TriggerRule(Trigger.Weekday(setOf(java.time.DayOfWeek.MONDAY)), listOf(movingOf(kind)))
            assertEquals(listOf(rule), ReminderCodec.decodeRules(ReminderCodec.encodeRules(listOf(rule))))
        }
    }

    @Test
    fun `andando starts at an ordinary walk, and a fence written before reads the same`() {
        // Audit probe P1: 1.2 m/s, a normal pace, was refused by "andando" (floor 1.5). The owner's
        // call, 2026-11-04: walking starts at 0.7 m/s.
        assertTrue(onFoot.holdsAt(now, zone, fix(1.2)))
        assertTrue(onFoot.holdsAt(now, zone, fix(0.8)), "a stroll")
        assertTrue(either.holdsAt(now, zone, fix(1.2)))
        assertFalse(onFoot.holdsAt(now, zone, fix(0.5)), "standing about is not walking")
        // The chips used to write 1.5; those fences mean the new floor without being rewritten.
        val written = Condition.Moving(1.5, maxMps = 5.0)
        assertEquals(MovingKind.ON_FOOT, written.kind)
        assertEquals(PlaceWatchPolicy.ON_FOOT_MPS, written.floorMps)
        assertTrue(written.holdsAt(now, zone, fix(1.2)))
        assertTrue(Condition.Moving(1.5).holdsAt(now, zone, fix(1.2)), "and the old bare floor, which took either")
        // A vehicle's floor is not a walking one, and stays where it was.
        assertEquals(PlaceWatchPolicy.DRIVING_MPS, driving.floorMps)
        assertFalse(driving.holdsAt(now, zone, fix(1.2)))
    }

    @Test
    fun `an average over more than five minutes is no speed at all`() {
        // Audit probe P1: half an hour at home, then six hundred metres in the last seven minutes,
        // was one number — 0.33 m/s — and "y sólo si voy en coche" read it as standing still.
        val home = Fix(40.4169, -3.7035, 15.0, now.minusSeconds(1_800))
        val gone = Fix(40.4169 + 600 / 111_195.0, -3.7035, 15.0, now)
        val kept = stepPlaceWatch(PlaceWatchState(lastFix = home), gone, emptyList(), now).state.lastFix!!
        assertNull(kept.speedMps, "nobody knows how fast that was")
        assertTrue(driving.holdsAt(now, zone, kept), "and what nobody can vouch for holds: it rings")
        // Two minutes apart, the same six hundred metres is a car, and says so.
        val recent = home.copy(at = now.minusSeconds(120))
        val measured = stepPlaceWatch(PlaceWatchState(lastFix = recent), gone, emptyList(), now).state.lastFix!!
        assertEquals(5.0, measured.speedMps!!, 0.1)
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
        // A ceiling at or under the floor is a band nothing can be inside of.
        assertEquals(TriggerProblem.SPEED_OUT_OF_RANGE, problemOf(Condition.Moving(5.0, maxMps = 5.0)))
        assertEquals(TriggerProblem.SPEED_OUT_OF_RANGE, problemOf(Condition.Moving(5.0, maxMps = 1.0)))
        for (kind in MovingKind.entries) assertNull(problemOf(movingOf(kind)), "$kind is one of the three the chips write")
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

    // ---- a crossing that asks how fast waits for a look that can say (2026-11-04) -----------

    private val homeLat = 40.4169
    private val homeLng = -3.7035

    /** "Al salir de casa, y sólo si voy en coche": the doorway, its rule asking how fast. */
    private val leaving = WatchedPlace("r1#0", homeLat, homeLng, radiusM = 100, transition = Transition.EXIT, label = "Casa", onCrossing = true, timesSpeed = true)

    /** A fix [metres] due north of home at [at]. */
    private fun north(metres: Double, at: Instant) = Fix(homeLat + metres / 111_195.0, homeLng, 15.0, at)

    /** Half an hour on the sofa: the last look, inside, the way a "when I leave" watch rests. */
    private val sofa = PlaceWatchState(lastFix = north(0.0, now.minusSeconds(1_800)), inside = mapOf(leaving.id to true))

    @Test
    fun `a crossing seen after a long rest waits a minute and a half for its speed`() {
        // Audit probe P1: the speed at the doorway was the average since the last look, half an
        // hour of it on the sofa. Now the crossing is held and the next look comes soon.
        val crossing = stepPlaceWatch(sofa, north(400.0, now), listOf(leaving), now)
        assertTrue(crossing.events.isEmpty(), "not handed on yet")
        assertEquals(listOf(leaving), crossing.timed)
        assertEquals(mapOf(leaving.id to now), crossing.state.timing)
        assertEquals(PlaceWatchPolicy.SPEED_LOOK, crossing.plan!!.wait)
        assertNull(crossing.state.lastFix!!.speedMps)
        // A rest is never taken over it: the look it waits for is a fresh one.
        assertNull(stepWithoutLooking(crossing.state.copy(stillStreak = 3), listOf(leaving), now.plusSeconds(90), sensed = false))

        // Driven away: 900 m in the ninety seconds after. Handed on, with a car's speed to read.
        val later = now.plus(PlaceWatchPolicy.SPEED_LOOK)
        val driven = stepPlaceWatch(crossing.state, north(1_300.0, later), listOf(leaving), later)
        assertEquals(listOf(PlaceEvent(leaving.id, Transition.EXIT)), driven.events)
        assertTrue(driven.state.timing.isEmpty())
        assertTrue(driving.holdsAt(later, zone, driven.state.lastFix), "a car")
        // Walked to the bins: the look after finds the phone where it was.
        val bins = stepPlaceWatch(crossing.state, north(410.0, later), listOf(leaving), later)
        assertEquals(1, bins.events.size, "handed on all the same")
        assertFalse(driving.holdsAt(later, zone, bins.state.lastFix), "and the fence says no")
    }

    @Test
    fun `a crossing with a fresh speed in hand is handed on at once`() {
        // Two minutes since the last look, inside: the pair straddles the line, and is believed.
        val recent = sofa.copy(lastFix = north(0.0, now.minusSeconds(120)))
        val step = stepPlaceWatch(recent, north(1_200.0, now), listOf(leaving), now)
        assertEquals(listOf(PlaceEvent(leaving.id, Transition.EXIT)), step.events)
        assertTrue(step.state.timing.isEmpty())
        assertTrue(driving.holdsAt(now, zone, step.state.lastFix))
        // And a rule that asks nothing about speed is never held, however old the last look.
        val plain = leaving.copy(timesSpeed = false)
        val untimed = stepPlaceWatch(sofa.copy(inside = mapOf(plain.id to true)), north(400.0, now), listOf(plain), now)
        assertEquals(1, untimed.events.size)
    }

    @Test
    fun `a follow-up that comes late hands the crossing on with no speed, and it rings`() {
        val crossing = stepPlaceWatch(sofa, north(400.0, now), listOf(leaving), now)
        val late = now.plusSeconds(1_200)
        val step = stepPlaceWatch(crossing.state, north(400.0, late), listOf(leaving), late)
        assertEquals(1, step.events.size)
        assertNull(step.state.lastFix!!.speedMps, "twenty minutes is too long to say")
        assertTrue(driving.holdsAt(late, zone, step.state.lastFix), "what nobody can vouch for holds")
    }

    @Test
    fun `the geofence's crossing waits for two looks after it, not one before`() {
        // The fence reports the leaving with no fix of its own; the watch's last one is the sofa's.
        val fenced = sofa.remembering(leaving.id, Transition.EXIT).timingSpeed(leaving.id, now)
        // The look taken at once: its pair runs back to the sofa, half an hour, so it cannot say.
        val first = now.plusSeconds(5)
        val base = stepPlaceWatch(fenced, north(450.0, first), listOf(leaving), first)
        assertTrue(base.events.isEmpty(), "the side is already written; only the speed is awaited")
        assertEquals(PlaceWatchPolicy.SPEED_LOOK, base.plan!!.wait)
        // The one after: ninety seconds, a kilometre further. That is the speed after the line.
        val second = first.plus(PlaceWatchPolicy.SPEED_LOOK)
        val timed = stepPlaceWatch(base.state, north(1_350.0, second), listOf(leaving), second)
        assertEquals(listOf(PlaceEvent(leaving.id, Transition.EXIT)), timed.events)
        assertTrue(driving.holdsAt(second, zone, timed.state.lastFix))
        // A cached fix from before the crossing says nothing about it, however fresh its pair.
        val stale = stepPlaceWatch(fenced.copy(lastFix = north(0.0, now.minusSeconds(100))), north(0.0, now.minusSeconds(10)), listOf(leaving), first)
        assertTrue(stale.events.isEmpty())
        assertNull(stale.state.lastFix!!.speedMps)
        // And the wait is bounded: past five minutes the crossing goes on, unanswered — and rings.
        val gone = now.plus(PlaceWatchPolicy.FENCE_SPAN).plusSeconds(1)
        assertEquals(1, stepPlaceWatch(stale.state, north(0.0, now.minusSeconds(10)), listOf(leaving), gone).events.size)
    }

    @Test
    fun `a circle knows when its rule asks how fast`() {
        val home = Trigger.Location(homeLat, homeLng, radiusM = 100, presence = Presence.OUTSIDE, label = "Casa", onCrossing = true)
        fun circleOf(vararg conditions: Condition) = Reminder(
            id = "r1", text = "x", rules = listOf(TriggerRule(home, conditions.toList())), createdAt = now, updatedAt = now,
        ).watchedCircles(now, zone, Fixtures.defaultTime).single().place
        assertTrue(circleOf(driving).timesSpeed)
        assertFalse(circleOf().timesSpeed)
        assertFalse(circleOf(Condition.OnMonthDays(setOf(1))).timesSpeed)
        // The timing travels with the rest of the watch's memory, and a blob from before reads empty.
        val held = PlaceWatchState().timingSpeed("r1#0", now)
        assertEquals(held, ReminderCodec.decodePlaceWatch(ReminderCodec.encodePlaceWatch(held)))
        assertTrue(ReminderCodec.decodePlaceWatch(ReminderCodec.encodePlaceWatch(PlaceWatchState()).replace(",\"timing\":{}", "")).timing.isEmpty())
    }
}
