package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * What the reliability review of the trigger-contract round found in places (2026-11-04), each
 * one a failing test when it was found and a regression test since its fix (0.205.0). The
 * numbers are the review's own; P3 (a timed crossing across the end of its window) was left as
 * a known limit.
 */
class PlaceFindingsTest {

    private val homeLat = 40.4169
    private val homeLng = -3.7035
    private val driving = movingOf(MovingKind.DRIVING)
    private fun thu(hour: Int, minute: Int = 0): Instant = local(2026, 8, 27, hour, minute)
    private fun north(metres: Double, at: Instant, accuracy: Double = 15.0) = Fix(homeLat + metres / 111_195.0, homeLng, accuracy, at)
    private val homeCondition = Condition.AtPlace(homeLat, homeLng, 100, "Casa")

    private fun lookAt(state: PlaceWatchState, fix: Fix, vararg reminders: Reminder): WatchStep {
        val circles = reminders.flatMap { it.watchedCircles(fix.at, zone, defaultTime) }
        return stepPlaceWatch(
            state, fix, circles.filter { it.opensAt == null }.map { it.place }, fix.at,
            listening = circles.filter { it.listens }.map { it.place },
        )
    }

    @Test
    fun `P1 a stretch written while open and dropped at its minute is not armed in the past again`() {
        val written = thu(18, 30)
        val row = Reminder(
            id = "r1", text = "x",
            rules = listOf(TriggerRule(Trigger.Interval(LocalTime.of(18, 0), LocalTime.of(20, 0)), listOf(homeCondition))),
            createdAt = written, updatedAt = written,
        )
        assertEquals(thu(18, 31), nextWake(row, written, zone, defaultTime)?.at)
        // 18:31: the phone is not at home, the firing drops it and spends the armed moment; the re-arm asks again.
        val after = thu(18, 31).plusSeconds(2)
        val again = nextWake(row, after, zone, defaultTime)
        assertTrue(again == null || again.at > after, "re-armed for $again, which is in the past: the alarm fires at once, is dropped, re-armed...")
    }

    @Test
    fun `P2 the fence's crossing is not timed off a look from before the line`() {
        val leaving = WatchedPlace("r1#0", homeLat, homeLng, radiusM = 100, transition = Transition.EXIT, label = "Casa", onCrossing = true, timesSpeed = true)
        val t0 = thu(8, 0)
        // The watch looked a minute before the line, inside: somebody walking to the car.
        val state = PlaceWatchState(lastFix = north(0.0, t0.minusSeconds(60)), inside = mapOf(leaving.id to true))
        val fenced = state.remembering(leaving.id, Transition.EXIT).timingSpeed(leaving.id, t0)
        val first = t0.plusSeconds(5)
        val step = stepPlaceWatch(fenced, north(250.0, first), listOf(leaving), first)
        assertTrue(step.events.isEmpty(), "handed on off a pair that runs back to before the line: ${step.state.lastFix?.speedMps} m/s")
    }

    @Test
    fun `P4 a held arrival that asks how fast is judged by the speed it arrived at`() {
        val door = Trigger.Location(homeLat, homeLng, radiusM = 100, presence = Presence.INSIDE, label = "Casa", onCrossing = true)
        val row = Reminder(
            id = "r1", text = "x",
            rules = listOf(TriggerRule(door, listOf(driving, Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))))),
            createdAt = thu(12), updatedAt = thu(12),
        )
        val away = lookAt(PlaceWatchState(), north(1_800.0, thu(17, 22)), row)
        val arrived = lookAt(away.state, north(0.0, thu(17, 24)), row)
        assertTrue(driving.holdsAt(thu(17, 24), zone, arrived.state.lastFix), "arrived by car")
        assertEquals(1, arrived.state.held.size)
        val stir = lookAt(arrived.state, north(5.0, thu(17, 57)), row)
        val six = lookAt(stir.state, north(0.0, thu(18, 0)), row)
        assertEquals(1, six.events.size, "released at six")
        assertTrue(driving.holdsAt(thu(18, 0), zone, six.state.lastFix), "but the firing reads the sofa's speed: ${six.state.lastFix?.speedMps}")
    }

    @Test
    fun `P5 a doorway fenced to its day, crossed on it before the waking hour, still rings`() {
        val gym = Trigger.Location(homeLat, homeLng, radiusM = 100, presence = Presence.INSIDE, label = "Gimnasio", onCrossing = true)
        val row = Reminder(
            id = "r1", text = "x", rules = listOf(TriggerRule(gym, listOf(Condition.OnDays(setOf(DayOfWeek.SATURDAY))))),
            createdAt = thu(12), updatedAt = thu(12),
        )
        // Saturday 2026-08-29: the weekend's day starts at ten by default.
        val sat = { h: Int, m: Int -> local(2026, 8, 29, h, m) }
        var state = lookAt(PlaceWatchState(), north(900.0, sat(8, 50)), row).state
        val events = ArrayList<PlaceEvent>()
        for ((at, metres) in listOf(sat(9, 0) to 0.0, sat(9, 20) to 5.0, sat(9, 45) to 900.0, sat(10, 0) to 900.0, sat(12, 0) to 900.0)) {
            val step = lookAt(state, north(metres, at), row)
            events += step.events
            state = step.state
        }
        assertEquals(1, events.size, "on Saturday at nine, at the gym, for three quarters of an hour")
    }

    @Test
    fun `P6 a doorway's early arrival answered by a hecho is not rung again the next day`() {
        val door = Trigger.Location(homeLat, homeLng, radiusM = 100, presence = Presence.INSIDE, label = "Casa", onCrossing = true)
        val row = Reminder(
            id = "r1", text = "x",
            rules = listOf(TriggerRule(door, listOf(Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))))),
            recurrence = Recurrence.After(24, RecurrenceUnit.HOURS),
            createdAt = thu(12), updatedAt = thu(12),
        )
        val away = lookAt(PlaceWatchState(), north(900.0, thu(17)), row)
        val arrived = lookAt(away.state, north(0.0, thu(17, 30)), row)
        assertEquals(1, arrived.state.held.size, "home before six: held")
        // 17:45, from Home: "hecho". It rests until Friday 17:45.
        val dealt = row.copy(lastDealtAt = thu(17, 45))
        var state = arrived.state
        val events = ArrayList<PlaceEvent>()
        val fri = { h: Int, m: Int -> local(2026, 8, 28, h, m) }
        for (at in listOf(thu(18), thu(21), fri(9, 0), fri(17, 45), fri(18, 0), fri(19, 0))) {
            val step = lookAt(state, north(0.0, at), dealt)
            events += step.events
            state = step.state
        }
        assertTrue(events.isEmpty(), "Thursday's arrival, dealt with, rang on Friday at six: $events")
    }

    @Test
    fun `P7 a stretch of dates owes each day's ring past its fourth day`() {
        val office = Condition.AtPlace(homeLat, homeLng, 100, "Oficina")
        val row = Reminder(
            id = "r1", text = "x",
            rules = listOf(TriggerRule(Trigger.DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15)), listOf(office))),
            createdAt = local(2026, 8, 30, 12, 0), updatedAt = local(2026, 8, 30, 12, 0),
            lastFiredAt = local(2026, 9, 9, 9, 0), lastFiredRule = 0,
        )
        // The range rings each of its days at nine, answered or not.
        assertEquals(local(2026, 9, 10, 9, 0), nextWake(row, local(2026, 9, 9, 9, 1), zone, defaultTime)?.at)
        // The 10th's nine o'clock found the phone elsewhere; at ten it gets to the office.
        assertEquals(local(2026, 9, 10, 9, 0), row.stretchOwed(0, local(2026, 9, 10, 10, 0), zone, defaultTime))
    }

    @Test
    fun `P8 a stretch's place owed after a rest rings for being there, not only for getting there`() {
        val stretch = Reminder(
            id = "r1", text = "x",
            rules = listOf(TriggerRule(Trigger.Interval(LocalTime.of(18, 0), LocalTime.of(22, 0)), listOf(homeCondition))),
            recurrence = Recurrence.After(24, RecurrenceUnit.HOURS),
            createdAt = local(2026, 8, 26, 12, 0), updatedAt = thu(18, 5),
            lastFiredAt = thu(18, 0), lastFiredRule = 0, lastDealtAt = thu(18, 5),
        )
        // Another reminder pays for the looks: "al llegar a la oficina", far away.
        val office = Reminder(
            id = "r2", text = "y",
            rules = listOf(TriggerRule(Trigger.Location(homeLat + 0.1, homeLng, radiusM = 100, presence = Presence.INSIDE, label = "Oficina", onCrossing = true))),
            createdAt = thu(12), updatedAt = thu(12),
        )
        val rest = stretch.restUntil(zone, DEFAULT_DAY_START)!!
        val fri = { h: Int, m: Int -> local(2026, 8, 28, h, m) }
        // Home all Friday afternoon, the circle told so on the way past while the rest runs.
        var state = lookAt(PlaceWatchState(), north(0.0, fri(17, 0)), stretch, office).state
        state = lookAt(state, north(0.0, fri(17, 50)), stretch, office).state
        val events = ArrayList<PlaceEvent>()
        for (at in listOf(rest, rest.plusSeconds(120), fri(20, 0), fri(21, 30))) {
            val circle = stretch.watchedCircles(at, zone, defaultTime).single()
            val step = lookAt(state, north(0.0, at), stretch, office)
            events += step.events.filter { it.placeId == circle.place.id }
            state = step.state
        }
        assertTrue(stretch.stretchOwed(0, rest.plusSeconds(120), zone, defaultTime) != null, "owed: opened at six during the rest, never rung")
        assertEquals(1, events.size, "home through the open, owed stretch, and nothing rang")
    }

    @Test
    fun `P9 under a la vez a place that rang unanswered is still watched for its sibling's moment`() {
        val home = Trigger.Location(homeLat, homeLng, radiusM = 100, presence = Presence.INSIDE, label = "Casa")
        val row = Reminder(
            id = "r1", text = "x",
            rules = listOf(TriggerRule(home), TriggerRule(Trigger.Interval(LocalTime.of(18, 0), LocalTime.of(22, 0)))),
            ruleMatch = RuleMatch.TOGETHER,
            recurrence = Recurrence.After(24, RecurrenceUnit.HOURS),
            createdAt = local(2026, 8, 26, 12, 0), updatedAt = thu(18, 30),
            lastFiredAt = thu(18, 30), lastFiredRule = 0,
        )
        val fri = { h: Int, m: Int -> local(2026, 8, 28, h, m) }
        assertEquals(Wake(fri(18, 0), 1), nextWake(row, fri(17, 0), zone, defaultTime), "the window rings on Friday at six, if the phone is home")
        val circle = row.watchedCircles(fri(17, 56), zone, defaultTime).single { it.ruleIndex == 0 }
        assertTrue(circle.opensAt == null || circle.listens, "the one circle that answers 'is the phone home at six' is shut until ${circle.opensAt}")
    }

    @Test
    fun `P11 a walk timed over the ninety seconds after the line reads as a walk`() {
        val onFoot = movingOf(MovingKind.ON_FOOT)
        val leaving = WatchedPlace("r1#0", homeLat, homeLng, radiusM = 100, transition = Transition.EXIT, label = "Casa", onCrossing = true, timesSpeed = true)
        val t0 = thu(8, 0)
        val sofa = PlaceWatchState(lastFix = north(0.0, t0.minusSeconds(1_800), 40.0), inside = mapOf(leaving.id to true))
        val crossing = stepPlaceWatch(sofa, north(200.0, t0, 50.0), listOf(leaving), t0)
        assertEquals(1, crossing.timed.size)
        println("P11 tier of the speed look: ${crossing.plan?.tier}")
        val later = t0.plus(PlaceWatchPolicy.SPEED_LOOK)
        // Walked on at 1.2 m/s: 108 m further, on the blend's fixes.
        val walked = stepPlaceWatch(crossing.state, north(308.0, later, 60.0), listOf(leaving), later)
        assertEquals(1, walked.events.size)
        assertTrue(onFoot.holdsAt(later, zone, walked.state.lastFix), "an ordinary walk read as standing still: ${walked.state.lastFix?.speedMps}")
    }

    @Test
    fun `P10 a state place on a calendar, rung and unanswered, asks again on the calendar's next date`() {
        val home = Trigger.Location(homeLat, homeLng, radiusM = 100, presence = Presence.INSIDE, label = "Casa")
        val fridays = Recurrence.Calendar(Trigger.Repeat(startsOn = LocalDate.of(2026, 8, 28), unit = RepeatUnit.WEEK, days = setOf(DayOfWeek.FRIDAY)))
        val row = Reminder(
            id = "r1", text = "x", rules = listOf(TriggerRule(home)), recurrence = fridays,
            createdAt = local(2026, 8, 26, 12, 0), updatedAt = local(2026, 8, 28, 19, 0),
            lastFiredAt = local(2026, 8, 28, 19, 0), lastFiredRule = 0,
        )
        val nextFriday = local(2026, 9, 4, 19, 0)
        println("P10 circles a week later: " + row.watchedCircles(nextFriday, zone, defaultTime) + " wake: " + nextWake(row, nextFriday, zone, defaultTime))
        assertFalse(row.presenceAlreadyRang(home, 0, nextFriday, zone, DEFAULT_DAY_START), "still spent a week later: it never comes back")
    }
}
