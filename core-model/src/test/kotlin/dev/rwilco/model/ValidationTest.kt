package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.local
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ValidationTest {

    private val tonight = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30))

    @Test
    fun `a complete reminder has no errors`() {
        assertTrue(validate("Water the plants", listOf(TriggerRule(tonight))).isEmpty())
    }

    @Test
    fun `only the words are required`() {
        assertEquals(listOf(ValidationError.TextBlank), validate("  ", emptyList()))
        assertEquals(listOf(ValidationError.TextTooLong), validate("x".repeat(MAX_TEXT_LENGTH + 1), listOf(TriggerRule(tonight))))
    }

    @Test
    fun `a reminder with nothing to fire is a note, not an error`() {
        // Kept under a tag and never rung: "lista de la compra" is a list, not an alarm.
        assertTrue(validate("Pilas AA", emptyList()).isEmpty())
    }

    @Test
    fun `each trigger problem is reported with its index`() {
        val triggers = listOf(
            tonight,
            Trigger.AtTime(LocalTime.of(7, 0), emptySet()),
            Trigger.Location(95.0, 0.0, 200, Presence.INSIDE, "x"),
            Trigger.Location(40.0, -3.0, 20, Presence.OUTSIDE, "x"),
            Trigger.Location(40.0, -3.0, 200, Presence.OUTSIDE, "y".repeat(MAX_LABEL_LENGTH + 1)),
            Trigger.Random(0, Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0), emptySet()),
            Trigger.Random(3, Period.DAY, LocalTime.of(10, 0), LocalTime.of(10, 2), emptySet()),
            Trigger.Random(1, Period.WEEK, LocalTime.of(10, 0), LocalTime.of(10, 0), emptySet()),
            // Ends before it starts: an evening that runs past midnight, and nothing wrong with it.
            Trigger.Random(1, Period.WEEK, LocalTime.of(22, 0), LocalTime.of(2, 0), emptySet()),
        )
        assertEquals(
            listOf(
                ValidationError.BadTrigger(1, TriggerProblem.DAYS_EMPTY),
                ValidationError.BadTrigger(2, TriggerProblem.COORDINATES_INVALID),
                ValidationError.BadTrigger(3, TriggerProblem.RADIUS_OUT_OF_RANGE),
                ValidationError.BadTrigger(4, TriggerProblem.LABEL_TOO_LONG),
                ValidationError.BadTrigger(5, TriggerProblem.TIMES_OUT_OF_RANGE),
                ValidationError.BadTrigger(6, TriggerProblem.WINDOW_EMPTY),
                ValidationError.BadTrigger(7, TriggerProblem.WINDOW_EMPTY),
            ),
            validate("ok", triggers.asRules()),
        )
    }

    @Test
    fun `a window that starts where it ends is not a window`() {
        val rules = listOf(
            TriggerRule(tonight, listOf(Condition.TimeWindow(LocalTime.of(9, 0), LocalTime.of(9, 0)))),
            TriggerRule(tonight, listOf(Condition.TimeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0)))),
        )
        assertEquals(
            listOf(ValidationError.BadTrigger(0, TriggerProblem.WINDOW_EMPTY)),
            validate("ok", rules),
            "crossing midnight is a window; starting where it ends is not",
        )
    }

    /**
     * The warning is only worth anything if it is about *this* reminder. A random window's
     * moments are drawn from (id, period), so the same rules under two ids are two different
     * moments — and the editor has to judge the moment the scheduler is going to arm, not one
     * drawn from an id nobody will save.
     */
    @Test
    fun `a warning about a random window is judged with the id the reminder will have`() {
        // "El sábado 29, a cualquier hora, y a la vez una vez a la semana al azar": the draw is
        // made inside the Saturday, so every id rings that day — at a minute of its own.
        val saturday = TriggerRule(Trigger.DayRandom(LocalDate.of(2026, 8, 29)))
        val onceAWeek = TriggerRule(Trigger.Random(1, Period.WEEK, LocalTime.of(9, 0), LocalTime.of(21, 0)))
        val rules = listOf(saturday, onceAWeek)
        val ids = (0..15).map { "id-$it" }

        val moments = ids.map { id ->
            val reminder = Reminder(
                id = id,
                text = "Llamar al taller",
                rules = rules,
                ruleMatch = RuleMatch.TOGETHER,
                createdAt = now,
                updatedAt = now,
            )
            val next = nextFire(reminder, now, zone, defaultTime)
            val warned = warnings(rules, now, zone, defaultTime, RuleMatch.TOGETHER, reminderId = id)
                .any { it is ValidationWarning.NeverFires }
            assertEquals(next == null, warned, "the editor and the scheduler disagreed about $id")
            assertTrue(next != null && next.moment?.atZone(zone)?.toLocalDate() == LocalDate.of(2026, 8, 29), "$id: $next")
            next!!.moment
        }
        assertTrue(moments.distinct().size > 1, "the moment is the id's own; the verdict is not")
    }

    @Test
    fun `a stretch months away is not called never`() {
        // "A las nueve, sólo del 1 al 15 de agosto", written in April. The walk used to give up
        // after sixty-four daily moments — nine weeks, well short of August — and the editor
        // said "nunca sonará" of a reminder that was going to ring on the first of the month.
        val april = local(2026, 4, 15, 12, 0)
        val august = Condition.DateRange(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15))
        val rule = TriggerRule(Trigger.TimeOfDay(LocalTime.of(9, 0)), listOf(august))
        assertTrue(warnings(listOf(rule), april, zone, defaultTime).none { it is ValidationWarning.NeverFires })
        // And one whose stretch is already behind it is called never at once, not after a walk.
        val september = local(2026, 9, 1, 12, 0)
        assertTrue(warnings(listOf(rule), september, zone, defaultTime).any { it is ValidationWarning.NeverFires })
    }

    @Test
    fun `a countdown that ran out is a warning like any other moment behind us`() {
        // Half an hour, started an hour ago. It was on the catch-up's list of one-shot shapes
        // and not on the editor's, so it saved without a word and never rang.
        val ranOut = TriggerRule(Trigger.Countdown(30, startedAt = now.minusSeconds(3600)))
        assertEquals(listOf(ValidationWarning.InPast(0)), warnings(listOf(ranOut), now, zone, defaultTime))
        assertTrue(warnings(listOf(TriggerRule(Trigger.Countdown(30, startedAt = now))), now, zone, defaultTime).isEmpty())
    }

    @Test
    fun `a random whose fences allow no minute is called never`() {
        // Drawn inside its fences, a window with no minute inside them has no draw at all —
        // and no draw is not "in the past", it is never.
        val mornings = Trigger.Random(3, Period.DAY, LocalTime.of(10, 0), LocalTime.of(12, 0))
        val evenings = Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))
        assertEquals(listOf(ValidationWarning.NeverFires(0)), warnings(listOf(TriggerRule(mornings, listOf(evenings))), now, zone, defaultTime))
    }

    @Test
    fun `one-shot moments already behind are a warning, repeating ones never`() {
        val triggers = listOf(
            Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 8, 0)),
            Trigger.OnDate(LocalDate.of(2026, 8, 27)),
            Trigger.AtTime(LocalTime.of(8, 0), setOf(DayOfWeek.THURSDAY)),
            tonight,
        )
        assertEquals(
            listOf(ValidationWarning.InPast(0), ValidationWarning.InPast(1)),
            warnings(triggers.asRules(), now, zone, defaultTime),
        )
    }

    @Test
    fun `a span below one is nonsense, whatever wrote it`() {
        assertEquals(TriggerProblem.EVERY_OUT_OF_RANGE, problemOf(Recurrence.After(0, RecurrenceUnit.HOURS)))
        assertEquals(TriggerProblem.EVERY_OUT_OF_RANGE, problemOf(Recurrence.After(-2, RecurrenceUnit.DAYS)))
        assertEquals(null, problemOf(Recurrence.After(1, RecurrenceUnit.HOURS)))
        assertEquals(null, problemOf(Recurrence.None))
    }
}
