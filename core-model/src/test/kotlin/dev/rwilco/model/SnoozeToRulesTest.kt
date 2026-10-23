package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * "La próxima vez que llegue a Mercadona" (0.196.0): which rings are offered it and what the
 * button can name, what the answer changes (only that the ring is answered) and what it leaves
 * alone (everything the rules decide), and the journey — the arrival it was said at, the walk
 * away, the next arrival.
 */
class SnoozeToRulesTest {

    private val market = Trigger.Location(40.4300, -3.7000, 120, Presence.INSIDE, "Mercadona", onCrossing = true)
    private val office = Trigger.Location(40.4500, -3.6900, 150, Presence.OUTSIDE, "Oficina", onCrossing = true)
    private val homeState = Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa")
    private val seven = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 19, 0))
    private val tomorrowNine = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 9, 0))
    private val rang = now.minusSeconds(60)

    private fun reminder(vararg rules: TriggerRule, match: RuleMatch = RuleMatch.ANY, recurrence: Recurrence = Recurrence.None) =
        Reminder(id = "r1", text = "Comprar filtros", rules = rules.toList(), ruleMatch = match, recurrence = recurrence, createdAt = now.minusSeconds(86_400), updatedAt = now.minusSeconds(86_400))

    private fun rule(trigger: Trigger, vararg conditions: Condition) = TriggerRule(trigger, conditions.toList())

    /** Rang [rang] for rule [ruleIndex], and nobody has said anything since. */
    private fun Reminder.rangFor(ruleIndex: Int?) = copy(lastFiredAt = rang, lastFiredRule = ruleIndex)

    private fun Reminder.offer() = nextTimeOffer(now, zone, defaultTime)

    // --- who is offered it, and what the button says ---

    @Test
    fun `a doorway in that rang is offered the next arrival there, by name`() {
        assertEquals(SnoozePlace.NextTime(market), reminder(rule(market)).rangFor(0).offer())
    }

    @Test
    fun `a doorway out is offered the next leaving, by name`() {
        assertEquals(SnoozePlace.NextTime(office), reminder(rule(office)).rangFor(0).offer())
    }

    @Test
    fun `rung by its clock, the door beside it is what comes next`() {
        // "A las 19:00 o al llegar a Mercadona", rung at seven on the metro: the hour is spent.
        val both = reminder(rule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 14, 59))), rule(market)).rangFor(0)
        assertEquals(SnoozePlace.NextTime(market), both.offer())
    }

    @Test
    fun `nothing to offer before a ring, after an answer, or while another snooze waits`() {
        val plain = reminder(rule(market))
        assertNull(plain.offer(), "never rang")
        assertNull(plain.rangFor(0).copy(lastDealtAt = now).offer(), "answered with hecho")
        assertNull(plain.rangFor(0).copy(snoozedUntil = now.plusSeconds(600)).offer(), "put off to a clock")
        assertNull(plain.rangFor(0).copy(snoozedToRules = true).offer(), "already left to the rules")
        assertNull(plain.rangFor(0).copy(status = Status.PAUSED).offer(), "paused")
        assertNotNull(plain.rangFor(0).copy(status = Status.ACTIVE).offer())
    }

    @Test
    fun `a state that has had its say would ring nothing, so it is not offered`() {
        assertNull(reminder(rule(homeState)).rangFor(0).offer())
    }

    @Test
    fun `a clock alone is not offered it, spent or not`() {
        assertNull(reminder(rule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 14, 59)))).rangFor(0).offer(), "spent")
        assertNull(reminder(rule(Trigger.AtTime(LocalTime.of(9, 0), DayOfWeek.entries.toSet()))).rangFor(0).offer(), "a clock that comes round")
    }

    @Test
    fun `with something else that can ring it, the button names nothing`() {
        val generic = SnoozePlace.NextTime(null)
        assertEquals(generic, reminder(rule(market), rule(office)).rangFor(0).offer(), "two doors")
        assertEquals(generic, reminder(rule(market), rule(tomorrowNine)).rangFor(0).offer(), "a moment still ahead")
        assertEquals(generic, reminder(rule(market), rule(homeState)).rangFor(0).offer(), "a state that has not rung")
        assertEquals(
            SnoozePlace.NextTime(market),
            reminder(rule(market), rule(homeState)).rangFor(1).offer(),
            "the state that rang is held quiet, so the door is all there is",
        )
    }

    @Test
    fun `under a la vez the button names nothing, because the windows open by themselves`() {
        val together = reminder(rule(market), rule(Trigger.Interval(LocalTime.of(9, 0), LocalTime.of(20, 0))), match = RuleMatch.TOGETHER).rangFor(0)
        assertEquals(SnoozePlace.NextTime(null), together.offer())
    }

    @Test
    fun `under todos the set is complete once it rang, so there is nothing to wait for`() {
        val all = reminder(rule(market), rule(seven), match = RuleMatch.ALL).rangFor(0).copy(firedRules = setOf(0, 1))
        assertNull(all.offer())
    }

    @Test
    fun `a door whose fences are over for good rings nothing`() {
        val august = Condition.DateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 26))
        assertNull(reminder(rule(market, august)).rangFor(0).offer())
    }

    @Test
    fun `a door with hours on it is still the door, the hours are its own`() {
        val evenings = Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(21, 0))
        assertEquals(SnoozePlace.NextTime(market), reminder(rule(market, evenings)).rangFor(0).offer())
    }

    @Test
    fun `a routine, a contact and a span that took over are not offered it`() {
        val routine = reminder(rule(market), recurrence = Recurrence.Since(21, RecurrenceUnit.DAYS)).rangFor(null)
        assertNull(routine.offer())
        assertNull(routine.copy(contactKind = ContactKind.PERSONAL).offer())
        val exact = reminder(rule(market), recurrence = Recurrence.After(30, RecurrenceUnit.DAYS, landing = SpanLanding.EXACT))
            .rangFor(0).copy(lastDealtAt = rang.minusSeconds(3600))
        assertTrue(exact.spanHasTakenOver)
        assertNull(exact.offer())
    }

    // --- the place offers for one ring ---

    @Test
    fun `its own next time goes first, and the most-used arrival at the same door goes`() {
        val home = SavedPlace("Mercadona", 40.4300, -3.7000, 120)
        val elsewhere = SavedPlace("Casa", 40.4169, -3.7035, 200)
        val screen = listOf(SnoozePlace.Arrive(home), SnoozePlace.LeaveHere(120))
        assertEquals(listOf(SnoozePlace.NextTime(market), SnoozePlace.LeaveHere(120)), placeOffersFor(screen, SnoozePlace.NextTime(market)))
        val other = listOf(SnoozePlace.Arrive(elsewhere), SnoozePlace.LeaveHere(120))
        assertEquals(listOf(SnoozePlace.NextTime(market)) + other, placeOffersFor(other, SnoozePlace.NextTime(market)))
        assertEquals(listOf(SnoozePlace.NextTime(office)) + screen, placeOffersFor(screen, SnoozePlace.NextTime(office)), "leaving is not arriving")
        assertEquals(listOf(SnoozePlace.NextTime(null)) + screen, placeOffersFor(screen, SnoozePlace.NextTime(null)))
        assertEquals(screen, placeOffersFor(screen, null))
    }

    @Test
    fun `without the location grant there is no place offer at all, its own included`() {
        assertEquals(emptyList<SnoozePlace>(), placeOffersFor(emptyList(), SnoozePlace.NextTime(market)))
    }

    // --- what the answer is ---

    @Test
    fun `left to the rules is an answer, and the net has nothing to say about it`() {
        val left = reminder(rule(market)).rangFor(0).copy(armedFor = null, snoozedToRules = true)
        assertFalse(left.awaitingAnswer(now))
        assertFalse(left.answerOwed(now))
        assertNull(missedFire(left, now))
        assertTrue(left.deadlineOutranked(now))
        for (days in listOf(1L, 3L, 30L)) {
            assertNull(left.netDue(now.plus(Duration.ofDays(days)), zone, defaultTime, SafetyNetSettings()), "not a ring let go, and not a wait at a circle")
        }
        assertTrue(left.copy(snoozedToRules = false).awaitingAnswer(now), "and without it the ring is still owed one")
    }

    @Test
    fun `it outranks nothing - the rules, the alarm, the fences and the watch are what they were`() {
        val rung = reminder(rule(market), rule(tomorrowNine)).rangFor(0)
        val left = rung.copy(snoozedToRules = true)
        assertEquals(nextFire(rung, now, zone, defaultTime), nextFire(left, now, zone, defaultTime))
        assertEquals(Wake(local(2026, 8, 28, 9, 0), 1), nextWake(left, now, zone, defaultTime))
        assertEquals(geofenceChoices(listOf(rung)), geofenceChoices(listOf(left)))
        assertEquals(rung.watchedCircles(now, zone, defaultTime), left.watchedCircles(now, zone, defaultTime))
        assertEquals(NextFire.WhenAt(market), nextFire(reminder(rule(market)).rangFor(0).copy(snoozedToRules = true), now, zone, defaultTime), "a door, not a snooze")
    }

    @Test
    fun `a hecho after it answers that ring and spends nothing ahead`() {
        val daily = Recurrence.After(1, RecurrenceUnit.DAYS)
        val left = reminder(rule(market), rule(tomorrowNine), recurrence = daily).rangFor(0).copy(snoozedToRules = true)
        assertNull(left.momentDealtWith(now, zone, defaultTime), "tomorrow's nine is not what was done")
        assertEquals(Status.DONE, statusAfterDismissal(reminder(rule(market)).rangFor(0).copy(snoozedToRules = true), now, zone, defaultTime))
    }

    @Test
    fun `Home files it back under whenever, out of the waiting card`() {
        val left = reminder(rule(market)).rangFor(0).copy(snoozedToRules = true)
        assertEquals(emptyList<Reminder>(), answersOwed(listOf(left), now))
        assertEquals(setOf(Section.WHENEVER), groupForHome(listOf(left), now, zone, defaultTime).sections.keys)
    }

    // --- the journey ---

    @Test
    fun `said at the door, it waits through the stay, and the next arrival rings it`() {
        val phone = Simulation(reminder(rule(market)), now)
        val first = phone.arrive(0) { Simulation.Deal.NextTime }!!
        assertEquals(0, first.ruleIndex)
        assertTrue(phone.reminder.snoozedToRules)
        assertFalse(phone.reminder.awaitingAnswer(phone.now))
        assertNull(phone.arm(), "nothing on the clock: the door is the alarm, as before the ring")
        phone.now = phone.now.plus(Duration.ofDays(3))
        val again = phone.arrive(0)!!
        assertEquals(0, again.ruleIndex, "its own rule rang it, not a snooze")
        assertFalse(phone.reminder.snoozedToRules, "the ring spends the answer")
        assertTrue(phone.reminder.awaitingAnswer(phone.now))
        phone.deal(Simulation.Deal.Done)
        assertEquals(Status.DONE, phone.reminder.status)
    }

    @Test
    fun `left to the rules, a sibling's moment still rings it`() {
        val phone = Simulation(reminder(rule(market), rule(tomorrowNine)), now)
        phone.arrive(0) { Simulation.Deal.NextTime }
        val nine = phone.step()!!
        assertEquals(local(2026, 8, 28, 9, 0), nine.at)
        assertEquals(1, nine.ruleIndex)
    }

    @Test
    fun `a ring put off to the next time and rung again is one round, done but not at the first ring`() {
        val events = listOf(
            FiringEvent(FiringKind.RANG, local(2026, 8, 24, 18, 0)),
            FiringEvent(FiringKind.SNOOZED, local(2026, 8, 24, 18, 1), detail = nextTimeDetail(market)),
            FiringEvent(FiringKind.RANG, local(2026, 8, 27, 18, 0)),
            FiringEvent(FiringKind.DEALT, local(2026, 8, 27, 18, 5)),
        )
        val round = rounds(events, RoundShape.REPEATING).single()
        assertEquals(RoundEnd.DONE, round.end)
        assertEquals(1, round.snoozes)
        assertFalse(round.firstTime)
    }

    // --- what the history line keeps ---

    @Test
    fun `the history line says which door, or none`() {
        assertEquals("next:arrive:Mercadona", nextTimeDetail(market))
        assertEquals("next:leave:Oficina", nextTimeDetail(office))
        assertEquals("next", nextTimeDetail(null))
        assertEquals(Presence.INSIDE to "Mercadona", nextTimeDoorOf(nextTimeDetail(market)))
        assertEquals(Presence.OUTSIDE to "Oficina", nextTimeDoorOf(nextTimeDetail(office)))
        assertNull(nextTimeDoorOf("next"))
        assertTrue(isNextTimeDetail("next") && isNextTimeDetail("next:leave:Oficina"))
        assertFalse(isNextTimeDetail("arrive:Mercadona") || isNextTimeDetail(LATER_DETAIL) || isNextTimeDetail(now.toString()))
        assertNull(snoozeDetailOf(nextTimeDetail(market)), "not read as a wait at a circle")
    }
}
