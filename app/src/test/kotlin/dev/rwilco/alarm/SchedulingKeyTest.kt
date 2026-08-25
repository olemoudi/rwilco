package dev.rwilco.alarm

import dev.rwilco.model.Action
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Status
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

/**
 * What a change to a reminder has to say before the alarms are worked out again.
 *
 * The app re-arms when this key changes and not otherwise, which is what keeps the armed moment
 * — written back to the same row it was read from — from coming round as a change and arming
 * everything all over again. The cost of leaving something out of it is silent: the reminder
 * saves, the screen says what it should, and no alarm is ever set.
 */
class SchedulingKeyTest {

    private val written = Instant.parse("2026-08-27T13:00:00Z")

    private val note = Reminder(
        id = "r1",
        text = "Pastillas",
        createdAt = written,
        updatedAt = written,
    )

    private fun key(reminder: Reminder) = ReminderScheduler.schedulingKey(reminder)

    @Test
    fun `asking for a recurrence is asking for an alarm`() {
        // A note with no trigger, given "cada 6 h". Nothing else about it changed — same rules
        // (none), same status, same match, nothing ticked off, no snooze — so if the recurrence
        // is not part of the key this reminder never gets an alarm at all.
        val every6h = note.copy(recurrence = Recurrence.After(6, RecurrenceUnit.HOURS), updatedAt = written.plusSeconds(60))

        assertNotEquals(key(note), key(every6h), "a recurrence is the only thing saying when this rings")
        assertNotEquals(key(every6h), key(every6h.copy(recurrence = Recurrence.After(8, RecurrenceUnit.HOURS))))
        assertNotEquals(key(every6h), key(note), "and taking it away has to put the alarm out")
    }

    @Test
    fun `what decides when it rings is in the key`() {
        val at = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 9, 0))
        val timed = note.copy(rules = listOf(TriggerRule(at)))

        assertNotEquals(key(note), key(timed))
        assertNotEquals(key(timed), key(timed.copy(status = Status.PAUSED)))
        assertNotEquals(key(timed), key(timed.copy(ruleMatch = RuleMatch.ALL)))
        assertNotEquals(key(timed), key(timed.copy(firedRules = setOf(0))))
        assertNotEquals(key(timed), key(timed.copy(snoozedUntil = written.plusSeconds(600))))
    }

    @Test
    fun `what the scheduler writes back is not in it, or nothing would ever settle`() {
        val timed = note.copy(rules = listOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 9, 0)))))

        assertEquals(key(timed), key(timed.copy(armedFor = written.plusSeconds(3600), armedRule = 0)))
        assertEquals(key(timed), key(timed.copy(lastFiredAt = written)))
        assertEquals(key(timed), key(timed.copy(updatedAt = written.plusSeconds(1))))
        assertEquals(key(timed), key(timed.copy(text = "otra cosa", tags = listOf("casa"), actions = setOf(Action.SOUND))))
    }
}
