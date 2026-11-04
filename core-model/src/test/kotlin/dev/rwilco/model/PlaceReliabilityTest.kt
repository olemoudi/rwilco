package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * The places half of the trigger-contract round (2026-11-04), pinned at its edges: the speed read
 * after the line, the walk past on a low battery, the holds a doorway and a stretch keep for their
 * clock, the state about being away that waits for a clear reading, and the stretch whose "y sólo
 * si estoy en…" rings when the phone gets there.
 *
 * The other place tests pin each change where it was made; these walk the corners between them —
 * boundaries to the second and the percent, the shapes a hold must not outlive, and the rings a
 * round must not give twice.
 */
class PlaceReliabilityTest {

    private val homeLat = 40.4169
    private val homeLng = -3.7035

    /** Thursday 2026-08-27, the tests' own day. */
    private fun thu(hour: Int, minute: Int = 0): Instant = local(2026, 8, 27, hour, minute)

    private fun fri(hour: Int, minute: Int = 0): Instant = local(2026, 8, 28, hour, minute)

    /** A fix [metres] due north of home. */
    private fun north(metres: Double, at: Instant, accuracy: Double = 15.0) =
        Fix(homeLat + metres / 111_195.0, homeLng, accuracy, at)

    private val driving = movingOf(MovingKind.DRIVING)
    private val onFoot = movingOf(MovingKind.ON_FOOT)
    private val either = movingOf(MovingKind.ANY)

    private fun door(presence: Presence = Presence.INSIDE) =
        Trigger.Location(homeLat, homeLng, radiusM = 100, presence = presence, label = "Casa", onCrossing = true)

    private val atHome = Condition.AtPlace(homeLat, homeLng, 100, "Casa")

    private fun row(vararg rules: TriggerRule, createdAt: Instant = local(2026, 8, 26, 12, 0)) =
        Reminder(id = "r1", text = "x", rules = rules.toList(), createdAt = createdAt, updatedAt = createdAt)

    /** One look as the phone takes it: every gate worked out at the fix's moment, then the step. */
    private fun lookAt(state: PlaceWatchState, fix: Fix, vararg reminders: Reminder, charge: Double? = null): WatchStep {
        val circles = reminders.flatMap { it.watchedCircles(fix.at, zone, defaultTime) }
        return stepPlaceWatch(
            state, fix, circles.filter { it.opensAt == null }.map { it.place }, fix.at, charge = charge,
            listening = circles.filter { it.listens }.map { it.place },
        )
    }

    // ---- the speed is the one after the line -----------------------------------------------------

    @Test
    fun `a speed is written only off a look at most five minutes older, to the second`() {
        val now = thu(9)
        val span = PlaceWatchPolicy.FENCE_SPAN
        val within = stepPlaceWatch(PlaceWatchState(lastFix = north(0.0, now - span)), north(600.0, now), emptyList(), now)
        assertEquals(600.0 / span.seconds, within.state.lastFix!!.speedMps!!, 0.05, "five minutes exactly still speaks")
        val past = stepPlaceWatch(PlaceWatchState(lastFix = north(0.0, now - span - Duration.ofSeconds(1))), north(600.0, now), emptyList(), now)
        assertNull(past.state.lastFix!!.speedMps, "a second more and nobody knows")
        // A rest hands the stored fix back as the current one: same instant, same look, and the
        // speed it already carried is not replaced by a zero nobody measured.
        val measured = north(0.0, now).copy(speedMps = 3.0)
        val rested = stepPlaceWatch(PlaceWatchState(lastFix = measured), measured, emptyList(), now.plusSeconds(60))
        assertEquals(3.0, rested.state.lastFix!!.speedMps)
    }

    @Test
    fun `walking starts at its floor and ends where a car begins`() {
        fun at(speed: Double) = north(0.0, thu(9)).copy(speedMps = speed)
        val now = thu(9)
        assertTrue(onFoot.holdsAt(now, zone, at(PlaceWatchPolicy.ON_FOOT_MPS)), "the floor itself is a walk")
        assertFalse(onFoot.holdsAt(now, zone, at(0.69)))
        assertFalse(onFoot.holdsAt(now, zone, at(PlaceWatchPolicy.DRIVING_MPS)), "five metres a second is a car's")
        assertTrue(driving.holdsAt(now, zone, at(PlaceWatchPolicy.DRIVING_MPS)))
        assertFalse(driving.holdsAt(now, zone, at(4.99)))
        assertTrue(either.holdsAt(now, zone, at(0.7)))
        assertTrue(either.holdsAt(now, zone, at(30.0)))
        // Every walking fence ever written reads as the new floor; a car's never moves.
        for (written in listOf(Condition.Moving(1.5), Condition.Moving(1.5, maxMps = 5.0), Condition.Moving(0.7, maxMps = 5.0))) {
            assertEquals(PlaceWatchPolicy.ON_FOOT_MPS, written.floorMps, "$written")
            assertTrue(written.holdsAt(now, zone, at(1.0)), "$written refused a stroll")
        }
        assertEquals(PlaceWatchPolicy.DRIVING_MPS, Condition.Moving(5.0).floorMps)
        assertEquals(PlaceWatchPolicy.DRIVING_MPS, driving.floorMps)
    }

    private val leaving = WatchedPlace("r1#0", homeLat, homeLng, radiusM = 100, transition = Transition.EXIT, label = "Casa", onCrossing = true, timesSpeed = true)

    /** Half an hour on the sofa, inside: the look a "when I leave" watch rests on. */
    private fun sofa(at: Instant) = PlaceWatchState(lastFix = north(0.0, at.minusSeconds(1_800)), inside = mapOf(leaving.id to true))

    @Test
    fun `a timed crossing waits out exactly five minutes for a look after it, then goes on unanswered`() {
        val t0 = thu(8)
        val crossing = stepPlaceWatch(sofa(t0), north(400.0, t0), listOf(leaving), t0)
        assertEquals(mapOf(leaving.id to t0), crossing.state.timing)
        // Every look until then hands the phone a fix from before the line (the provider had
        // nothing newer): none of them can say how fast the line was crossed.
        val stale = north(0.0, t0.minusSeconds(10))
        val edge = stepPlaceWatch(crossing.state, stale, listOf(leaving), t0 + PlaceWatchPolicy.FENCE_SPAN)
        assertTrue(edge.events.isEmpty(), "five minutes exactly is still waiting")
        assertEquals(mapOf(leaving.id to t0), edge.state.timing)
        val over = t0 + PlaceWatchPolicy.FENCE_SPAN + Duration.ofSeconds(1)
        val gone = stepPlaceWatch(edge.state, stale, listOf(leaving), over)
        assertEquals(listOf(PlaceEvent(leaving.id, Transition.EXIT)), gone.events, "and past it, it rings: what nobody can vouch for holds")
        assertTrue(gone.state.timing.isEmpty())
        assertNull(gone.state.lastFix!!.speedMps, "a fix from before the line carries no speed for it")
        assertTrue(driving.holdsAt(over, zone, gone.state.lastFix), "and with no speed, the fence holds")
    }

    @Test
    fun `a timed crossing buys one look at ninety seconds even on a nearly flat battery, and no gps`() {
        val t0 = thu(8)
        val crossing = stepPlaceWatch(sofa(t0), north(400.0, t0), listOf(leaving), t0, charge = 0.10)
        assertEquals(1, crossing.timed.size)
        assertEquals(PlaceWatchPolicy.SPEED_LOOK, crossing.plan!!.wait, "the hourly floor gives way to the one look the crossing waits for")
        assertNotEquals(FixTier.PRECISE, crossing.plan!!.tier, "at a tenth of a battery the GPS stays off")
        // And once it is handed on, the battery has its last word again.
        val later = t0 + PlaceWatchPolicy.SPEED_LOOK
        val handed = stepPlaceWatch(crossing.state, north(1_300.0, later), listOf(leaving), later, charge = 0.10)
        assertEquals(1, handed.events.size)
        assertTrue(handed.plan!!.wait >= batteryFloor(0.10))
    }

    @Test
    fun `a timed crossing goes with its circle when the rule is edited`() {
        // An edit is a new id (GeofenceIds): the crossing being timed was of a circle nobody is
        // waiting on any more, and handing it on under the new one would ring the edit.
        val t0 = thu(8)
        val crossing = stepPlaceWatch(sofa(t0), north(400.0, t0), listOf(leaving), t0)
        val edited = leaving.copy(id = "r1#0@edited", radiusM = 150)
        val later = t0 + PlaceWatchPolicy.SPEED_LOOK
        val step = stepPlaceWatch(crossing.state, north(1_300.0, later), listOf(edited), later)
        assertTrue(step.state.timing.isEmpty())
        assertTrue(step.events.none { it.placeId == leaving.id }, "the old circle rang after its edit")
    }

    @Test
    fun `the watch's memory keeps its holds and timings across a restart, and older and newer blobs still read`() {
        val t0 = thu(8)
        val full = PlaceWatchState(
            lastFix = north(10.0, t0).copy(speedMps = 1.2),
            inside = mapOf("r1#0" to true, "r2#0" to false),
            dwelling = mapOf("r3#0" to Dwelling(since = t0, lastAt = t0)),
            held = mapOf("r1#0" to t0.minusSeconds(600)),
            timing = mapOf("r2#0" to t0.minusSeconds(30)),
        )
        assertEquals(full, ReminderCodec.decodePlaceWatch(ReminderCodec.encodePlaceWatch(full)))
        // A blob from before holds and timings: both read back empty, and nothing else is lost.
        val old = ReminderCodec.encodePlaceWatch(full.copy(held = emptyMap(), timing = emptyMap()))
            .replace(",\"held\":{}", "").replace(",\"timing\":{}", "")
        assertFalse("\"held\":" in old || "\"timing\":" in old, old)
        val read = ReminderCodec.decodePlaceWatch(old)
        assertTrue(read.held.isEmpty() && read.timing.isEmpty())
        assertEquals(full.inside, read.inside)
        assertEquals(full.lastFix, read.lastFix)
        // A blob from a newer build, with a field this one has never heard of, still reads.
        val newer = ReminderCodec.encodePlaceWatch(full).replaceFirst("{", "{\"someday\":{\"r1#0\":3},")
        assertEquals(full, ReminderCodec.decodePlaceWatch(newer))
        // And a blob nobody can read costs a baseline, never a crash.
        assertEquals(PlaceWatchState(), ReminderCodec.decodePlaceWatch("{\"timing\":{\"r1#0\":\"not a moment\"}"))
    }

    // ---- a walk past keeps its looks on a low battery ---------------------------------------------

    private val atm = WatchedPlace("atm#0", homeLat, homeLng, radiusM = 50, transition = Transition.ENTER, label = "Cajero", onCrossing = true)

    /** On foot towards the cash machine, 258 m out, 1.4 m/s by the last two looks. */
    private fun approach(charge: Double?, places: List<WatchedPlace> = listOf(atm)): WatchPlan {
        val previous = north(-300.0, thu(17))
        val fix = north(-258.0, thu(17).plusSeconds(30))
        val movement = movementSince(previous, fix, sensed = true, stillStreak = 0)
        return planNextCheck(fix, movement, places, places.associate { it.id to false }, charge = charge, previous = previous)!!
    }

    @Test
    fun `the walk past keeps its pace down to a fifth of a battery, and its gps only above a quarter`() {
        val full = approach(charge = null)
        assertTrue(full.passing)
        assertEquals(FixTier.PRECISE, full.tier, "walking up to a line, measured: the GPS")
        assertEquals(full.wait, approach(0.2001).wait, "a hair over a fifth: the pass keeps its looks")
        assertTrue(approach(0.20).wait >= batteryFloor(0.20), "at a fifth exactly, the battery has the last word")
        // Between a quarter and a fifth the looks stay quick and the GPS goes; above, it may come.
        assertNotEquals(FixTier.PRECISE, approach(0.24).tier)
        assertEquals(FixTier.PRECISE, approach(0.30).tier)
    }

    @Test
    fun `only the walk past undercuts the floor, whatever else is near`() {
        // A big circle the phone is also closing on asks for two minutes, which is no pass: it is
        // floored. The small circle's pass is the one plan that is not, and it sets the look.
        val big = atm.copy(id = "big#0", lat = homeLat + 0.0005, radiusM = 150)
        val mixed = approach(0.40, listOf(big, atm))
        assertEquals(atm.id, mixed.nearest.id)
        assertEquals(approach(null).wait, mixed.wait)
        val alone = approach(0.40, listOf(big))
        assertFalse(alone.passing)
        assertTrue(alone.wait >= batteryFloor(0.40), "everything else is floored as before")
    }

    // ---- holds: a doorway's arrival held for its gate ---------------------------------------------

    /** "Al salir de casa, y vuelve cada 24 h desde el hecho", dealt with on Monday at seven. */
    private val leavingDaily = Reminder(
        id = "r1", text = "x", rules = listOf(TriggerRule(door(Presence.OUTSIDE))),
        recurrence = Recurrence.After(24, RecurrenceUnit.HOURS),
        lastFiredAt = local(2026, 8, 31, 18, 55), lastFiredRule = 0, lastDealtAt = local(2026, 8, 31, 19, 0),
        createdAt = local(2026, 8, 30, 12, 0), updatedAt = local(2026, 8, 31, 19, 0),
    )

    private fun tue(hour: Int, minute: Int = 0) = local(2026, 9, 1, hour, minute)

    @Test
    fun `a leaving during the rest, still away when it ends, rings then and once`() {
        // "Arrivals and leavings alike": out of the door at half past eight on Tuesday while the
        // rest runs until seven, and still out at seven.
        val home = lookAt(PlaceWatchState(), north(0.0, tue(8)), leavingDaily)
        assertEquals(true, home.state.inside.values.single(), "the listener knows the phone is home")
        val out = lookAt(home.state, north(900.0, tue(8, 30)), leavingDaily)
        assertTrue(out.events.isEmpty(), "a listener reports nothing")
        assertEquals(tue(8, 30), out.state.held.values.single())
        val noon = lookAt(out.state, north(2_000.0, tue(12)), leavingDaily)
        assertEquals(tue(8, 30), noon.state.held.values.single(), "the moment of the leaving is kept, not the last look's")
        val seven = lookAt(noon.state, north(2_000.0, tue(19)), leavingDaily)
        assertEquals(listOf(Transition.EXIT), seven.events.map { it.transition })
        assertTrue(lookAt(seven.state, north(2_000.0, tue(19, 30)), leavingDaily).events.isEmpty(), "once")
        assertNotNull(Simulation(leavingDaily, tue(19)).arrive(0), "and the firing takes it")
    }

    @Test
    fun `a leaving during the rest, home again before it ends, owes nothing`() {
        val home = lookAt(PlaceWatchState(), north(0.0, tue(8)), leavingDaily)
        val out = lookAt(home.state, north(900.0, tue(8, 30)), leavingDaily)
        val back = lookAt(out.state, north(0.0, tue(18)), leavingDaily)
        assertTrue(back.state.held.isEmpty(), "somebody who came back has to leave again")
        assertTrue(lookAt(back.state, north(0.0, tue(19)), leavingDaily).events.isEmpty())
    }

    @Test
    fun `only a ringing doorway with no stay asked of it is held while it listens`() {
        val doorway = WatchedPlace("r1#0", homeLat, homeLng, 100, Transition.ENTER, "Casa", onCrossing = true)
        assertTrue(doorway.listensForTheDoor)
        assertTrue(doorway.copy(transition = Transition.EXIT).listensForTheDoor, "leavings alike")
        assertFalse(doorway.copy(dwell = Duration.ofMinutes(10)).listensForTheDoor, "a rate a listener cannot count")
        assertFalse(doorway.copy(onCrossing = false).listensForTheDoor, "a state is asked afresh, not held")
        for (crossing in listOf(Crossing.TAKES_BACK, Crossing.NOTHING, Crossing.ASKS, Crossing.RESETS)) {
            assertFalse(doorway.copy(crossing = crossing).listensForTheDoor, "$crossing is not a ring owed")
        }
        // And a routine's doorway, crossed while it waits, keeps no hold: its question is asked
        // when it is asked, not owed from the way past.
        val routine = doorway.copy(crossing = Crossing.ASKS)
        val before = PlaceWatchState(lastFix = north(900.0, thu(17)), inside = mapOf(routine.id to false))
        val step = stepPlaceWatch(before, north(0.0, thu(17, 30)), emptyList(), thu(17, 30), listening = listOf(routine))
        assertTrue(step.state.held.isEmpty())
    }

    @Test
    fun `a paused reminder lets its held arrival go, and resumed it starts from the doorway`() {
        // "Al llegar a casa, y sólo si de 18 a 22", home at half past five: held for six. Paused at
        // twenty to six, its circles are gone and so is the hold — a ring owed to a reminder
        // somebody switched off is not one to keep.
        val evening = row(TriggerRule(door(), listOf(Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0)))), createdAt = thu(12))
        val away = lookAt(PlaceWatchState(), north(900.0, thu(17)), evening)
        val held = lookAt(away.state, north(0.0, thu(17, 30)), evening)
        assertEquals(1, held.state.held.size)
        val paused = evening.copy(status = Status.PAUSED)
        assertTrue(paused.watchedCircles(thu(17, 40), zone, defaultTime).isEmpty())
        val off = lookAt(held.state, north(0.0, thu(17, 40)), paused)
        assertTrue(off.state.held.isEmpty())
        val six = lookAt(off.state, north(0.0, thu(18)), evening.copy(resumedAt = thu(17, 50)))
        assertTrue(six.events.isEmpty(), "home all along: no doorway since the pause")
    }

    // ---- a state about being away waits for a reading that can tell ---------------------------------

    private val away = WatchedPlace("r1#0", homeLat, homeLng, radiusM = 100, transition = Transition.EXIT, label = "Casa")

    @Test
    fun `a state about being away is first judged only off a reading whose whole doubt is one side of the line`() {
        val now = thu(20)
        assertTrue(north(50.0, now, accuracy = 50.0).settlesFirstSideOf(away), "doubt reaching the line from inside: inside")
        assertFalse(north(50.0, now, accuracy = 51.0).settlesFirstSideOf(away))
        assertFalse(north(150.0, now, accuracy = 50.0).settlesFirstSideOf(away), "doubt reaching the line from outside: not yet")
        assertTrue(north(151.0, now, accuracy = 50.0).settlesFirstSideOf(away), "clear of it: outside")
        // Only where acting on it does something: a condition's circle keeps the house rule's
        // lean, and a snooze's first side is the person's own word.
        assertTrue(north(50.0, now, accuracy = 500.0).settlesFirstSideOf(away.copy(crossing = Crossing.NOTHING)))
        assertTrue(north(50.0, now, accuracy = 500.0).settlesFirstSideOf(away.copy(id = GeofenceIds.encodeSnooze("r1", door(Presence.OUTSIDE)))))
        // "Mientras esté en casa" keeps its lean towards being there.
        assertTrue(north(50.0, now, accuracy = 500.0).settlesFirstSideOf(away.copy(transition = Transition.ENTER)))
    }

    @Test
    fun `doubtful at home all evening, a state about being away rings once when the phone really leaves`() {
        var state = PlaceWatchState()
        var at = thu(18)
        repeat(8) {
            val step = stepPlaceWatch(state, north(20.0, at, accuracy = 120.0), listOf(away), at)
            assertTrue(step.events.isEmpty(), "rang at the sofa on look $it")
            assertFalse(away.id in step.state.inside, "and wrote a side it could not read")
            assertNotNull(step.plan, "the watch goes on planning while it waits")
            state = step.state
            at += Duration.ofMinutes(30)
        }
        val gone = stepPlaceWatch(state, north(900.0, at, accuracy = 100.0), listOf(away), at)
        assertEquals(listOf(PlaceEvent(away.id, Transition.EXIT)), gone.events)
        at += Duration.ofMinutes(30)
        assertTrue(stepPlaceWatch(gone.state, north(1_500.0, at, accuracy = 300.0), listOf(away), at).events.isEmpty(), "and once")
    }

    // ---- a stretch with a place in its fences rings when the phone gets there ----------------------

    private val evening = TriggerRule(Trigger.Interval(LocalTime.of(18, 0), LocalTime.of(20, 0)), listOf(atHome))

    @Test
    fun `a sibling's ring does not spend a stretch's place, and its own ring however it came does`() {
        val quarterPast = TriggerRule(Trigger.TimeOfDay(LocalTime.of(18, 15)))
        val set = row(evening, quarterPast)
        val half = thu(18, 30)
        assertEquals(thu(18), set.copy(lastFiredAt = thu(18, 15), lastFiredRule = 1).stretchOwed(0, half, zone, defaultTime), "the 18:15 is another rule's")
        // The stretch's own ring put off ten minutes and ringing again keeps the rule (ruleRungBy).
        val snoozed = set.copy(lastFiredAt = thu(18), lastFiredRule = 0, snoozedUntil = thu(18, 10))
        val rung = snoozed.copy(lastFiredAt = thu(18, 10), lastFiredRule = snoozed.ruleRungBy(null, viaSnoozePlace = false), snoozedUntil = null)
        assertEquals(0, rung.lastFiredRule)
        assertNull(rung.stretchOwed(0, half, zone, defaultTime))
        // Done ahead for this evening ("hecho" from Home at five): spent before it opened.
        assertNull(set.copy(lastDealtAt = thu(17), dealtThrough = thu(18)).stretchOwed(0, half, zone, defaultTime))
        // A row from before the rule column: a ring with no rule is read as possibly this one.
        assertNull(set.copy(lastFiredAt = thu(18, 15), lastFiredRule = null).stretchOwed(0, half, zone, defaultTime))
    }

    @Test
    fun `a weekday's place is owed from its waking opening to the end of its day`() {
        val thursdays = row(TriggerRule(Trigger.Weekday(setOf(DayOfWeek.THURSDAY)), listOf(atHome)))
        assertNull(thursdays.stretchOwed(0, thu(0, 30), zone, defaultTime), "Thursday has started, its ring has not")
        assertEquals(thu(8), thursdays.stretchOwed(0, thu(8), zone, defaultTime))
        assertEquals(thu(8), thursdays.stretchOwed(0, thu(23, 50), zone, defaultTime))
        assertNull(thursdays.stretchOwed(0, fri(0, 10), zone, defaultTime), "Friday is not Thursday")
        assertEquals(Crossing.RINGS, thursdays.watchedCircles(thu(23, 50), zone, defaultTime).single().place.crossing)
        assertEquals(Crossing.NOTHING, thursdays.watchedCircles(fri(0, 10), zone, defaultTime).single().place.crossing)
    }

    @Test
    fun `a stretch across midnight is owed past midnight from the evening it opened`() {
        val late = row(TriggerRule(Trigger.Interval(LocalTime.of(22, 0), LocalTime.of(2, 0)), listOf(atHome)))
        assertEquals(thu(22), late.stretchOwed(0, fri(1), zone, defaultTime))
        assertNull(late.stretchOwed(0, fri(2, 30), zone, defaultTime), "shut at two")
        assertNull(late.copy(lastFiredAt = thu(22), lastFiredRule = 0).stretchOwed(0, fri(1), zone, defaultTime), "rung at ten")
        // Yesterday's ring is yesterday's round.
        assertEquals(thu(22), late.copy(lastFiredAt = local(2026, 8, 26, 22, 0), lastFiredRule = 0).stretchOwed(0, fri(1), zone, defaultTime))
    }

    @Test
    fun `a stretch's place is not asked to ring while a rest or a snooze stands in front of it, nor once dealt with`() {
        val stretch = row(evening)
        val half = thu(18, 30)
        val dealt = stretch.copy(lastFiredAt = thu(18), lastFiredRule = 0, lastDealtAt = thu(18, 10), recurrence = Recurrence.After(1, RecurrenceUnit.HOURS))
        assertNotNull(dealt.restUntil(zone, DEFAULT_DAY_START)?.takeIf { it > half }, "resting until ten past seven")
        assertEquals(Crossing.NOTHING, dealt.watchedCircles(half, zone, defaultTime).single().place.crossing)
        assertEquals(Crossing.NOTHING, dealt.watchedCircles(thu(19, 15), zone, defaultTime).single().place.crossing, "the rest is over, the round had its say")
        val snoozedFromHome = stretch.copy(snoozedUntil = thu(19))
        assertEquals(Crossing.NOTHING, snoozedFromHome.watchedCircles(half, zone, defaultTime).single().place.crossing)
        assertTrue(stretch.copy(status = Status.PAUSED).watchedCircles(half, zone, defaultTime).isEmpty())
    }

    @Test
    fun `under todos a stretch's place ticks its rule and the set rings when the rest is met`() {
        val set = row(evening, TriggerRule(Trigger.TimeOfDay(LocalTime.of(19, 30)))).copy(ruleMatch = RuleMatch.ALL)
        val half = thu(18, 30)
        assertEquals(Crossing.RINGS, set.watchedCircles(half, zone, defaultTime).single { it.ruleIndex == 0 }.place.crossing)
        val phone = Simulation(set, half)
        assertNull(phone.arriveAtCondition(0), "noted, not rung: half past seven is still to come")
        assertEquals(setOf(0), phone.reminder.firedRules)
        assertTrue(phone.reminder.watchedCircles(thu(18, 35), zone, defaultTime).none { it.ruleIndex == 0 }, "a ticked stretch watches nothing")
        assertEquals(listOf(thu(19, 30)), phone.run(thu(21)).map { it.at }, "and the set rings at half past seven, inside the stretch")
    }

    @Test
    fun `a stretch written while open and rung by its place is not rung again by its own minute`() {
        // "De 18 a 20, y sólo si estoy en casa", saved at home at half past six: the watch finds
        // the phone home five seconds later and rings; the minute the writing armed is spent with it.
        val written = thu(18, 30)
        val stretch = row(evening, createdAt = written)
        assertEquals(thu(18), stretch.stretchOwed(0, written, zone, defaultTime))
        assertEquals(Crossing.RINGS, stretch.watchedCircles(written, zone, defaultTime).single().place.crossing)
        val phone = Simulation(stretch, written)
        assertEquals(thu(18, 31), phone.arm()?.at, "the writing arms the minute after")
        phone.now = written.plusSeconds(5)
        assertNotNull(phone.arriveAtCondition(0))
        assertEquals(fri(18), phone.arm()?.at, "re-armed for tomorrow's six")
        assertTrue(phone.run(thu(19, 59)).isEmpty(), "and nothing more this evening")
    }

    @Test
    fun `a condition's circle names its rule and is never read as a trigger`() {
        val id = GeofenceIds.encodeCondition("r1", 2, 1, atHome)
        assertEquals(2, GeofenceIds.conditionRuleOf(id))
        assertNull(GeofenceIds.triggerIndexOf(id), "the trigger path can never ring it as its own")
        assertEquals("r1", GeofenceIds.reminderIdOf(id))
        assertNull(GeofenceIds.conditionRuleOf(GeofenceIds.encode("r1", 2, door())))
        assertNull(GeofenceIds.conditionRuleOf(GeofenceIds.encodeSnooze("r1", door())))
        for (junk in listOf("", "r1", "r1#", "r1#c0@x", "r1#xc0@x")) {
            assertNull(GeofenceIds.conditionRuleOf(junk), "'$junk' named a rule")
        }
    }

    // ---- «desde el hecho», unanswered: a state asks again a span after its ring ---------------------

    /** "Mientras esté en casa, y vuelve cada 24 h desde el hecho": rang Thursday at seven, unanswered. */
    private val homeDaily = Reminder(
        id = "r1", text = "x",
        rules = listOf(TriggerRule(Trigger.Location(homeLat, homeLng, radiusM = 100, presence = Presence.INSIDE, label = "Casa"))),
        recurrence = Recurrence.After(24, RecurrenceUnit.HOURS),
        lastFiredAt = thu(19), lastFiredRule = 0,
        createdAt = local(2026, 8, 26, 12, 0), updatedAt = thu(19),
    )

    @Test
    fun `an unanswered state waits a span from its ring, gated and asked afresh, then rings again`() {
        val place = homeDaily.rules.single().trigger as Trigger.Location
        val waiting = homeDaily.watchedCircles(thu(19, 5), zone, defaultTime).single()
        assertEquals(fri(19), waiting.opensAt, "not dropped: it waits for the span")
        assertFalse(waiting.listens, "and keeps no side through it: being home at seven is news")
        assertTrue(homeDaily.presenceAlreadyRang(place, 0, fri(18, 57), zone, DEFAULT_DAY_START))
        // From the run-up the circle is asked, and the firing takes what it finds.
        val runUp = fri(18, 58)
        assertNull(homeDaily.watchedCircles(runUp, zone, defaultTime).single().opensAt)
        assertFalse(homeDaily.presenceAlreadyRang(place, 0, runUp, zone, DEFAULT_DAY_START))
        val first = stepPlaceWatch(PlaceWatchState(), north(0.0, runUp), listOf(waiting.place), runUp)
        assertEquals(1, first.events.size, "home all along, and the first look at the end of the span finds it true")
        val phone = Simulation(homeDaily, runUp)
        assertNotNull(phone.arrive(0))
        // Rung again, unanswered again: the next span counts from this ring.
        assertEquals(local(2026, 8, 29, 18, 58), phone.reminder.watchedCircles(fri(19, 5), zone, defaultTime).single().opensAt)
    }

    @Test
    fun `answered, a state rests from the hecho and not from the ring`() {
        val dealt = homeDaily.copy(lastDealtAt = thu(21))
        assertEquals(fri(21), dealt.watchedCircles(thu(21, 5), zone, defaultTime).single().opensAt)
        assertNull(dealt.unansweredRest(zone, DEFAULT_DAY_START, DayShape.DEFAULT), "nothing unanswered left")
        // «Desde que suena» was never this rule's business: its rest counts from the ring anyway.
        val fromRing = homeDaily.copy(recurrence = Recurrence.After(24, RecurrenceUnit.HOURS, from = RecurrenceFrom.RANG))
        assertNull(fromRing.unansweredRest(zone, DEFAULT_DAY_START, DayShape.DEFAULT))
        assertEquals(fri(19), fromRing.watchedCircles(thu(19, 5), zone, defaultTime).single().opensAt)
    }

    // ---- days: what a clock can say about a circle's fences ------------------------------------------

    @Test
    fun `fences that can never hold together never open, and say so at once`() {
        // Mondays, and only on Sunday the 30th: no day is both.
        val never = TriggerRule(door(), listOf(Condition.OnDays(setOf(DayOfWeek.MONDAY)), Condition.DateRange(LocalDate.of(2026, 8, 30), LocalDate.of(2026, 8, 30))))
        assertNull(never.fencesOpenFrom(thu(9), zone))
        assertTrue(row(never).watchedCircles(thu(9), zone, defaultTime).isEmpty(), "nothing watched for a circle no day allows")
        // "El día 31" in a month of thirty is its last day, and opens with that day's waking hour.
        val thirtyFirsts = TriggerRule(door(), listOf(Condition.OnMonthDays(setOf(31))))
        assertEquals(local(2026, 9, 30, 8, 0), thirtyFirsts.fencesOpenFrom(local(2026, 9, 1, 9, 0), zone))
        // October's is a Saturday, which opens at the weekend's hour.
        assertEquals(local(2026, 10, 31, 10, 0), thirtyFirsts.fencesOpenFrom(local(2026, 10, 1, 9, 0), zone))
    }
}
