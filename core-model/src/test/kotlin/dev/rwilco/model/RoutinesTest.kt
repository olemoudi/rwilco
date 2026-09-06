package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * A routine: a count of time since the last time something was done.
 *
 * "Mover el coche cada 21 días" written on a Thursday afternoon: the count runs from that
 * afternoon, the deadline is the morning twenty-one days on, and the only thing that moves it
 * is doing it — which is *now*, never "the one that was coming".
 */
class RoutinesTest {

    private val dayStart: LocalTime = LocalTime.of(9, 0)
    private val garage = Trigger.Location(40.4, -3.7, 150, Presence.OUTSIDE, "Garaje", onCrossing = true)
    private val nine = Trigger.TimeOfDay(LocalTime.of(9, 0))

    private fun car(
        vararg triggers: Trigger,
        span: Recurrence = Recurrence.Since(21, RecurrenceUnit.DAYS),
        createdAt: Instant = now.minusSeconds(86_400),
        lastDealtAt: Instant? = null,
        lastFiredAt: Instant? = null,
        dealtThrough: Instant? = null,
        status: Status = Status.ACTIVE,
        id: String = "car",
        text: String = "Mover el coche",
        tags: List<String> = emptyList(),
    ) = Reminder(
        id = id,
        text = text,
        tags = tags,
        rules = triggers.map { TriggerRule(it) },
        recurrence = span,
        status = status,
        createdAt = createdAt,
        updatedAt = createdAt,
        lastDealtAt = lastDealtAt,
        lastFiredAt = lastFiredAt,
        dealtThrough = dealtThrough,
    )

    private fun Reminder.next() = nextFire(this, now, zone, defaultTime, dayStart)
    private fun Reminder.wake() = nextWake(this, now, zone, defaultTime, dayStart)

    /** What `ReminderFiring.dismiss` writes, as `Simulation.deal(Done)` mirrors it. */
    private fun Reminder.done(at: Instant): Reminder {
        val consumed = momentDealtWith(at, zone, defaultTime, dayStart)
        val dealt = copy(lastDealtAt = at, dealtThrough = consumed ?: dealtThrough, snoozedUntil = null, firedRules = emptySet())
        return dealt.copy(status = statusAfterDismissal(dealt, at, zone, defaultTime))
    }

    // ---- the shape --------------------------------------------------------------------------

    @Test
    fun `a routine is a reminder whose vuelve is a span since the last time`() {
        assertTrue(car().isRoutine)
        assertFalse(car(span = Recurrence.After(21, RecurrenceUnit.DAYS)).isRoutine)
        val since = Recurrence.Since(21, RecurrenceUnit.DAYS)
        assertTrue(since.isAnchored)
        assertTrue(since.countsInDays)
        assertTrue(since.landsOnAnHour)
        assertTrue(since.repeats)
        assertFalse(since.isCalendar)
        assertFalse(since.landsExactly, "a routine never takes rules out of a loop they were never in")
        assertFalse(Recurrence.Since(8, RecurrenceUnit.HOURS).countsInDays)
        assertFalse(Recurrence.Since(8, RecurrenceUnit.HOURS).landsOnAnHour)
    }

    @Test
    fun `the span is counted like any other, and lands where it says`() {
        val dealt = local(2026, 8, 27, 18, 30)
        assertEquals(local(2026, 9, 17, 9, 0), nextRecurrence(Recurrence.Since(21, RecurrenceUnit.DAYS), dealt, zone, dayStart), "days land at the day's start")
        assertEquals(local(2026, 9, 17, 18, 30), nextRecurrence(Recurrence.Since(21, RecurrenceUnit.DAYS, RecurrenceHour.Same), dealt, zone, dayStart))
        assertEquals(local(2026, 8, 28, 2, 30), nextRecurrence(Recurrence.Since(8, RecurrenceUnit.HOURS), dealt, zone, dayStart), "hours are exact")
        assertEquals(local(2026, 10, 27, 9, 0), nextRecurrence(Recurrence.Since(2, RecurrenceUnit.MONTHS), dealt, zone, dayStart))
    }

    @Test
    fun `a span of nothing is not a span`() {
        assertEquals(TriggerProblem.EVERY_OUT_OF_RANGE, problemOf(Recurrence.Since(0, RecurrenceUnit.HOURS)))
        assertNull(problemOf(Recurrence.Since(21, RecurrenceUnit.DAYS)))
    }

    // ---- the count --------------------------------------------------------------------------

    @Test
    fun `until it has been done once, the count runs from the day it was written`() {
        val fresh = car()
        assertEquals(fresh.createdAt, fresh.routineAnchor())
        assertEquals(local(2026, 9, 16, 9, 0), fresh.routineDeadline(zone, dayStart))
        assertTrue(fresh.routineDone(now, zone, dayStart), "done for now: the count has not run out")
        assertEquals(NextFire.Scheduled(local(2026, 9, 16, 9, 0), null), fresh.next(), "the deadline is what fires next")
        assertEquals(Wake(local(2026, 9, 16, 9, 0), null), fresh.wake(), "and what is armed")
    }

    @Test
    fun `hecho is now, never the one that was coming`() {
        // Day 10 of 21: the car moved today, so the next deadline is 21 days from today — not,
        // as "done ahead" would have it, 21 days after the deadline that was coming (day 42).
        val tenDaysIn = car(createdAt = now.minusSeconds(10 * 86_400))
        assertNull(tenDaysIn.momentDealtWith(now, zone, defaultTime, dayStart), "nothing ahead is spent")
        val moved = tenDaysIn.done(now)
        assertEquals(Status.ACTIVE, moved.status, "a routine is never finished by doing it")
        assertEquals(now, moved.routineAnchor())
        assertEquals(local(2026, 9, 17, 9, 0), moved.routineDeadline(zone, dayStart))
        assertEquals(local(2026, 9, 17, 9, 0), (moved.next() as NextFire.Scheduled).at)
    }

    @Test
    fun `a moment dealt with ahead by an earlier recurrence does not push the count`() {
        // "Cada 21 días" (after) edited into a routine, with a dealtThrough in the future left
        // over from a "hecho" given ahead of time: the routine counts from the "hecho" itself.
        val edited = car(lastDealtAt = now.minusSeconds(3 * 86_400), dealtThrough = now.plusSeconds(30 * 86_400))
        assertEquals(local(2026, 9, 14, 9, 0), edited.routineDeadline(zone, dayStart))
        assertEquals(local(2026, 9, 14, 9, 0), (edited.next() as NextFire.Scheduled).at)
    }

    @Test
    fun `once the span is up it is No until somebody does it, and the ring is spent`() {
        val deadline = local(2026, 8, 20, 9, 0)
        val late = car(createdAt = deadline.minusSeconds(21 * 86_400))
        assertEquals(deadline, late.routineDeadline(zone, dayStart))
        assertFalse(late.routineDone(now, zone, dayStart), "No: the span is up")
        // Not rung yet (the phone was off): the deadline is still what is armed.
        assertEquals(Wake(deadline, null), late.wake())
        // Rung and ignored: nothing to arm until it is dealt with, and it is overdue.
        val rang = late.copy(lastFiredAt = deadline)
        assertNull(rang.next())
        assertNull(rang.wake())
        assertTrue(rang.awaitingAnswer(now))
        // Dealt with: the count starts again from now.
        val moved = rang.done(now)
        assertTrue(moved.routineDone(now, zone, dayStart))
        assertEquals(local(2026, 9, 17, 9, 0), moved.routineDeadline(zone, dayStart))
    }

    @Test
    fun `a snooze on the ring outranks the deadline, as it outranks everything`() {
        val snoozed = car(lastFiredAt = now.minusSeconds(3600)).copy(snoozedUntil = now.plusSeconds(1800))
        assertEquals(NextFire.Scheduled(now.plusSeconds(1800), null, snoozed = true), snoozed.next())
    }

    // ---- the rules are questions, not rings -------------------------------------------------

    @Test
    fun `the rules never decide the ring and never rest`() {
        val withRules = car(garage, nine)
        assertEquals(local(2026, 9, 16, 9, 0), (withRules.next() as NextFire.Scheduled).at, "the deadline, not tomorrow at nine")
        assertNull(withRules.next()?.trigger, "no rule behind the moment")
        assertEquals(Wake(local(2026, 9, 16, 9, 0), null), withRules.wake())
        val dealt = withRules.done(now)
        assertNull(dealt.restUntil(zone, dayStart), "a question is worth asking every time")
        assertEquals(local(2026, 9, 17, 9, 0), (dealt.next() as NextFire.Scheduled).at)
    }

    @Test
    fun `the net measures a routine by its span`() {
        assertEquals(Duration.ofDays(21), car(garage).ringCadence(now, zone, defaultTime, dayStart))
        assertEquals(Duration.ofHours(8), car(span = Recurrence.Since(8, RecurrenceUnit.HOURS)).ringCadence(now, zone, defaultTime, dayStart))
        // Rung and let go: the net has a word, and it is not "too fast".
        val letGo = car(createdAt = now.minusSeconds(30 * 86_400), lastFiredAt = local(2026, 8, 17, 9, 0))
        val due = letGo.netDue(now, zone, defaultTime, SafetyNetSettings(), dayStart)
        assertEquals(NetWord.LET_GO, due?.word)
    }

    @Test
    fun `a stale alarm for a rule rings nothing, through the same door everything comes through`() {
        // Simulation.fire mirrors ReminderFiring.fire's guard: a routine's rule is never a ring.
        val sim = Simulation(car(garage, nine), now, dayStart = dayStart)
        assertNull(sim.arrive(0), "leaving the garage is not the alarm")
        assertTrue(sim.rings.isEmpty())
        assertEquals(Wake(local(2026, 9, 16, 9, 0), null), sim.arm(), "the deadline is still what is armed")
    }

    // ---- a year of the car --------------------------------------------------------------

    @Test
    fun `written, rung, ignored, done — and the next count runs from the hecho, not from the ring`() {
        val sim = Simulation(car(garage), now, dayStart = dayStart)
        val first = sim.run(local(2026, 9, 20, 0, 0))
        assertEquals(listOf(local(2026, 9, 16, 9, 0)), first.map { it.at }, "the deadline rings once")
        assertNull(sim.arm(), "and then waits for an answer")
        // Ten days late, the car finally moves.
        sim.now = local(2026, 9, 26, 18, 0)
        sim.deal(Simulation.Deal.Done)
        assertEquals(Wake(local(2026, 10, 17, 9, 0), null), sim.arm(), "twenty-one days after the hecho")
        // Dealt with the moment it rings, every time, for the rest of the year: one ring per span.
        val rest = sim.run(local(2027, 9, 1, 0, 0)) { Simulation.Deal.Done }
        assertTrue(rest.isNotEmpty())
        // Calendar days, not hours: the spring clock change makes one of these spans an hour short.
        for ((before, after) in rest.zipWithNext()) {
            assertEquals(21, ChronoUnit.DAYS.between(before.local(zone).toLocalDate(), after.local(zone).toLocalDate()), "${before.local(zone)} → ${after.local(zone)}")
            assertEquals(LocalTime.of(9, 0), after.local(zone).toLocalTime())
        }
    }

    // ---- what the screens read ----------------------------------------------------------

    @Test
    fun `home lists no routine, and its tags are not chips there`() {
        val routine = car(tags = listOf("coche"))
        val plain = Fixtures.reminder(Trigger.AtDateTime(local(2026, 8, 27, 21, 0).atZone(zone).toLocalDateTime()), id = "plain", tags = listOf("casa"))
        val groups = groupForHome(listOf(routine, plain), now, zone, defaultTime)
        assertEquals(listOf("plain"), groups.sections.values.flatten().map { it.reminder.id } + listOfNotNull(groups.hero?.entry?.reminder?.id))
        assertEquals(listOf("casa"), tagsInUse(listOf(routine, plain)))
        assertEquals(listOf("coche"), routineTags(listOf(routine, plain)))
    }

    @Test
    fun `overdue routines come first, the one that has waited longest on top`() {
        val plants = car(id = "plants", text = "Regar", createdAt = now.minusSeconds(25 * 86_400))
        val filter = car(id = "filter", text = "Filtro", span = Recurrence.Since(3, RecurrenceUnit.MONTHS), createdAt = now.minusSeconds(100 * 86_400))
        val fresh = car(id = "fresh", createdAt = now.minusSeconds(86_400))
        val soon = car(id = "soon", createdAt = now.minusSeconds(20 * 86_400))
        val paused = car(id = "paused", createdAt = now.minusSeconds(40 * 86_400), status = Status.PAUSED)
        val all = listOf(fresh, plants, paused, filter, soon)
        assertEquals(listOf("filter", "plants"), overdueRoutines(all, now, zone, dayStart).map { it.id }, "the filter has waited nine days longer")
        assertEquals(listOf("filter", "plants", "soon", "fresh", "paused"), routinesFor(all, RoutineFilter.All, now, zone, dayStart).map { it.id })
        assertEquals(listOf("filter", "plants"), routinesFor(all, RoutineFilter.Overdue, now, zone, dayStart).map { it.id })
    }

    @Test
    fun `a search narrows the list and leaves its order alone`() {
        val plants = car(id = "plants", text = "Regar las plantas", createdAt = now.minusSeconds(25 * 86_400))
        val filter = car(id = "filter", text = "Cambiar el filtro del agua", span = Recurrence.Since(3, RecurrenceUnit.MONTHS), createdAt = now.minusSeconds(100 * 86_400))
        val fresh = car(id = "fresh", text = "Mover el coche", createdAt = now.minusSeconds(86_400))
        val all = listOf(fresh, plants, filter)

        assertEquals(listOf("filter", "plants", "fresh"), routinesFor(all, RoutineFilter.All, now, zone, dayStart).map { it.id }, "no words, no narrowing")
        assertEquals(listOf("fresh"), routinesFor(all, RoutineFilter.All, now, zone, dayStart, "coche").map { it.id })
        // Forgiving the way Home's search is: accents, case, and the letters in order.
        assertEquals(listOf("plants"), routinesFor(all, RoutineFilter.All, now, zone, dayStart, "PLANTAS").map { it.id })
        assertEquals(listOf("filter"), routinesFor(all, RoutineFilter.All, now, zone, dayStart, "cmbiar").map { it.id })
        assertEquals(emptyList<String>(), routinesFor(all, RoutineFilter.All, now, zone, dayStart, "bicicleta").map { it.id })
        // **The order is the list's own**, not the search's: what is owed stays on top even
        // when the words match the fresh one better.
        assertEquals(
            listOf("filter", "plants"),
            routinesFor(all, RoutineFilter.All, now, zone, dayStart, "a").map { it.id },
            "one letter matches all three by subsequence only where it starts a word",
        )
        // And it composes with a chip rather than replacing it.
        assertEquals(listOf("filter"), routinesFor(all, RoutineFilter.Overdue, now, zone, dayStart, "filtro").map { it.id })
    }

    @Test
    fun `the filters offered are vencidas while something is, and then the routines' own tags`() {
        val tagged = car(id = "a", tags = listOf("Casa"), createdAt = now.minusSeconds(86_400))
        val other = car(id = "b", tags = listOf("coche", "casa"), createdAt = now.minusSeconds(86_400))
        assertEquals(listOf(RoutineFilter.Tag("Casa"), RoutineFilter.Tag("coche")), routineFilters(listOf(tagged, other), now, zone, dayStart))
        val overdue = car(id = "c", createdAt = now.minusSeconds(30 * 86_400))
        assertEquals(RoutineFilter.Overdue, routineFilters(listOf(tagged, overdue), now, zone, dayStart).first())
        assertEquals(listOf("b"), routinesFor(listOf(tagged, other), RoutineFilter.Tag("coche"), now, zone, dayStart).map { it.id })
        assertEquals(listOf("a", "b"), routinesFor(listOf(tagged, other), RoutineFilter.Tag("casa"), now, zone, dayStart).map { it.id }, "a tag is matched whatever its case")
    }

    // ---- the shape on disk --------------------------------------------------------------

    @Test
    fun `the on-disk shape of a routine is frozen, and a place that resets is written only when it does`() {
        assertEquals(
            """{"type":"since","amount":21,"unit":"DAYS","hour":{"type":"day_start"}}""",
            ReminderCodec.encodeRecurrence(Recurrence.Since(21, RecurrenceUnit.DAYS)),
        )
        assertEquals(Recurrence.Since(8, RecurrenceUnit.HOURS, RecurrenceHour.Same), ReminderCodec.decodeRecurrence("""{"type":"since","amount":8,"unit":"HOURS","hour":{"type":"same"}}"""))
        val asks = TriggerRule(garage)
        val resets = TriggerRule(garage, resets = true)
        val encoded = ReminderCodec.encodeRules(listOf(asks, resets))
        assertFalse(encoded.substringBefore("},{").contains("resets"), "a rule that asks is the rule it always was on disk")
        assertTrue(encoded.substringAfter("},{").contains(""""resets":true"""))
        assertEquals(listOf(asks, resets), ReminderCodec.decodeRules(encoded))
    }
}
