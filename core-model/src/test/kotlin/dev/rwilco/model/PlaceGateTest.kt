package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Which circles cost a position and which do not — the one decision in this app that spends a
 * radio, and until now the only one that could be asked nothing but a phone.
 *
 * The clock is a Thursday at 15:00 in Madrid ([Fixtures.now]).
 */
class PlaceGateTest {

    private val homeLat = 40.4169
    private val homeLng = -3.7035
    private val home = Trigger.Location(homeLat, homeLng, radiusM = 200, presence = Presence.INSIDE, label = "Casa")
    private val leavingHome = home.copy(presence = Presence.OUTSIDE)

    private fun reminder(
        vararg rules: TriggerRule,
        match: RuleMatch = RuleMatch.ANY,
        fired: Set<Int> = emptySet(),
        dealt: java.time.Instant? = null,
        recurrence: Recurrence = Recurrence.None,
    ) = Reminder(
        id = "r1",
        text = "Sacar la basura",
        rules = rules.toList(),
        ruleMatch = match,
        firedRules = fired,
        recurrence = recurrence,
        lastDealtAt = dealt,
        status = Status.ACTIVE,
        createdAt = now.minusSeconds(3_600),
        updatedAt = now.minusSeconds(3_600),
    )

    private fun Reminder.circles() = watchedCircles(now, zone, defaultTime)

    private fun at(hour: Int, minute: Int = 0, day: Int = 27) =
        TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, day, hour, minute)))

    @Test
    fun `a bare place is watched now, and asks for the ordinary cadence`() {
        val circles = reminder(TriggerRule(home)).circles()
        assertEquals(1, circles.size)
        assertNull(circles[0].opensAt, "a place with nothing in front of it is worth a fix now")
        assertEquals(Crossing.RINGS, circles[0].place.crossing)
        assertEquals(Duration.ZERO, circles[0].place.floor, "and no floor under the arithmetic")
    }

    @Test
    fun `hours of its own shut the gate until the run-up`() {
        // "Al llegar a casa, y sólo si es de 20 a 22": at three in the afternoon nothing can
        // ring however far anybody walks, and the watch wakes two hours before it can.
        val evening = Condition.TimeWindow(LocalTime.of(20, 0), LocalTime.of(22, 0))
        val circle = reminder(TriggerRule(home, listOf(evening))).circles().single()
        assertEquals(Fixtures.local(2026, 8, 27, 18, 0), circle.opensAt)
    }

    @Test
    fun `under a la vez a franja gates the place and a moment only asks it`() {
        // Two shapes of "a la vez" and they are not the same question. Beside a WINDOW the
        // place is what rings, so it needs the run-up: a baseline before the hours open, or an
        // arrival at one minute past is a first reading. Beside a MOMENT it rings nothing —
        // the clock does — and all it is ever asked is where the phone is AT the moment.
        val window = TriggerRule(Trigger.Interval(LocalTime.of(19, 0), LocalTime.of(21, 0)))
        val beside = reminder(TriggerRule(home), window, match = RuleMatch.TOGETHER).circles()
        assertEquals(Fixtures.local(2026, 8, 27, 17, 0), beside.single { it.ruleIndex == 0 }.opensAt)
        assertEquals(Crossing.RINGS, beside.single { it.ruleIndex == 0 }.place.crossing)

        val moment = reminder(TriggerRule(home), at(16), match = RuleMatch.TOGETHER).circles()
        val asked = moment.single { it.ruleIndex == 0 }
        assertEquals(Fixtures.local(2026, 8, 27, 15, 55), asked.opensAt, "five minutes, not two hours")
        assertEquals(Crossing.NOTHING, asked.place.crossing, "the clock is what rings there")
    }

    @Test
    fun `under todos a place is never gated by its siblings, only slowed`() {
        // "Cuando salga de la oficina, y el 26 del mes que viene", read as "todos". The set
        // cannot ring for a month — but a place under "todos" is a state, and the leaving that
        // meets it happens when it happens. Switched off until the 26th, that leaving is lost
        // and the set waits for one that will never come again. So: watched, at the cheapest
        // cadence there is.
        val far = reminder(TriggerRule(leavingHome), at(9, day = 28), match = RuleMatch.ALL).circles()
        val circle = far.single { it.ruleIndex == 0 }
        assertNull(circle.opensAt, "a place under todos was switched off")
        assertEquals(PlaceWatchPolicy.MAX_WAIT, circle.place.floor, "and it should be the hourly one")

        // Inside the run-up of the moment that can complete the set, the distance arithmetic
        // has it back.
        val near = reminder(TriggerRule(leavingHome), at(16), match = RuleMatch.ALL).circles()
        assertNull(near.single { it.ruleIndex == 0 }.opensAt)
        assertEquals(Duration.ZERO, near.single { it.ruleIndex == 0 }.place.floor)
    }

    @Test
    fun `under todos a ticked-off place waits for the crossing that takes it back`() {
        val set = reminder(TriggerRule(leavingHome), at(9, day = 28), match = RuleMatch.ALL, fired = setOf(0))
        val circle = set.circles().single { it.ruleIndex == 0 }
        assertNull(circle.opensAt, "a ticked-off place stopped being watched")
        assertEquals(Crossing.TAKES_BACK, circle.place.crossing)
        assertEquals(Transition.ENTER, circle.place.transition, "it is waiting to be walked back into")
        // The id is the circle's, not the crossing's: the memory of which side of the line the
        // phone is on has to survive being met and un-met.
        assertEquals(GeofenceIds.encode("r1", 0, leavingHome), circle.place.id)
    }

    @Test
    fun `under cualquiera nothing is ticked off, so nothing waits to come undone`() {
        val set = reminder(TriggerRule(leavingHome), at(9, day = 28), match = RuleMatch.ANY, fired = setOf(0))
        val circle = set.circles().single { it.ruleIndex == 0 }
        assertEquals(Crossing.RINGS, circle.place.crossing)
        assertEquals(Transition.EXIT, circle.place.transition)
        assertEquals(Duration.ZERO, circle.place.floor, "and no floor: nothing is holding it back")
    }

    @Test
    fun `a resting reminder watches nothing and keeps its memory`() {
        // Dealt with an hour ago and coming back tomorrow: the rules say nothing until the rest
        // is up, but which side of the line the phone is on is what the next crossing is judged
        // against — and what a place that has already rung is owed a leaving by.
        val resting = reminder(
            TriggerRule(home),
            recurrence = Recurrence.After(1, RecurrenceUnit.DAYS),
            dealt = now.minusSeconds(3_600),
        )
        val circle = resting.circles().single()
        assertNotNull(circle.opensAt, "a resting circle was watched")
        assertTrue(circle.resting, "and its memory would have been dropped")
    }

    @Test
    fun `a circle only asked about is watched five minutes before it is asked`() {
        val asking = TriggerRule(
            Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 0)),
            listOf(Condition.AtPlace(homeLat, homeLng, 200, "Casa", inside = true)),
        )
        val circle = reminder(asking).circles().single()
        assertEquals(Fixtures.local(2026, 8, 27, 20, 55), circle.opensAt)
        assertEquals(Crossing.NOTHING, circle.place.crossing)
        assertEquals(Transition.ENTER, circle.place.transition, "waiting to be there reads as an arrival")
    }

    @Test
    fun `what a card asks is whether the circle is costing anything`() {
        val evening = Condition.TimeWindow(LocalTime.of(20, 0), LocalTime.of(22, 0))
        assertTrue(reminder(TriggerRule(home)).circles().watchingRule(0))
        assertTrue(!reminder(TriggerRule(home, listOf(evening))).circles().watchingRule(0), "shut is not watched")
        // A circle that is only ever asked about is nobody's idea of a watched rule either: it
        // rings nothing, and the mark on that row is the clock's.
        val asked = TriggerRule(
            Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 15, 3)),
            listOf(Condition.AtPlace(homeLat, homeLng, 200, "Casa")),
        )
        assertTrue(!reminder(asked).circles().watchingRule(0))
    }

    @Test
    fun `a finished reminder is watched by nobody`() {
        assertTrue(reminder(TriggerRule(home)).copy(status = Status.DONE).circles().isEmpty())
    }

    // ---- the two readings of a circle, on their way to the watch -------------------------

    @Test
    fun `the reading a rule asks for is the one the watch is handed`() {
        val being = reminder(TriggerRule(home)).watchedCircles(now, zone, defaultTime).single()
        assertEquals(Transition.ENTER, being.place.transition)
        assertFalse(being.place.onCrossing, "a place is a state unless somebody asks for the doorway")

        val arriving = reminder(TriggerRule(home.copy(onCrossing = true))).watchedCircles(now, zone, defaultTime).single()
        assertTrue(arriving.place.onCrossing)
        // A doorway circle is a different thing to watch, so it keeps its own memory of which
        // side the phone is on rather than inheriting the state reading's.
        assertNotEquals(being.place.id, arriving.place.id)
    }

    @Test
    fun `a ticked-off place is watched as a state, whichever reading ticked it`() {
        // Under "todos" a tick comes off when the rule stops being true — not on a second
        // doorway. "Al llegar a casa" ticked off is un-ticked by not being at home, and asking
        // for a crossing to say so would leave the set holding a rule that is plainly untrue.
        val arriving = TriggerRule(home.copy(onCrossing = true))
        val nine = TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 0)))
        val ticked = reminder(arriving, nine, match = RuleMatch.ALL, fired = setOf(0))
        val circle = ticked.watchedCircles(now, zone, defaultTime).first { it.ruleIndex == 0 }
        assertEquals(Crossing.TAKES_BACK, circle.place.crossing)
        assertEquals(Transition.EXIT, circle.place.transition, "it is waiting to stop being true")
        assertFalse(circle.place.onCrossing, "a tick comes off on the state, not on a doorway")
    }

    @Test
    fun `a lone state that has already rung stops costing a radio`() {
        // Found on a real phone: the one circle with no gate at all. A single-rule set has no
        // window to close it, and a recurrence cannot rest a reminder nobody has dealt with, so
        // it asked for a position every few minutes — for a reminder whose every firing the
        // ring guard was already dropping.
        val rang = local(2026, 8, 27, 18, 21)
        val spent = reminder(TriggerRule(home)).copy(lastFiredAt = rang)
        assertTrue(spent.watchedCircles(now, zone, defaultTime).isEmpty(), "it is still paying for fixes")

        // Before it rings, and after it is dealt with, it is watched like anything else.
        assertEquals(1, reminder(TriggerRule(home)).watchedCircles(now, zone, defaultTime).size)
        val dealt = spent.copy(lastDealtAt = rang.plusSeconds(60), recurrence = Recurrence.None)
        assertEquals(1, dealt.watchedCircles(now, zone, defaultTime).size)
    }

    @Test
    fun `a doorway that has rung keeps being watched, because it can ring again`() {
        val doorway = home.copy(onCrossing = true)
        val rung = reminder(TriggerRule(doorway)).copy(lastFiredAt = local(2026, 8, 27, 18, 21))
        assertEquals(1, rung.watchedCircles(now, zone, defaultTime).size)
    }

    @Test
    fun `a spent state with siblings is still watched, because they are not spent`() {
        // Under "a la vez" the circle is folded into every other rule as a state, and a window
        // beside it can ring again — this map is what answers where the phone was when it did.
        val evening = TriggerRule(Trigger.Interval(LocalTime.of(19, 0), LocalTime.of(21, 30)))
        val set = reminder(TriggerRule(home), evening, match = RuleMatch.TOGETHER)
            .copy(lastFiredAt = local(2026, 8, 27, 18, 21))
        assertTrue(set.watchedCircles(now, zone, defaultTime).any { it.ruleIndex == 0 })
    }
    @Test
    fun `a rate travels to the watch with the doorway that asked for it`() {
        val rate = home.copy(onCrossing = true, dwellMinutes = 10)
        val door = reminder(TriggerRule(rate)).watchedCircles(now, zone, defaultTime).single()
        assertEquals(Duration.ofMinutes(10), door.place.dwell)

        // And nowhere else. A side reading never reads one, so the circle behind it carries none.
        val state = reminder(TriggerRule(home.copy(dwellMinutes = 10))).watchedCircles(now, zone, defaultTime).single()
        assertNull(state.place.dwell)
    }

    @Test
    fun `a rate carries the moment its hours open, so a stay met early can wait for them`() {
        // "Al llegar a casa, y al menos diez minutos allí, a la vez de 19:00 a 21:30": watched
        // from five, and a stay met at a quarter to seven is held for seven, not thrown away.
        val rate = TriggerRule(home.copy(onCrossing = true, dwellMinutes = 10))
        val evening = TriggerRule(Trigger.Interval(LocalTime.of(19, 0), LocalTime.of(21, 30)))
        val set = reminder(rate, evening, match = RuleMatch.TOGETHER)
        val seven = local(2026, 8, 27, 19, 0)
        assertEquals(seven, set.watchedCircles(local(2026, 8, 27, 18, 45), zone, defaultTime).single { it.ruleIndex == 0 }.place.ringsFrom)
        assertNull(set.watchedCircles(local(2026, 8, 27, 19, 30), zone, defaultTime).single { it.ruleIndex == 0 }.place.ringsFrom, "open: nothing to wait for")

        // The rule's own hours read the same way as a sibling's.
        val own = reminder(TriggerRule(home.copy(onCrossing = true, dwellMinutes = 10), listOf(Condition.TimeWindow(LocalTime.of(19, 0), LocalTime.of(21, 30)))))
        assertEquals(seven, own.watchedCircles(local(2026, 8, 27, 18, 45), zone, defaultTime).single().place.ringsFrom)

        // A bare doorway was an instant here and carried nothing, until the owner's call on
        // 2026-11-04: arrived before the window and still there when it opens, it rings then. It
        // is a moment, so the window's rule cannot fold it in and asks nothing at seven; the
        // doorway is the only clock the set has. A side of a line is already a state that the
        // window's own opening asks about, and stays as it was.
        val bare = reminder(TriggerRule(home.copy(onCrossing = true)), evening, match = RuleMatch.TOGETHER)
        assertEquals(seven, bare.watchedCircles(local(2026, 8, 27, 18, 45), zone, defaultTime).single { it.ruleIndex == 0 }.place.ringsFrom)
        val side = reminder(TriggerRule(home), evening, match = RuleMatch.TOGETHER)
        assertNull(side.watchedCircles(local(2026, 8, 27, 18, 45), zone, defaultTime).single { it.ruleIndex == 0 }.place.ringsFrom)
    }

    @Test
    fun `a state with hours of its own carries their opening, so being there early waits for them`() {
        // "Mientras esté en casa, y sólo si es de 20:00 a 22:00": home at seven is home at eight,
        // and nothing else in the set asks at eight — there is no sibling window to ring then.
        val evening = listOf(Condition.TimeWindow(LocalTime.of(20, 0), LocalTime.of(22, 0)))
        val eight = local(2026, 8, 27, 20, 0)
        val state = reminder(TriggerRule(home, evening))
        assertEquals(eight, state.watchedCircles(local(2026, 8, 27, 19, 0), zone, defaultTime).single().place.ringsFrom)
        assertNull(state.watchedCircles(local(2026, 8, 27, 20, 30), zone, defaultTime).single().place.ringsFrom, "open: nothing to wait for")
        // A doorway with no rate was an instant here — arriving at seven was not arriving at eight
        // — until the owner's call on 2026-11-04 (audit probe P2): "al llegar a casa, y sólo si de
        // 20 a 22", arrived at seven and still there at eight, rings at eight. Its hours are its
        // own clock, and nothing else in the set asks at the opening.
        val door = reminder(TriggerRule(home.copy(onCrossing = true), evening))
        assertEquals(eight, door.watchedCircles(local(2026, 8, 27, 19, 0), zone, defaultTime).single().place.ringsFrom)
    }

    @Test
    fun `under todos a place carries the opening of the plazo's hours`() {
        // Audit probe C1: "a las 20:00" and "mientras esté en casa" under "todos", the plazo from
        // 18:00 to 22:00. Home since three: the circle is asked from four, and what it finds there
        // waits for six instead of being emitted at four and dropped by the window for good.
        val created = local(2026, 8, 27, 14, 0)
        val base = Reminder(
            id = "r1", text = "x", rules = listOf(TriggerRule(Trigger.TimeOfDay(LocalTime.of(20, 0))), TriggerRule(home)),
            ruleMatch = RuleMatch.ALL, deadline = Deadline.Window(LocalTime.of(18, 0), LocalTime.of(22, 0)),
            createdAt = created, updatedAt = created,
        )
        val set = base.copy(expiresAt = base.roundExpiry(created, zone, defaultTime))
        val four = set.watchedCircles(local(2026, 8, 27, 16, 0), zone, defaultTime).single { it.ruleIndex == 1 }
        assertNull(four.opensAt)
        assertEquals(local(2026, 8, 27, 18, 0), four.place.ringsFrom)
        assertNull(set.watchedCircles(local(2026, 8, 27, 18, 30), zone, defaultTime).single { it.ruleIndex == 1 }.place.ringsFrom, "open")
        // Under "a la vez" nothing changes: the sibling's own moment asks about the place.
        val together = reminder(TriggerRule(home), TriggerRule(Trigger.Interval(LocalTime.of(18, 0), LocalTime.of(22, 0))), match = RuleMatch.TOGETHER)
        assertNull(together.watchedCircles(local(2026, 8, 27, 16, 0), zone, defaultTime).single { it.ruleIndex == 0 }.place.ringsFrom)
    }

    @Test
    fun `a state fenced by days opens with the waking day, not at midnight`() {
        // Audit probe P3: "mientras esté en casa, y sólo el día 1", home since the evening of the
        // 31st. The owner's call, 2026-11-04: it rings when the 1st opens — the person's day, as
        // a day with no hour has always opened (Tuesday 2026-09-01: eight o'clock by default).
        val firsts = reminder(TriggerRule(home, listOf(Condition.OnMonthDays(setOf(1)))))
        val eve = firsts.watchedCircles(local(2026, 8, 31, 19, 0), zone, defaultTime).single()
        assertEquals(local(2026, 9, 1, 6, 0), eve.opensAt, "two hours of run-up before the day opens")
        assertFalse(eve.listens, "a state is not told about the 31st on the way past")
        val dawn = firsts.watchedCircles(local(2026, 9, 1, 6, 0), zone, defaultTime).single()
        assertNull(dawn.opensAt)
        assertEquals(local(2026, 9, 1, 8, 0), dawn.place.ringsFrom)
        assertNull(firsts.watchedCircles(local(2026, 9, 1, 12, 0), zone, defaultTime).single().place.ringsFrom, "open all day")
        assertEquals(local(2026, 10, 1, 6, 0), firsts.watchedCircles(local(2026, 9, 2, 0, 5), zone, defaultTime).single().opensAt, "and shut until next month")
    }

    @Test
    fun `days and hours open together at the first hour of a day the days allow`() {
        val home9 = TriggerRule(home, listOf(Condition.OnMonthDays(setOf(1)), Condition.TimeWindow(LocalTime.of(20, 0), LocalTime.of(22, 0))))
        assertEquals(local(2026, 9, 1, 20, 0), home9.fencesOpenFrom(local(2026, 8, 31, 21, 0), zone))
        // A weekend's day opens later, by the person's own day (ten o'clock by default).
        val saturdays = TriggerRule(home, listOf(Condition.OnDays(setOf(java.time.DayOfWeek.SATURDAY))))
        assertEquals(local(2026, 8, 29, 10, 0), saturdays.fencesOpenFrom(local(2026, 8, 27, 15, 0), zone))
        assertEquals(local(2026, 8, 29, 11, 0), saturdays.fencesOpenFrom(local(2026, 8, 29, 11, 0), zone), "already open")
        // A stretch of dates opens on its first day, and one that has gone never opens again.
        val stretch = TriggerRule(home, listOf(Condition.DateRange(java.time.LocalDate.of(2026, 9, 10), java.time.LocalDate.of(2026, 9, 12))))
        assertEquals(local(2026, 9, 10, 8, 0), stretch.fencesOpenFrom(local(2026, 8, 27, 15, 0), zone))
        assertNull(stretch.fencesOpenFrom(local(2026, 9, 13, 9, 0), zone))
        // With no day fence it is the hours alone, as it always was.
        assertEquals(local(2026, 8, 27, 20, 0), TriggerRule(home, listOf(Condition.TimeWindow(LocalTime.of(20, 0), LocalTime.of(22, 0)))).fencesOpenFrom(now, zone))
    }

    @Test
    fun `a gated state is not judged on the way past, and a gated doorway is`() {
        // A doorway needs to know which side it starts on, or its first look is a baseline and
        // not an arrival. A state is the opposite: what it starts from is "not asked yet", so
        // that being there when its gate opens is news — a side kept from the wait made it none.
        val evening = listOf(Condition.TimeWindow(LocalTime.of(20, 0), LocalTime.of(22, 0)))
        val state = reminder(TriggerRule(home, evening)).circles().single()
        val door = reminder(TriggerRule(home.copy(onCrossing = true), evening)).circles().single()
        assertNotNull(state.opensAt)
        assertFalse(state.listens)
        assertTrue(door.listens)
        assertFalse(reminder(TriggerRule(home)).circles().single().listens, "an open circle asks; it does not listen")
    }

    // ---- a routine's circles: ask or count as done, never rest -----------------------------

    @Test
    fun `a routine's place is watched to ask, or to count as done, and never rests`() {
        val since = Recurrence.Since(21, RecurrenceUnit.DAYS)
        // Written seventeen days ago: in the last quarter of its span, the stretch it asks in.
        val written = now.minusSeconds(17 * 86_400)
        val asks = reminder(TriggerRule(leavingHome), recurrence = since).copy(createdAt = written)
        val circle = asks.circles().single()
        assertEquals(Crossing.ASKS, circle.place.crossing)
        // Written as a state, watched as one: "si ya estás fuera de casa" is the reading for a
        // phone that is never seen crossing the line, and forcing the doorway lost that
        // question for good. A doorway written as one stays a doorway.
        assertFalse(circle.place.onCrossing, "a state is watched as a state")
        val door = reminder(TriggerRule(leavingHome.copy(onCrossing = true)), recurrence = since).copy(createdAt = written)
        assertTrue(door.circles().single().place.onCrossing, "and a doorway as a doorway")
        assertNull(circle.opensAt, "worth a position now")
        assertFalse(circle.resting)
        val counts = reminder(TriggerRule(leavingHome, resets = true), recurrence = since).copy(createdAt = written)
        assertEquals(Crossing.RESETS, counts.circles().single().place.crossing)
        // Dealt with an hour ago: an ordinary span would rest its rules; a routine keeps its
        // doorway, held only for the quiet after the "hecho" — two days on three weeks.
        val done = counts.copy(lastDealtAt = now.minusSeconds(3_600))
        val held = done.circles().single()
        assertEquals(Crossing.RESETS, held.place.crossing)
        assertEquals(done.promptQuietUntil(zone), held.opensAt, "opens when the quiet is over")
        // Gated, it rests — and the watch keeps a resting routine circle's memory, which is
        // what stops a state that counts as done from counting itself done again at the end
        // of every quiet (PlaceWatcher.watching).
        assertTrue(held.resting, "a gated routine circle rests")
        assertFalse(circle.resting, "an open one does not")
        // A doorway that asks waits longer after a "hecho": for the last quarter (0.198.0).
        val asked = asks.copy(lastDealtAt = now.minusSeconds(3_600))
        assertEquals(asked.promptOpensAt(zone), asked.circles().single().opensAt, "opens with the questions")
        assertTrue(asked.promptOpensAt(zone)!! > done.promptQuietUntil(zone)!!)
    }

    @Test
    fun `a routine put off to a place keeps the doorway that counts as done, and drops the ones that ask`() {
        val since = Recurrence.Since(21, RecurrenceUnit.DAYS)
        val written = now.minusSeconds(10 * 86_400)
        val garage = leavingHome.copy(lat = homeLat + 0.01, label = "Garaje")
        val put = reminder(TriggerRule(garage, resets = true), TriggerRule(leavingHome), recurrence = since)
            .copy(createdAt = written, snoozedToPlace = home)
        val circles = put.circles()
        assertEquals(setOf(Crossing.RINGS, Crossing.RESETS), circles.map { it.place.crossing }.toSet(), "the snooze's circle and the vouching one; the asking one waits")
        assertEquals(SNOOZE_RULE, circles.single { it.place.crossing == Crossing.RINGS }.ruleIndex)
        assertEquals(0, circles.single { it.place.crossing == Crossing.RESETS }.ruleIndex)
        // Not a routine: the snooze's circle alone, as ever.
        assertEquals(1, reminder(TriggerRule(leavingHome)).copy(snoozedToPlace = home).circles().size)
    }

    @Test
    fun `a doorway that asks is held back while the deadline is ringing, one that counts as done is not`() {
        val since = Recurrence.Since(21, RecurrenceUnit.DAYS)
        val rang = reminder(TriggerRule(leavingHome), recurrence = since)
            .copy(createdAt = now.minusSeconds(30 * 86_400), lastFiredAt = now.minusSeconds(9 * 86_400))
        assertTrue(rang.awaitingAnswer(now))
        assertTrue(rang.circles().isEmpty(), "the alarm is asking louder")
        val counts = rang.copy(rules = listOf(TriggerRule(leavingHome, resets = true)))
        assertEquals(Crossing.RESETS, counts.circles().single().place.crossing, "leaving answers the ring as surely as the button")
    }

    @Test
    fun `a clock rule's condition circle is watched just before its next question`() {
        val since = Recurrence.Since(21, RecurrenceUnit.DAYS)
        val nine = Trigger.TimeOfDay(LocalTime.of(9, 0))
        val fenced = reminder(TriggerRule(nine, listOf(Condition.AtPlace(homeLat, homeLng, 200, "Casa"))), recurrence = since)
            .copy(createdAt = now.minusSeconds(17 * 86_400))
        val circle = fenced.circles().single()
        assertEquals(Crossing.NOTHING, circle.place.crossing)
        assertEquals(local(2026, 8, 28, 9, 0).minus(PlaceWatchPolicy.ASK_LEAD), circle.opensAt, "the run-up to tomorrow's nine")
    }
}
