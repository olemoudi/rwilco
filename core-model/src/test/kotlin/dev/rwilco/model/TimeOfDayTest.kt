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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * An hour of the day and nothing else — the point [Trigger.Interval] is a stretch of.
 *
 * It exists to be combined, so most of this is about what it does in company: it is the *moment*
 * a set is built around, and everything else is the state it has to land inside.
 */
class TimeOfDayTest {

    private val nine = Trigger.TimeOfDay(LocalTime.of(9, 0))
    private val weekdayNine = Trigger.TimeOfDay(LocalTime.of(9, 0), WEEKDAYS)

    /** Thursday 2026-08-27 at 15:00, the shared clock: nine has been and gone. */
    private val now = Fixtures.now

    @Test
    fun `no days is every day, unlike the weekly it is not`() {
        // AtTime reads an empty day set as "never", which is right for a weekly appointment and
        // wrong for a shape of the day — the same distinction Interval draws.
        val next = nextFireOf(nine, "r1", now, zone, defaultTime) as NextFire.Scheduled
        assertEquals(local(2026, 8, 28, 9, 0), next.at, "tomorrow morning")
        assertNull(nextFireOf(Trigger.AtTime(LocalTime.of(9, 0), emptySet()), "r1", now, zone, defaultTime))
        assertNull(problemOf(nine), "an hour is never nonsense in itself")
    }

    @Test
    fun `days it does not count on are stepped over`() {
        // Friday the 28th is the next weekday morning; from Friday afternoon it is Monday.
        assertEquals(
            local(2026, 8, 28, 9, 0),
            (nextFireOf(weekdayNine, "r1", now, zone, defaultTime) as NextFire.Scheduled).at,
        )
        assertEquals(
            local(2026, 8, 31, 9, 0),
            (nextFireOf(weekdayNine, "r1", local(2026, 8, 28, 15, 0), zone, defaultTime) as NextFire.Scheduled).at,
        )
    }

    @Test
    fun `it is a moment, which is the whole difference from a window`() {
        assertTrue(nine.isMoment)
        assertNull(nine.asState())
        assertFalse(Trigger.Interval(LocalTime.of(9, 0), LocalTime.of(10, 0)).isMoment)
    }

    @Test
    fun `combined with a stretch of the calendar it is the moment inside it`() {
        // The sentence this tile was asked for: "a las 09:00, y a la vez entre el 1 y el 15".
        val hour = TriggerRule(nine)
        val september = TriggerRule(Trigger.DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15)))
        val together = Reminder(
            id = "r",
            text = "x",
            rules = listOf(hour, september),
            ruleMatch = RuleMatch.TOGETHER,
            createdAt = now,
            updatedAt = now,
        )
        // One moment and one state: a set that can hold, so nothing is warned about.
        assertFalse(together.momentsCannotCoincide())
        assertTrue(warnings(listOf(hour, september), now, zone, defaultTime, RuleMatch.TOGETHER).isEmpty())
        // And the first nine o'clock the stretch allows is the ring — not tomorrow's.
        val next = nextFire(together, now, zone, defaultTime) as NextFire.Scheduled
        assertEquals(local(2026, 9, 1, 9, 0), next.at)
    }

    @Test
    fun `combined with a place it is the moment the place has to be true at`() {
        val hour = TriggerRule(nine)
        val home = TriggerRule(Trigger.Location(40.4, -3.7, 150, Presence.INSIDE, "Casa"))
        val together = Reminder(
            id = "r",
            text = "x",
            rules = listOf(hour, home),
            ruleMatch = RuleMatch.TOGETHER,
            createdAt = now,
            updatedAt = now,
        )
        assertTrue(
            together.ruleInSet(0)!!.conditions.any { it is Condition.AtPlace },
            "the place reaches the hour as a state, to be asked when the alarm goes off",
        )
        assertEquals(local(2026, 8, 28, 9, 0), (nextFire(together, now, zone, defaultTime) as NextFire.Scheduled).at)
    }

    @Test
    fun `two of them cannot be the same instant, and the editor says so`() {
        val nineAndTen = listOf(TriggerRule(nine), TriggerRule(Trigger.TimeOfDay(LocalTime.of(10, 0))))
        assertTrue(
            warnings(nineAndTen, now, zone, defaultTime, RuleMatch.TOGETHER).any { it is ValidationWarning.MomentsCannotCoincide },
        )
    }

    @Test
    fun `it answers "when in the day", so a calendar written after it opens on that hour`() {
        assertEquals(DayTiming.At(LocalTime.of(9, 0)), dayTimingOf(listOf(TriggerRule(nine))))
    }

    @Test
    fun `a standing hour is a shape worth offering again`() {
        // Unlike a date, "a las nueve los laborables" has nothing in it belonging to one reminder.
        val past = listOf(Fixtures.reminder(weekdayNine))
        assertEquals(listOf<Trigger>(weekdayNine), suggestedTriggers(past, now, zone))
    }

    @Test
    fun `the shape on disk is its own, and not the weekly it would be folded into`() {
        val json = Json.encodeToString(Trigger.serializer(), weekdayNine)
        assertTrue(json.startsWith("""{"type":"time_of_day","time":"09:00"""), json)
        assertEquals(weekdayNine, Json.decodeFromString(Trigger.serializer(), json))
        // The thing it deliberately is not: an AtTime rule is folded away into the calendar it
        // always was, and a tile writing one would resurrect every repeat that move retired.
        val kept = foldRepeats(listOf(TriggerRule(weekdayNine)), Recurrence.None, LocalDate.of(2026, 8, 27))
        assertNull(kept.index, "a time of day is not a legacy repeat: ${kept.rules}")
        assertEquals(listOf(TriggerRule(weekdayNine)), kept.rules)
        val folded = foldRepeats(listOf(TriggerRule(Trigger.AtTime(LocalTime.of(9, 0), WEEKDAYS))), Recurrence.None, LocalDate.of(2026, 8, 27))
        assertTrue(folded.recurrence is Recurrence.Calendar, "whereas AtTime still is")
    }

    @Test
    fun `it is its own tile, beside the stretch it is a point of`() {
        assertEquals(TriggerKind.TIME_OF_DAY, nine.kind)
        assertEquals(TriggerFamily.TIME, nine.family)
        assertEquals(
            listOf(TriggerKind.DATE, TriggerKind.DATE_RANGE, TriggerKind.TIME_OF_DAY, TriggerKind.INTERVAL),
            OFFERED_KINDS.take(4),
        )
    }
}

private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
