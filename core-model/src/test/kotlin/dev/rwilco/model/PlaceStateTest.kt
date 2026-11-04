package dev.rwilco.model

import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalTime

/**
 * A place read as a state — "mientras esté en casa" — is met by being there, and the two shapes
 * that used to keep it from ever ringing (0.172.0):
 *
 * - **with hours of its own**, being there before they open was judged early, dropped, and never
 *   offered again: the phone did not cross anything at eight, it had been home since seven;
 * - **after a gate or a rest**, the side the phone was on was kept from the wait by every look
 *   another reminder paid for, so being there when the circle opened again was no change at all.
 *
 * September 2026: the 29th is a Tuesday, the 30th a Wednesday.
 */
class PlaceStateTest {

    private val homeLat = 40.4169
    private val homeLng = -3.7035
    private val home = Trigger.Location(homeLat, homeLng, radiusM = 50, presence = Presence.INSIDE, label = "Casa")
    /** Five kilometres north: a second reminder's place, watched all day. */
    private val work = Trigger.Location(homeLat + 0.045, homeLng, radiusM = 100, presence = Presence.INSIDE, label = "Oficina")

    private fun at(day: Int, hour: Int, minute: Int = 0): Instant = local(2026, 9, day, hour, minute)

    private fun reminder(id: String, rule: TriggerRule, recurrence: Recurrence = Recurrence.None, fired: Instant? = null, dealt: Instant? = null) = Reminder(
        id = id,
        text = "Pastillas",
        rules = listOf(rule),
        recurrence = recurrence,
        status = Status.ACTIVE,
        lastFiredAt = fired,
        lastFiredRule = fired?.let { 0 },
        lastDealtAt = dealt,
        createdAt = at(1, 9),
        updatedAt = at(1, 9),
    )

    private val evening = reminder("evening", TriggerRule(home, listOf(Condition.TimeWindow(LocalTime.of(20, 0), LocalTime.of(22, 0)))))
    private val office = reminder("office", TriggerRule(work))

    /** A fix [metres] due north of home. */
    private fun north(metres: Double, at: Instant) = Fix(homeLat + metres / 111_195.0, homeLng, 10.0, at)

    /** One look as the phone takes it (`PlaceWatcher.watching`): every reminder's gate, then the step. */
    private fun look(state: PlaceWatchState, metres: Double, at: Instant, vararg reminders: Reminder): WatchStep {
        val circles = reminders.flatMap { it.watchedCircles(at, zone, Fixtures.defaultTime) }
        return stepPlaceWatch(
            state, north(metres, at), circles.filter { it.opensAt == null }.map { it.place }, at,
            listening = circles.filter { it.listens }.map { it.place },
        )
    }

    private fun Reminder.key(at: Instant): String = watchedCircles(at, zone, Fixtures.defaultTime).single().place.id

    @Test
    fun `home at seven, with the hours from eight, rings at eight`() {
        val outside = look(PlaceWatchState(), 900.0, at(30, 18, 30), evening).state
        val arrived = look(outside, 10.0, at(30, 19, 0), evening)
        assertTrue(arrived.events.isEmpty(), "the hours are not open yet")
        assertEquals(at(30, 19, 0), arrived.state.held[evening.key(at(30, 19, 0))], "held from the arrival")
        assertEquals(at(30, 20, 0), arrived.heldUntil)
        assertTrue(arrived.state.nextCheckAt!! <= at(30, 20, 0), "the watch looks again no later than the opening")

        val eight = look(arrived.state, 10.0, at(30, 20, 0), evening)
        assertEquals(listOf(evening.key(at(30, 20, 0))), eight.events.map { it.placeId })
        assertTrue(eight.state.held.isEmpty(), "a hold rings once")
        assertTrue(look(eight.state, 10.0, at(30, 20, 30), evening).events.isEmpty(), "still there is not news")
    }

    @Test
    fun `home all afternoon rings when the hours open`() {
        // Watched from six (the run-up): the first look finds the phone already home, which for a
        // state is news — and, two hours early, a hold.
        val six = look(PlaceWatchState(), 10.0, at(30, 18, 0), evening)
        assertTrue(six.events.isEmpty())
        assertEquals(1, six.state.held.size)
        assertEquals(1, look(six.state, 10.0, at(30, 20, 0), evening).events.size)
    }

    @Test
    fun `home all afternoon, with another place watched all day, still rings at eight`() {
        // The office is asked about all afternoon, and every one of its looks used to judge the
        // evening's circle on the way past: home, home, home — so at six nothing had changed.
        var state = PlaceWatchState()
        for (hour in 15..17) {
            val step = look(state, 10.0, at(30, hour), evening, office)
            assertNull(step.state.inside[evening.key(at(30, hour))], "a gated state is not judged on the way past")
            state = step.state
        }
        val six = look(state, 10.0, at(30, 18, 0), evening, office)
        assertEquals(1, six.state.held.size, "home, two hours before its hours: held")
        val eight = look(six.state, 10.0, at(30, 20, 0), evening, office)
        assertEquals(listOf(evening.key(at(30, 20, 0))), eight.events.map { it.placeId })
    }

    @Test
    fun `gone before the hours open, nothing rings`() {
        val arrived = look(look(PlaceWatchState(), 900.0, at(30, 18, 30), evening).state, 10.0, at(30, 19, 0), evening)
        val left = look(arrived.state, 900.0, at(30, 19, 40), evening)
        assertTrue(left.state.held.isEmpty(), "somebody who left has to be there again")
        assertTrue(look(left.state, 900.0, at(30, 20, 0), evening).events.isEmpty())
    }

    @Test
    fun `a state that rested overnight is asked afresh in the morning, whatever else is watched`() {
        // "Mientras esté en casa, y vuelve cada día": rang at seven yesterday evening, done at five
        // past, resting until the day starts at nine. Home all night and all morning — working
        // from home — with the office reminder paying for a look every so often.
        val daily = reminder(
            "daily", TriggerRule(home), recurrence = Recurrence.After(1, RecurrenceUnit.DAYS),
            fired = at(29, 19, 0), dealt = at(29, 19, 5),
        )
        var state = PlaceWatchState()
        for ((day, hour) in listOf(29 to 21, 29 to 23, 30 to 3, 30 to 7)) {
            val step = look(state, 10.0, at(day, hour), daily, office)
            assertTrue(step.events.isEmpty(), "resting until nine")
            state = step.state
        }
        val nine = look(state, 10.0, at(30, 9, 0), daily, office)
        assertEquals(listOf(daily.key(at(30, 9, 0))), nine.events.map { it.placeId }, "home at nine is home: it rings")
    }

    /**
     * The same morning on a phone lying still on the desk, which is when the watch takes no fix
     * and runs the step on the one it has (`stepWithoutLooking`). That is only sound when the step
     * can report nothing — and a circle reopened with no side yet reports "home" at once, which a
     * rest would have written down and thrown away: silent until the phone left and came back.
     */
    @Test
    fun `a phone lying still still takes the look that finds a state asked afresh`() {
        val daily = reminder(
            "daily", TriggerRule(home), recurrence = Recurrence.After(1, RecurrenceUnit.DAYS),
            fired = at(29, 19, 0), dealt = at(29, 19, 5),
        )
        val seven = look(PlaceWatchState(), 10.0, at(30, 7, 0), daily, office).state
        fun rested(now: Instant): WatchStep? {
            val still = seven.copy(stillStreak = 3, lastFix = north(10.0, now.minusSeconds(600)))
            val circles = listOf(daily, office).flatMap { it.watchedCircles(now, zone, Fixtures.defaultTime) }
            return stepWithoutLooking(
                still, circles.filter { it.opensAt == null }.map { it.place }, now, sensed = false,
                listening = circles.filter { it.listens }.map { it.place },
            )
        }
        assertNotNull(rested(at(30, 8, 0)), "still resting: nothing to find, and no fix taken")
        assertNull(rested(at(30, 9, 0)), "open again: the look is taken, and it rings off that")
    }

    @Test
    fun `a state fenced to the first of the month, home since the 31st, rings when the 1st opens`() {
        // Audit probe P3. The owner's call, 2026-11-04: a state fenced by days is held and rings
        // when its day opens — the person's day (eight o'clock on a Tuesday), not midnight.
        val firsts = reminder("firsts", TriggerRule(home, listOf(Condition.OnMonthDays(setOf(1)))))
        val eve = look(PlaceWatchState(), 10.0, local(2026, 8, 31, 19, 0), firsts)
        assertTrue(eve.events.isEmpty(), "not on the 31st")
        assertTrue(eve.state.inside.isEmpty(), "and not even judged: a gated state is asked afresh")
        val dawn = look(eve.state, 10.0, local(2026, 9, 1, 6, 0), firsts)
        assertTrue(dawn.events.isEmpty(), "home at six is held for the day")
        assertEquals(1, dawn.state.held.size)
        assertEquals(local(2026, 9, 1, 8, 0), dawn.heldUntil)
        val eight = look(dawn.state, 10.0, local(2026, 9, 1, 8, 0), firsts)
        assertEquals(1, eight.events.size, "the 1st opens with the phone at home")
        assertNotNull(Simulation(firsts, local(2026, 9, 1, 8, 0)).arrive(0), "and the firing rings it")
    }

    @Test
    fun `under todos with a window plazo, home since three is ticked when the window opens`() {
        // Audit probe C1: "a las 20:00" and "mientras esté en casa" under "todos", plazo 18–22.
        // The state was emitted at four (when its circle opened), dropped by the window, never
        // asked again, and the round lapsed at ten. Now it is held for six and ticked then.
        val day = local(2026, 9, 30, 14, 0)
        val base = Reminder(
            id = "todos", text = "x", rules = listOf(TriggerRule(Trigger.TimeOfDay(LocalTime.of(20, 0))), TriggerRule(home)),
            ruleMatch = RuleMatch.ALL, deadline = Deadline.Window(LocalTime.of(18, 0), LocalTime.of(22, 0)),
            status = Status.ACTIVE, createdAt = day, updatedAt = day,
        )
        val set = base.copy(expiresAt = base.roundExpiry(day, zone, Fixtures.defaultTime))
        val four = look(PlaceWatchState(), 10.0, at(30, 16, 0), set)
        assertTrue(four.events.isEmpty(), "two hours before the window: held, not emitted")
        assertEquals(1, four.state.held.size)
        val six = look(four.state, 10.0, at(30, 18, 0), set)
        assertEquals(1, six.events.size)
        val phone = Simulation(set, at(30, 18, 0))
        assertNull(phone.arrive(1), "noted, not rung: the set is waiting for eight o'clock")
        assertEquals(setOf(1), phone.reminder.firedRules)
        val rings = phone.run(at(30, 23, 0))
        assertEquals(listOf(at(30, 20, 0)), rings.map { it.at }, "and the set rings at eight")
        assertTrue(phone.lapses.isEmpty())
    }
}
