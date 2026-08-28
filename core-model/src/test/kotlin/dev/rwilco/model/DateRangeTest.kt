package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.zone
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * A stretch of the calendar: two days and nothing else.
 *
 * What [Trigger.Interval] is to a day, this is to a year, so the two are held to the same two
 * promises — it rings when the stretch opens, and it is *true* for the whole of it — and the
 * second is the one that does the work.
 */
class DateRangeTest {

    private val first = LocalDate.of(2026, 9, 1)
    private val fifteenth = LocalDate.of(2026, 9, 15)
    private val august = Trigger.DateRange(first, fifteenth)

    private val start: java.time.Instant = local(2026, 8, 20, 6, 0)

    @Test
    fun `it rings on the first day, at the hour a date with no hour has always meant`() {
        val next = nextFireOf(august, "r1", start, zone, defaultTime) as NextFire.Scheduled
        assertEquals(local(2026, 9, 1, 9, 0), next.at)
        assertEquals(august, next.trigger, "the row keeps the icon it is recognised by")
        // The setting is the hour, whatever it has been changed to.
        val later = nextFireOf(august, "r1", start, zone, LocalTime.of(19, 30)) as NextFire.Scheduled
        assertEquals(local(2026, 9, 1, 19, 30), later.at)
    }

    @Test
    fun `written while it is open, it rings on the next day it is still open`() {
        // The case that decides the shape: somebody writes "entre el 1 y el 15" on the 8th at
        // noon. A range that only ever rang at 09:00 on the 1st would be, for them, a reminder
        // that silently never rings — so it opens again each morning the stretch is still open,
        // exactly as Trigger.Interval does with a stretch of the day.
        val next = nextFireOf(august, "r1", local(2026, 9, 8, 12, 0), zone, defaultTime) as NextFire.Scheduled
        assertEquals(local(2026, 9, 9, 9, 0), next.at)
        // A minute past the opening on the first day is the second day, not nothing.
        assertEquals(
            local(2026, 9, 2, 9, 0),
            (nextFireOf(august, "r1", local(2026, 9, 1, 9, 1), zone, defaultTime) as NextFire.Scheduled).at,
        )
        // And it stops with the range: the last day's hour is the last one there is.
        assertEquals(
            local(2026, 9, 15, 9, 0),
            (nextFireOf(august, "r1", local(2026, 9, 14, 12, 0), zone, defaultTime) as NextFire.Scheduled).at,
        )
        assertNull(nextFireOf(august, "r1", local(2026, 9, 15, 9, 1), zone, defaultTime))
        assertNull(nextFireOf(august, "r1", local(2026, 10, 1, 12, 0), zone, defaultTime))
    }

    @Test
    fun `a range that is over is the one the editor calls past`() {
        val over = listOf(TriggerRule(august))
        assertTrue(warnings(over, local(2026, 10, 1, 12, 0), zone, defaultTime).any { it is ValidationWarning.InPast })
        // While it is open — even written after that morning's hour — there is nothing to warn about.
        assertTrue(warnings(over, local(2026, 9, 8, 18, 0), zone, defaultTime).none { it is ValidationWarning.InPast })
        assertTrue(warnings(over, start, zone, defaultTime).none { it is ValidationWarning.InPast })
    }

    @Test
    fun `it is true on every day of the stretch, both ends included`() {
        val state = august.asState()
        assertEquals(Condition.DateRange(first, fifteenth), state)
        val fence = state!!
        assertFalse(fence.holdsAt(local(2026, 8, 31, 23, 59), zone))
        assertTrue(fence.holdsAt(local(2026, 9, 1, 0, 0), zone), "the first day counts from midnight")
        assertTrue(fence.holdsAt(local(2026, 9, 8, 3, 30), zone))
        assertTrue(fence.holdsAt(local(2026, 9, 15, 23, 59), zone), "and the last one right to the end of it")
        assertFalse(fence.holdsAt(local(2026, 9, 16, 0, 0), zone))
        // Which is what makes it safe to AND with anything: it is a state, never an instant.
        assertFalse(august.isMoment)
    }

    @Test
    fun `a single day is a stretch, and one that ends before it starts is not`() {
        assertNull(problemOf(Trigger.DateRange(first, first)), "both ends count, so one day is a day")
        assertEquals(TriggerProblem.ENDS_BEFORE_START, problemOf(Trigger.DateRange(fifteenth, first)))
        assertNull(problemOf(Condition.DateRange(first, first)))
        assertEquals(TriggerProblem.ENDS_BEFORE_START, problemOf(Condition.DateRange(fifteenth, first)))
    }

    @Test
    fun `under "a la vez" it fences the place beside it`() {
        // The sentence the tile exists for: "al llegar a casa, y a la vez entre el 1 y el 15".
        val range = TriggerRule(august)
        val home = TriggerRule(Trigger.Location(40.4, -3.7, 150, Presence.INSIDE, "Casa"))
        val together = Reminder(
            id = "r",
            text = "x",
            rules = listOf(range, home),
            ruleMatch = RuleMatch.TOGETHER,
            createdAt = start,
            updatedAt = start,
        )
        assertTrue(
            together.ruleInSet(1)!!.conditions.contains(Condition.DateRange(first, fifteenth)),
            "the stretch reaches the place as a state: ${together.ruleInSet(1)!!.conditions}",
        )
        // Two states can coincide, so this is not the set that can never hold.
        assertFalse(together.momentsCannotCoincide())
        // And the place keeps being a place: there is no date to count down to.
        assertTrue(nextFire(together, start, zone, defaultTime) is NextFire.WhenAt)
    }

    @Test
    fun `its standing is whether we are in it`() {
        val together = Reminder(
            id = "r",
            text = "x",
            rules = listOf(TriggerRule(august), TriggerRule(Trigger.Interval(LocalTime.of(9, 0), LocalTime.of(10, 0)))),
            ruleMatch = RuleMatch.TOGETHER,
            createdAt = start,
            updatedAt = start,
        )
        assertEquals(RuleStanding.NOT_HOLDING, together.ruleStandings(start, zone).first())
        assertEquals(RuleStanding.HOLDING, together.ruleStandings(local(2026, 9, 8, 12, 0), zone).first())
    }

    @Test
    fun `the shape on disk is two dates under a frozen name`() {
        val json = Json.encodeToString(Trigger.serializer(), august)
        assertEquals("""{"type":"date_range","from":"2026-09-01","to":"2026-09-15"}""", json)
        assertEquals(august, Json.decodeFromString(Trigger.serializer(), json))
        val fence = Json.encodeToString(Condition.serializer(), Condition.DateRange(first, fifteenth))
        assertEquals("""{"type":"date_range","from":"2026-09-01","to":"2026-09-15"}""", fence)
        assertEquals(Condition.DateRange(first, fifteenth), Json.decodeFromString(Condition.serializer(), fence))
    }

    @Test
    fun `it is its own tile, and there is nothing in it worth offering again`() {
        assertEquals(TriggerKind.DATE_RANGE, august.kind)
        assertTrue(TriggerKind.DATE_RANGE in OFFERED_KINDS)
        assertEquals(TriggerFamily.TIME, august.family)
        // "Entre el 1 y el 15" is about one September, not a shape somebody reuses.
        assertTrue(suggestedTriggers(listOf(Fixtures.reminder(august)), start, zone).isEmpty())
    }
}
