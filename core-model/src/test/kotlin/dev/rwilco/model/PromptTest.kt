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

/**
 * The questions a routine puts, and when it keeps them to itself.
 *
 * Thursday afternoon in Madrid. "Mover el coche cada 21 días", asked about at nine every
 * morning and at the garage door. Written seventeen days ago and never done: its deadline is
 * Monday morning, and it is in the last quarter of its span — the stretch it asks in (0.198.0).
 */
class PromptTest {

    private val dayStart: LocalTime = LocalTime.of(9, 0)
    private val nine = Trigger.TimeOfDay(LocalTime.of(9, 0))
    private val garage = Trigger.Location(40.4, -3.7, 150, Presence.OUTSIDE, "Garaje", onCrossing = true)

    private fun car(
        vararg rules: TriggerRule,
        span: Recurrence = Recurrence.Since(21, RecurrenceUnit.DAYS),
        lastDealtAt: Instant? = null,
        askedAt: Instant? = null,
        lastFiredAt: Instant? = null,
        snoozedUntil: Instant? = null,
        status: Status = Status.ACTIVE,
        createdAt: Instant = now.minusSeconds(17 * 86_400),
        // Written ten days ago and last edited now: a question is never owed from before the
        // last edit, so the fixture asks from now unless a test says otherwise.
        updatedAt: Instant = now,
    ) = Reminder(
        id = "car",
        text = "Mover el coche",
        rules = rules.toList(),
        recurrence = span,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastDealtAt = lastDealtAt,
        askedAt = askedAt,
        lastFiredAt = lastFiredAt,
        snoozedUntil = snoozedUntil,
    )

    private fun Reminder.prompt() = nextPrompt(now, zone, defaultTime, dayStart)

    @Test
    fun `a clock rule asks at its next moment, and a place asks nothing on the clock`() {
        val routine = car(TriggerRule(nine), TriggerRule(garage))
        assertEquals(Wake(local(2026, 8, 28, 9, 0), 0), routine.prompt(), "tomorrow at nine, the clock rule")
        assertNull(car(TriggerRule(garage)).prompt(), "a doorway is the watch's to ask")
        assertNull(car(TriggerRule(nine), span = Recurrence.After(21, RecurrenceUnit.DAYS)).prompt(), "not a routine, not a question")
    }

    @Test
    fun `a place that counts as done never asks`() {
        val resets = TriggerRule(garage, resets = true)
        assertTrue(resets.resetsRoutine)
        assertFalse(resets.asks)
        assertTrue(TriggerRule(garage).asks)
        // Only a place can vouch for the deed: the flag on a clock rule is nothing.
        assertFalse(TriggerRule(nine, resets = true).resetsRoutine)
        assertTrue(TriggerRule(nine, resets = true).asks)
    }

    @Test
    fun `the question looks past the one already asked`() {
        val asked = car(TriggerRule(nine), askedAt = local(2026, 8, 27, 9, 0))
        assertEquals(Wake(local(2026, 8, 28, 9, 0), 0), asked.prompt(), "the day after the one asked")
    }

    @Test
    fun `a question dropped at its fence is stamped as tried, so the next look moves on`() {
        // At home only, and not at home at nine: dropped — and the same nine is not handed
        // back by the next look, which would arm an alarm in the past, at once, for ever.
        val atHome = TriggerRule(nine, listOf(Condition.OnDays(setOf(java.time.DayOfWeek.SUNDAY))))
        val sim = Simulation(car(atHome, updatedAt = now.minusSeconds(10 * 86_400), askedAt = local(2026, 8, 25, 9, 0)), local(2026, 8, 26, 9, 0), dayStart = dayStart)
        assertFalse(sim.ask(0), "not a Sunday")
        assertEquals(local(2026, 8, 26, 9, 0), sim.reminder.askedAt, "tried, and said so")
        assertEquals(Wake(local(2026, 8, 30, 9, 0), 0), sim.promptAt(), "the next Sunday, not the same Wednesday again")
    }

    @Test
    fun `after a hecho a routine asks only in the last quarter of its span, and a place that counts holds a tenth`() {
        // Done on Thursday afternoon, 21 days: due on 17 September at nine, and nothing is asked
        // until a quarter of that is left — the first question is Saturday the 12th at nine.
        val done = car(TriggerRule(nine), lastDealtAt = now)
        val deadline = done.routineDeadline(zone, dayStart)!!
        val span = done.routineSpan(zone, dayStart)!!
        assertEquals(local(2026, 9, 17, 9, 0), deadline)
        assertEquals(deadline.minus(span.dividedBy(4)), done.promptOpensAt(zone, dayStart))
        assertEquals(Wake(local(2026, 9, 12, 9, 0), 0), done.prompt())
        // The tenth is still what keeps a place that counts as done from counting twice.
        val quiet = done.promptQuietUntil(zone, dayStart)!!
        assertEquals(now.plus(span.dividedBy(10)), quiet)
        assertTrue(quiet > local(2026, 8, 29, 9, 0) && quiet < local(2026, 8, 30, 9, 0))
        // In hours: eight hours asks in its last two, and a place holds forty-eight minutes.
        val pills = car(TriggerRule(nine), span = Recurrence.Since(8, RecurrenceUnit.HOURS), lastDealtAt = now)
        assertEquals(now.plus(Duration.ofHours(6)), pills.promptOpensAt(zone, dayStart))
        assertEquals(now.plus(Duration.ofMinutes(48)), pills.promptQuietUntil(zone, dayStart))
        assertNull(done.copy(recurrence = Recurrence.After(21, RecurrenceUnit.DAYS)).promptOpensAt(zone, dayStart), "not a routine")
    }

    @Test
    fun `eight weeks after a hecho, the weekend question waits for the last two weeks`() {
        // The owner's case (0.198.0): «Cargar teclado y ratón», every 8 weeks, asked about on
        // Saturdays and Sundays — done on Sunday 18 October, it asked again six days later.
        // (Asked at half past nine here: 08:45 would sit on the very moment the window opens.)
        val weekend = TriggerRule(Trigger.TimeOfDay(LocalTime.of(9, 30)), listOf(Condition.OnDays(setOf(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY))))
        val keyboard = car(weekend, span = Recurrence.Since(8, RecurrenceUnit.WEEKS), lastDealtAt = local(2026, 10, 18, 9, 0))
        val sim = Simulation(keyboard, local(2026, 10, 24, 8, 0), dayStart = dayStart)
        assertEquals(local(2026, 12, 13, 9, 0), sim.reminder.routineDeadline(zone, dayStart))
        sim.run(local(2026, 11, 28, 12, 0))
        assertTrue(sim.prompts.isEmpty(), "no weekend asks while the deadline is weeks away")
        sim.run(local(2026, 12, 6, 12, 0))
        assertEquals(listOf(local(2026, 11, 29, 9, 30), local(2026, 12, 5, 9, 30), local(2026, 12, 6, 9, 30)), sim.prompts.map { it.at }, "the last two weeks' weekends")
    }

    @Test
    fun `a routine just written asks in the last quarter from its start, and one told to start later too`() {
        // Never done, the count runs from the start: written today, "cambiar el filtro cada 3
        // meses" asks from early November. (Until 0.198.0 it asked from its first morning.)
        val fresh = car(TriggerRule(nine), span = Recurrence.Since(3, RecurrenceUnit.MONTHS), createdAt = now)
        assertNull(fresh.promptQuietUntil(zone, dayStart), "nothing done, nothing for a place to hold back after")
        assertEquals(Wake(local(2026, 11, 5, 9, 0), 0), fresh.prompt())
        val start = local(2026, 10, 1, 9, 0)
        val later = car(TriggerRule(nine), span = Recurrence.Since(21, RecurrenceUnit.DAYS, startsAt = start))
        val first = later.prompt()!!
        assertTrue(first.at >= start, "no question before the count begins")
        assertEquals(local(2026, 10, 17, 9, 0), first.at, "a quarter of its three weeks before the 22nd")
    }

    @Test
    fun `a question the phone slept through is still owed, once, and the next one is tomorrow's`() {
        // Asked two mornings ago, nothing since (the phone was off): the question is looked for
        // from that one, not from now, so yesterday's nine is the answer — a moment already
        // gone, which the alarm delivers at once. Asked now, tomorrow's is next.
        val slept = car(TriggerRule(nine), updatedAt = now.minusSeconds(10 * 86_400), askedAt = local(2026, 8, 25, 9, 0))
        assertTrue(slept.promptOpensAt(zone, dayStart)!! < local(2026, 8, 26, 9, 0), "inside the last quarter by the morning owed")
        assertEquals(Wake(local(2026, 8, 26, 9, 0), 0), slept.prompt(), "owed since yesterday morning")
        assertEquals(Wake(local(2026, 8, 28, 9, 0), 0), slept.copy(askedAt = now).prompt())
        // A rule added this morning is not owed yesterday's question: the last edit is a floor.
        assertEquals(Wake(local(2026, 8, 28, 9, 0), 0), car(TriggerRule(nine)).prompt())
        // And a stamp from a clock that ran ahead is ignored rather than obeyed.
        assertEquals(Wake(local(2026, 8, 28, 9, 0), 0), car(TriggerRule(nine), askedAt = now.plusSeconds(3 * 86_400)).prompt())
    }

    @Test
    fun `two doors within minutes are one question, whichever put the first`() {
        // The garage asked at 08:58; the nine o'clock rule is the same question and holds.
        val sim = Simulation(car(TriggerRule(garage), TriggerRule(nine)), local(2026, 8, 28, 8, 58), dayStart = dayStart)
        assertTrue(sim.ask(0, viaPlace = true))
        sim.now = local(2026, 8, 28, 9, 0)
        assertFalse(sim.ask(1), "the same question, two minutes on")
        sim.now = local(2026, 8, 28, 9, 6)
        assertTrue(sim.ask(1), "past the echo it is a question again")
    }

    @Test
    fun `a set of windows closes at the earliest close among them, on the day it falls`() {
        // What bounds a question retried inside its window: "de 18 a 22" at 20:00 closes at
        // 22:00; past midnight at 23:00 closes tomorrow at six; not inside, nothing closes.
        val evening = listOf(Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0)))
        assertEquals(local(2026, 8, 27, 22, 0), evening.closesFrom(local(2026, 8, 27, 20, 0), zone))
        assertNull(evening.closesFrom(local(2026, 8, 27, 15, 0), zone), "not inside it")
        val night = listOf(Condition.TimeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0)))
        assertEquals(local(2026, 8, 28, 6, 0), night.closesFrom(local(2026, 8, 27, 23, 0), zone))
        assertEquals(local(2026, 8, 27, 22, 0), (evening + listOf(Condition.TimeWindow(LocalTime.of(19, 0), LocalTime.of(23, 0)))).closesFrom(local(2026, 8, 27, 20, 0), zone), "the earliest")
        assertNull(emptyList<Condition.TimeWindow>().closesFrom(now, zone))
    }

    @Test
    fun `nothing asks while the deadline is asking louder, or while it is put off, or paused`() {
        val late = car(TriggerRule(nine), createdAt = now.minusSeconds(30 * 86_400), lastFiredAt = now.minusSeconds(9 * 86_400))
        assertTrue(late.awaitingAnswer(now))
        assertFalse(late.promptsAllowed(now))
        assertNull(late.prompt())
        val snoozed = late.copy(snoozedUntil = now.plusSeconds(3600))
        assertNull(snoozed.prompt())
        assertNull(car(TriggerRule(nine), status = Status.PAUSED).prompt())
        // Answered, the questions come back.
        assertTrue(late.copy(lastDealtAt = now).promptsAllowed(now))
    }

    @Test
    fun `a question is fenced by the rule's own hours, and a rule that never holds asks nothing`() {
        val weekdays = TriggerRule(nine, listOf(Condition.OnDays(setOf(java.time.DayOfWeek.MONDAY))))
        assertEquals(Wake(local(2026, 8, 31, 9, 0), 0), car(weekdays).prompt(), "Monday's nine")
        val never = TriggerRule(nine, listOf(Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))))
        assertNull(car(never).prompt())
    }

    @Test
    fun `the earliest of several asking rules is the alarm`() {
        val five = Trigger.TimeOfDay(LocalTime.of(17, 0))
        val routine = car(TriggerRule(nine), TriggerRule(five))
        assertEquals(Wake(local(2026, 8, 27, 17, 0), 1), routine.prompt(), "five this afternoon, before tomorrow's nine")
    }

    // ---- the harness, as the app's own doors judge a question --------------------------------

    @Test
    fun `a question put, ignored, and the next one a day later, then answered, and quiet until the last quarter`() {
        val sim = Simulation(car(TriggerRule(nine), TriggerRule(garage)), now, dayStart = dayStart)
        sim.run(local(2026, 8, 29, 12, 0))
        assertEquals(listOf(local(2026, 8, 28, 9, 0), local(2026, 8, 29, 9, 0)), sim.prompts.map { it.at }, "one question a morning, ignored")
        assertTrue(sim.rings.isEmpty(), "and nothing rang")
        // "Sí, ahora" to the next one: the count starts again, and the questions hold until a
        // quarter of it is left.
        sim.run(local(2026, 8, 30, 12, 0), answer = { Simulation.Deal.Done })
        assertEquals(local(2026, 8, 30, 9, 0), sim.reminder.lastDealtAt)
        assertEquals(local(2026, 9, 20, 9, 0), sim.reminder.routineDeadline(zone, dayStart))
        sim.run(local(2026, 9, 14, 12, 0))
        assertTrue(sim.prompts.drop(3).isEmpty(), "two weeks without a question")
        sim.run(local(2026, 9, 16, 12, 0))
        assertEquals(listOf(local(2026, 9, 15, 9, 0), local(2026, 9, 16, 9, 0)), sim.prompts.drop(3).map { it.at }, "then the mornings again")
    }

    @Test
    fun `a doorway asks when crossed, once, and not again within minutes`() {
        val sim = Simulation(car(TriggerRule(garage)), now, dayStart = dayStart)
        assertTrue(sim.crossRule(0, Transition.EXIT))
        assertEquals(1, sim.prompts.size)
        sim.now = now.plusSeconds(120)
        assertFalse(sim.crossRule(0, Transition.EXIT), "the line wobbled: the same question")
        assertFalse(sim.crossRule(0, Transition.ENTER), "the other side is not what the rule waits for")
        sim.now = now.plusSeconds(3 * 3600)
        assertTrue(sim.crossRule(0, Transition.EXIT), "leaving again, hours later, is a new question")
        assertEquals(2, sim.prompts.size)
    }

    @Test
    fun `a doorway that counts as done resets the count, and the notice can be undone by the model's own write`() {
        val sim = Simulation(car(TriggerRule(garage, resets = true)), now, dayStart = dayStart)
        val before = sim.reminder.lastDealtAt
        assertTrue(sim.crossRule(0, Transition.EXIT))
        assertEquals(now, sim.reminder.lastDealtAt, "leaving the garage is the car moving")
        assertEquals(local(2026, 9, 17, 9, 0), sim.reminder.routineDeadline(zone, dayStart))
        assertTrue(sim.prompts.isEmpty(), "no question was put")
        assertEquals(1, sim.resets.size)
        // Out again within the quiet: nothing new to say.
        sim.now = now.plusSeconds(6 * 3600)
        assertFalse(sim.crossRule(0, Transition.EXIT))
        // The undo is the count going back to where it was.
        assertNull(before)
    }

    @Test
    fun `a year of the car — the garage counts, the mornings ask, and the deadline only rings when the car sat still`() {
        val nineAsks = TriggerRule(nine)
        val garageCounts = TriggerRule(garage, resets = true)
        // Written seventeen days ago (its last quarter): driven every Saturday for ten weeks, no
        // deadline ever rings, and the mornings asked only before the first drive — after each one
        // the deadline is three weeks away, and nothing is asked before its last quarter.
        val sim = Simulation(car(nineAsks, garageCounts), now, dayStart = dayStart)
        var saturday = local(2026, 8, 29, 11, 0)
        repeat(10) {
            sim.run(saturday)
            sim.now = saturday
            assertTrue(sim.crossRule(1, Transition.EXIT), "$saturday")
            saturday = saturday.plusSeconds(7 * 86_400)
        }
        assertTrue(sim.rings.isEmpty(), "a car driven every week never rings")
        assertEquals(10, sim.resets.size)
        assertTrue(sim.prompts.isNotEmpty(), "the mornings still asked")
        // Then the car sits for a month: the deadline rings on day 21, once, and the questions
        // stop while it waits for an answer.
        val stood = sim.now
        val rings = sim.run(stood.plusSeconds(35 * 86_400))
        assertEquals(1, rings.size)
        assertEquals(sim.reminder.routineAnchor().plusSeconds(21 * 86_400).atZone(zone).toLocalDate(), rings.single().local(zone).toLocalDate())
        val askedAfterRing = sim.prompts.count { it.at > rings.single().at }
        assertEquals(0, askedAfterRing, "the alarm is asking louder")
        // Out of the garage at last: the ring is answered by the line itself.
        sim.now = stood.plusSeconds(36 * 86_400)
        assertTrue(sim.crossRule(1, Transition.EXIT))
        assertFalse(sim.reminder.awaitingAnswer(sim.now))
        assertTrue(sim.reminder.routineDone(sim.now, zone, dayStart))
    }
}
