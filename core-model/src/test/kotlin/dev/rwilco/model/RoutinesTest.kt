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

    private fun reminderOf(recurrence: Recurrence) = car(nine, span = recurrence)

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
    fun `a routine can be told when its count starts, and nothing is owed until then`() {
        // "Cambiar el filtro cada 3 meses, empezando el 1 de octubre", written in August: the
        // count runs from October, so the deadline is January and not November — and until
        // October the routine is neither overdue nor counting.
        val october = local(2026, 10, 1, 9, 0)
        val filter = car(span = Recurrence.Since(3, RecurrenceUnit.MONTHS, startsAt = october))
        assertEquals(october, filter.routineStart())
        assertEquals(october, filter.routineAnchor())
        assertTrue(filter.routineWaitingToStart(now), "the count has not begun")
        assertEquals(local(2027, 1, 1, 9, 0), filter.routineDeadline(zone, dayStart))
        assertEquals(NextFire.Scheduled(local(2027, 1, 1, 9, 0), null), filter.next())
        assertTrue(overdueRoutines(listOf(filter), now, zone, dayStart).isEmpty(), "nothing is owed yet")
        // The first "hecho" takes over from the start, the way it takes over from the day it
        // was written: the count is about the last time, once there is one.
        val changed = filter.done(now)
        assertEquals(now, changed.routineAnchor())
        assertFalse(changed.routineWaitingToStart(now))
        // And a routine with no start of its own is the one every phone already has.
        assertEquals(car().createdAt, car().routineStart())
        assertFalse(car().routineWaitingToStart(now))
        assertNull(reminderOf(Recurrence.After(3, RecurrenceUnit.MONTHS)).routineStart(), "not a routine, no start")
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
    fun `a pause freezes the count, and lifting it continues the count where it stopped`() {
        // Day 10 of 21, paused: the car is in the shop. Thirty days later the row still says
        // "hace 10 d" and "Sí" — nothing is owed while it rests — and lifting the pause moves
        // the anchor on by the thirty days, so the deadline is eleven days from the resume, not
        // nineteen days ago and ringing the same second.
        val paused = car(createdAt = now.minusSeconds(10 * 86_400)).copy(status = Status.PAUSED, pausedAt = now)
        val later = now.plusSeconds(30 * 86_400)
        assertEquals(now, paused.routineClock(later), "the clock stopped where the pause began")
        assertTrue(paused.routineDone(later, zone, dayStart), "Sí: nothing is owed while it rests")
        assertEquals(local(2026, 9, 7, 9, 0), paused.routineDeadline(zone, dayStart), "the deadline as it stood, for the row")
        val anchor = paused.routineAnchorAfterPause(later)
        assertEquals(paused.routineAnchor().plusSeconds(30 * 86_400), anchor)
        // What the repository writes on resume (ReminderDao.resumeRoutine).
        val resumed = paused.copy(status = Status.ACTIVE, pausedAt = null, lastDealtAt = anchor, resumedAt = later, updatedAt = later)
        assertEquals(local(2026, 10, 7, 9, 0), resumed.routineDeadline(zone, dayStart), "eleven days from the resume, landed on the hour")
        assertTrue(resumed.routineDone(later, zone, dayStart))
        val wake = nextWake(resumed, later, zone, defaultTime, dayStart)
        assertTrue(wake != null && wake.at > later, "nothing rings the second the pause is lifted")
        // The same arithmetic on a routine that had been done: the last "hecho" moves on.
        val done = car(lastDealtAt = now.minusSeconds(5 * 86_400)).copy(status = Status.PAUSED, pausedAt = now)
        assertEquals(now.minusSeconds(5 * 86_400).plusSeconds(30 * 86_400), done.routineAnchorAfterPause(later))
        // And nothing paused is nothing to move.
        assertEquals(car().routineAnchor(), car().routineAnchorAfterPause(later))
        assertEquals(later, car().routineClock(later))
    }

    @Test
    fun `an unanswered ring is one word from the net, at a tenth of the span or half an hour, and then nothing`() {
        // What the owner expects of a routine nobody answered (2026-09-07): the ring once, the
        // net's ICYMI after max(30 min, span/10) beside it, and no re-ring and no re-ask. The
        // routines screen and Home's line carry it from there.
        val deadline = local(2026, 8, 20, 9, 0)
        val rang = car(createdAt = deadline.minusSeconds(21 * 86_400)).copy(lastFiredAt = deadline)
        val due = rang.netDue(deadline.plusSeconds(60), zone, defaultTime, SafetyNetSettings(), dayStart)
        assertEquals(NetWord.LET_GO, due?.word)
        // A tenth of three weeks is two days, and the net never waits past its own longest
        // wait (afterHours, a day): the word comes a day after the ring.
        assertEquals(deadline.plus(Duration.ofHours(SafetyNetSettings().afterHours.toLong())), due?.at, "capped at the net's longest wait")
        assertNull(rang.next(), "the ring is spent")
        assertNull(rang.wake())
        assertFalse(rang.promptsAllowed(now), "the questions hold while the alarm is asking louder")
        // Said, it is said: no second word about the same ring.
        assertNull(rang.copy(nudgedAt = due!!.at).netDue(due.at.plusSeconds(3600), zone, defaultTime, SafetyNetSettings(), dayStart))
        // Six hours of pills: a tenth is 36 minutes, over the floor; one hour would be the floor.
        val pills = car(span = Recurrence.Since(6, RecurrenceUnit.HOURS), createdAt = now.minusSeconds(7 * 3600)).copy(lastFiredAt = now.minusSeconds(60))
        assertEquals(now.minusSeconds(60).plus(Duration.ofMinutes(36)), pills.netDue(now, zone, defaultTime, SafetyNetSettings(), dayStart)?.at)
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
    fun `a routine's net waits half an hour at the least, and the whole wait at the most`() {
        // Reported from the phone: a routine of one hour put the net's word in the shade six
        // minutes after the alarm — a tenth of the span, which is the right proportion for
        // something that is coming back and the wrong one here. The floor is 30 minutes.
        val rang = now.minusSeconds(60)
        val hourly = car(
            span = Recurrence.Since(1, RecurrenceUnit.HOURS),
            createdAt = now.minusSeconds(4 * 3_600),
            lastFiredAt = rang,
        )
        assertEquals(rang.plus(ROUTINE_NET_FLOOR), hourly.netDue(now, zone, defaultTime, SafetyNetSettings(), dayStart)?.at)

        // The floor is a floor: three weeks still waits the whole day the settings allow.
        val slow = car(createdAt = now.minusSeconds(30 * 86_400), lastFiredAt = rang)
        assertEquals(rang.plus(Duration.ofHours(24)), slow.netDue(now, zone, defaultTime, SafetyNetSettings(), dayStart)?.at)

        // And it is the routines' alone: an ordinary reminder that comes back hourly keeps its
        // six minutes, which is what the proportion was written for.
        val ordinary = Fixtures.reminder(nine, id = "pills").copy(
            recurrence = Recurrence.After(1, RecurrenceUnit.HOURS),
            createdAt = now.minusSeconds(4 * 3_600),
            lastFiredAt = rang,
        )
        assertEquals(rang.plus(Duration.ofMinutes(6)), ordinary.netDue(now, zone, defaultTime, SafetyNetSettings(), dayStart)?.at)

        // A span under the cadence floor still gets no net at all: this does not bring one back
        // where the settings ruled it out. (An hour is the shortest span a routine can have, so
        // it takes a raised `minCadenceMinutes` to get there.)
        val strict = SafetyNetSettings(minCadenceMinutes = 120)
        assertNull(hourly.netDue(now, zone, defaultTime, strict, dayStart))
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
        // A start is written only when there is one, so no routine already on a phone changes
        // shape on disk over a field it does not use — and one written by a newer build reads
        // back whole.
        val october = local(2026, 10, 1, 9, 0)
        val started = ReminderCodec.encodeRecurrence(Recurrence.Since(3, RecurrenceUnit.MONTHS, startsAt = october))
        assertTrue(started.contains(""""startsAt""""), started)
        assertEquals(Recurrence.Since(3, RecurrenceUnit.MONTHS, startsAt = october), ReminderCodec.decodeRecurrence(started))
        val asks = TriggerRule(garage)
        val resets = TriggerRule(garage, resets = true)
        val encoded = ReminderCodec.encodeRules(listOf(asks, resets))
        assertFalse(encoded.substringBefore("},{").contains("resets"), "a rule that asks is the rule it always was on disk")
        assertTrue(encoded.substringAfter("},{").contains(""""resets":true"""))
        assertEquals(listOf(asks, resets), ReminderCodec.decodeRules(encoded))
    }
}
