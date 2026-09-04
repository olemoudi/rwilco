package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * "Los viernes a las 14:00, y vuelve cada 30 días" — and which of the three things that can mean.
 *
 * Thirty days after Friday 1 May 2026 is **Sunday 31 May**, which the rules do not allow. The
 * whole of [SpanLanding] is what happens then, and the point of the test is that all three
 * answers are different dates and each is somebody's.
 */
class SpanLandingTest {

    private val dayStart = LocalTime.of(9, 0)

    /** Friday 1 May 2026, rung at two and dealt with five minutes later. */
    private val rang = local(2026, 5, 1, 14, 0)
    private val dealt = local(2026, 5, 1, 14, 5)

    /** The morning after: the rest is on, and the reminder is waiting for it. */
    private val now = local(2026, 5, 2, 10, 0)

    private fun reminder(landing: SpanLanding, hour: RecurrenceHour = RecurrenceHour.DayStart) =
        Fixtures.reminder(Trigger.TimeOfDay(LocalTime.of(14, 0), setOf(DayOfWeek.FRIDAY))).copy(
            recurrence = Recurrence.After(30, RecurrenceUnit.DAYS, hour = hour, landing = landing),
            lastFiredAt = rang,
            lastDealtAt = dealt,
        )

    private fun momentOf(landing: SpanLanding, hour: RecurrenceHour = RecurrenceHour.DayStart) =
        nextFire(reminder(landing, hour), now, zone, defaultTime, dayStart)?.moment

    @Test
    fun `the next allowed day is what it has always meant, and stays the default`() {
        // Sunday is not a Friday, so it waits for one: five days past the thirty. The span
        // stretches, every round, which is the cost of this reading and why it is now said out
        // loud rather than being what happens when nobody is asked.
        assertEquals(local(2026, 6, 5, 14, 0), momentOf(SpanLanding.NEXT))
        assertEquals(SpanLanding.NEXT, Recurrence.After(30, RecurrenceUnit.DAYS).landing, "nothing already written moves")
    }

    @Test
    fun `the closest allowed day can be before the span is up`() {
        // Friday the 29th is two days before the thirty are up; Friday the 5th is five days
        // after. Nearest is nearest, and going backwards is the whole reason this exists —
        // nothing else in the app can land a rest earlier than the span it counts.
        assertEquals(local(2026, 5, 29, 14, 0), momentOf(SpanLanding.NEAREST))
    }

    @Test
    fun `exactly the span means the span, whatever day it lands on`() {
        // Sunday the 31st, because thirty days is thirty days. The rules stop deciding and the
        // hour is the recurrence's own — which is why the editor hands it the hour the rules
        // were naming when somebody picks this.
        assertEquals(local(2026, 5, 31, 9, 0), momentOf(SpanLanding.EXACT), "at the hour the day starts")
        assertEquals(
            local(2026, 5, 31, 14, 0),
            momentOf(SpanLanding.EXACT, RecurrenceHour.At(LocalTime.of(14, 0))),
            "or at the one it was given",
        )
    }

    @Test
    fun `the alarm agrees with the answer, and it is the ring itself`() {
        // A wake with no rule behind it: under "justo el plazo" the moment IS the ring, the way
        // a recurrence's moment always is when the rules have nothing left to say.
        val wake = nextWake(reminder(SpanLanding.EXACT), now, zone, defaultTime, dayStart)
        assertNotNull(wake)
        assertEquals(local(2026, 5, 31, 9, 0), wake!!.at)
        assertEquals(null, wake.ruleIndex)
        // Whereas the other two are the rule's own moment, and are armed against the rule.
        assertEquals(0, nextWake(reminder(SpanLanding.NEAREST), now, zone, defaultTime, dayStart)!!.ruleIndex)
    }

    @Test
    fun `with no days named there is nothing to bend to`() {
        // The question only exists because the rules narrow the days. An hour every day lands
        // where the span lands under all three readings — and the editor does not ask at all.
        val everyDay = Fixtures.reminder(Trigger.TimeOfDay(LocalTime.of(14, 0))).copy(
            lastFiredAt = rang,
            lastDealtAt = dealt,
        )
        for (landing in SpanLanding.entries) {
            val moment = nextFire(
                everyDay.copy(recurrence = Recurrence.After(30, RecurrenceUnit.DAYS, landing = landing)),
                now,
                zone,
                defaultTime,
                dayStart,
            )?.moment
            val expected = if (landing == SpanLanding.EXACT) local(2026, 5, 31, 9, 0) else local(2026, 5, 31, 14, 0)
            assertEquals(expected, moment, "$landing")
        }
        assertEquals(emptySet<DayOfWeek>(), everyDay.daysNamedByRules())
    }

    @Test
    fun `the days come from the rules and their fences alike`() {
        val byCondition = Fixtures.reminder(
            Trigger.TimeOfDay(LocalTime.of(14, 0)),
            conditions = listOf(Condition.OnDays(setOf(DayOfWeek.FRIDAY))),
        ).copy(
            recurrence = Recurrence.After(30, RecurrenceUnit.DAYS, landing = SpanLanding.NEAREST),
            lastFiredAt = rang,
            lastDealtAt = dealt,
        )
        assertEquals(setOf(DayOfWeek.FRIDAY), byCondition.daysNamedByRules())
        assertEquals(local(2026, 5, 29, 14, 0), nextFire(byCondition, now, zone, defaultTime, dayStart)?.moment)
    }

    @Test
    fun `a place among the rules stops being watched under exactly the span`() {
        // The one cost of that reading, and it is stated on the button: the rules are out of
        // the loop, so there is nothing to watch and the span alone rings. Under the other two
        // the place is still what the reminder is waiting for once the rest is up.
        val withPlace = Fixtures.reminder(Trigger.Location(40.0, -3.0, 150, Presence.INSIDE, "Casa", onCrossing = true)).copy(
            lastFiredAt = rang,
            lastDealtAt = dealt,
        )
        val exact = withPlace.copy(recurrence = Recurrence.After(30, RecurrenceUnit.DAYS, landing = SpanLanding.EXACT))
        assertEquals(local(2026, 5, 31, 9, 0), nextFire(exact, now, zone, defaultTime, dayStart)?.moment)
        val next = withPlace.copy(recurrence = Recurrence.After(30, RecurrenceUnit.DAYS, landing = SpanLanding.NEXT))
        assert(nextFire(next, now, zone, defaultTime, dayStart) is NextFire.WhenAt) { "the circle is still the alarm" }
    }

    /**
     * "Al llegar a casa, sólo los viernes de 9 a 10, y vuelve cada 30 días — justo el plazo."
     *
     * Reachable: the fence names days, so the landing question is asked, and "justo el plazo"
     * says the rules stop deciding. What that has to mean is that the circle stops being
     * watched, stops being registered, and — the one that matters — that an arrival reaching
     * the firing anyway does not ring. A clock rule cannot leak here (nothing arms it), but a
     * place has no armed moment to check by design, so it is the one door left open.
     */
    private fun placeUnderExact() = Fixtures.reminder(
        Trigger.Location(40.4, -3.7, 150, Presence.INSIDE, "Casa", onCrossing = true),
        conditions = listOf(Condition.TimeWindow(LocalTime.of(9, 0), LocalTime.of(10, 0), setOf(DayOfWeek.FRIDAY))),
    ).copy(
        recurrence = Recurrence.After(30, RecurrenceUnit.DAYS, landing = SpanLanding.EXACT),
        lastFiredAt = rang,
        lastDealtAt = dealt,
    )

    @Test
    fun `once the span has taken over the rules are spent, rest or no rest`() {
        val reminder = placeUnderExact()
        assertTrue(reminder.spanHasTakenOver, "dealt with once, under «justo el plazo»")
        // Before the first "hecho" the rules are the whole arrangement and must be untouched.
        assertFalse(reminder.copy(lastDealtAt = null, lastFiredAt = null).spanHasTakenOver)
        // And it is a fact about this reading only: the other two leave the rules deciding.
        assertFalse(reminder.copy(recurrence = Recurrence.After(30, RecurrenceUnit.DAYS)).spanHasTakenOver)
        // A recurrence with no rules to take over from is the ring already; nothing to spend.
        assertFalse(
            Fixtures.reminder().copy(
                recurrence = Recurrence.After(30, RecurrenceUnit.DAYS, landing = SpanLanding.EXACT),
                lastDealtAt = dealt,
            ).spanHasTakenOver,
        )
    }

    @Test
    fun `a spent place is not watched, not registered, and does not ring`() {
        val reminder = placeUnderExact()
        // Long after the rest is up, which is when the gate on the rest alone stopped helping —
        // and inside the door's own hours, a Friday morning, because the harness judges an
        // arrival against them exactly as the phone does.
        val later = local(2026, 7, 3, 9, 30)
        assertEquals(emptyList<Gated>(), reminder.watchedCircles(later, zone, defaultTime, dayStart = dayStart))
        assertEquals(emptyList<Pair<String, Trigger.Location>>(), geofenceChoices(listOf(reminder)))
        // And the door itself: an arrival delivered anyway rings nothing.
        val phone = Simulation(reminder, later)
        assertEquals(null, phone.arrive(0), "the arrival rang under «justo el plazo»")
        assertTrue(phone.rings.isEmpty())
        // The same arrival on the same reminder under "el siguiente" is a ring, which is what
        // says the guard is about the reading and not about the place.
        val stillDeciding = Simulation(reminder.copy(recurrence = Recurrence.After(30, RecurrenceUnit.DAYS)), later)
        assertEquals(1, stillDeciding.arrive(0)?.let { 1 } ?: 0, "the other readings still watch the door")
    }

    @Test
    fun `a place waited at outranks even that`() {
        // A snooze is the person's own "cuando llegue a casa" about a ring that has already
        // happened, and it is the whole of the alarm: it is never what a recurrence spends.
        val waiting = placeUnderExact().copy(
            snoozedToPlace = Trigger.Location(40.4, -3.7, 150, Presence.INSIDE, "Casa", onCrossing = true),
        )
        val later = local(2026, 6, 30, 12, 0)
        assertEquals(1, waiting.watchedCircles(later, zone, defaultTime, dayStart = dayStart).size)
        assertEquals(1, geofenceChoices(listOf(waiting)).size)
    }

    @Test
    fun `the nearest allowed day, day by day`() {
        val fridays = setOf(DayOfWeek.FRIDAY)
        // Sunday the 31st: Friday the 29th is two back, Friday the 5th is five on.
        assertEquals(LocalDate.of(2026, 5, 29), nearestAllowedDay(LocalDate.of(2026, 5, 31), fridays))
        // A day that is already allowed stands, and so does one with nothing to be measured by.
        assertEquals(LocalDate.of(2026, 5, 29), nearestAllowedDay(LocalDate.of(2026, 5, 29), fridays))
        assertEquals(LocalDate.of(2026, 5, 31), nearestAllowedDay(LocalDate.of(2026, 5, 31), emptySet()))
        // A tie goes forward: Wednesday is three from Sunday either way, and coming back a
        // shade late is a smaller lie about "cada 30 días" than a series walking backwards.
        assertEquals(
            LocalDate.of(2026, 6, 3),
            nearestAllowedDay(LocalDate.of(2026, 5, 31), setOf(DayOfWeek.WEDNESDAY)),
        )
    }

    @Test
    fun `the field is additive, so nothing on disk changes shape`() {
        val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true; encodeDefaults = true }
        // What every phone already has written: no landing at all, which reads as the old
        // behaviour and would have been the answer anyway.
        val old = """{"type":"after","amount":30,"unit":"DAYS","from":"DEALT","hour":{"type":"day_start"}}"""
        assertEquals(
            Recurrence.After(30, RecurrenceUnit.DAYS),
            json.decodeFromString(Recurrence.serializer(), old),
        )
        assertEquals(
            """{"type":"after","amount":30,"unit":"DAYS","from":"DEALT","hour":{"type":"day_start"},"landing":"NEAREST"}""",
            json.encodeToString(Recurrence.serializer(), Recurrence.After(30, RecurrenceUnit.DAYS, landing = SpanLanding.NEAREST)),
        )
    }
}
