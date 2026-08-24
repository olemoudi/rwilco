package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
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
        assertTrue(validate("Water the plants", listOf(tonight), DEFAULT_ACTIONS).isEmpty())
    }

    @Test
    fun `text, trigger and action are each required`() {
        assertEquals(
            listOf(ValidationError.TextBlank, ValidationError.NoTrigger, ValidationError.NoAction),
            validate("  ", emptyList(), emptySet()),
        )
        assertEquals(listOf(ValidationError.TextTooLong), validate("x".repeat(MAX_TEXT_LENGTH + 1), listOf(tonight), DEFAULT_ACTIONS))
    }

    @Test
    fun `each trigger problem is reported with its index`() {
        val triggers = listOf(
            tonight,
            Trigger.AtTime(LocalTime.of(7, 0), emptySet()),
            Trigger.Location(95.0, 0.0, 200, Transition.ENTER, "x"),
            Trigger.Location(40.0, -3.0, 50, Transition.EXIT, "x"),
            Trigger.Location(40.0, -3.0, 200, Transition.EXIT, "y".repeat(MAX_LABEL_LENGTH + 1)),
            Trigger.Random(0, Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0), emptySet()),
            Trigger.Random(3, Period.DAY, LocalTime.of(10, 0), LocalTime.of(10, 2), emptySet()),
            Trigger.Random(1, Period.WEEK, LocalTime.of(10, 0), LocalTime.of(9, 0), emptySet()),
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
            validate("ok", triggers, DEFAULT_ACTIONS),
        )
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
            warnings(triggers, now, zone, defaultTime),
        )
    }
}
