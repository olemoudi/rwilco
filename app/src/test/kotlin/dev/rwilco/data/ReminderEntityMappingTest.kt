package dev.rwilco.data

import dev.rwilco.model.Action
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderEntityMappingTest {

    private val reminder = Reminder(
        id = "abc",
        text = "Water the plants",
        tags = listOf("casa", "balcón"),
        triggers = listOf(
            Trigger.AtTime(LocalTime.of(10, 0), setOf(DayOfWeek.SATURDAY)),
            Trigger.Location(40.4, -3.7, 200, Transition.ENTER, "Casa"),
        ),
        actions = setOf(Action.FULL_SCREEN, Action.VIBRATE),
        status = Status.PAUSED,
        createdAt = Instant.ofEpochMilli(1_700_000_000_000),
        updatedAt = Instant.ofEpochMilli(1_700_000_500_000),
        doneAt = null,
    )

    @Test
    fun `a reminder survives the trip through its row`() {
        assertEquals(reminder, reminder.toEntity().toDomain())
        val done = reminder.copy(status = Status.DONE, doneAt = Instant.ofEpochMilli(1_700_000_900_000))
        assertEquals(done, done.toEntity().toDomain())
    }

    @Test
    fun `the row stores JSON text and epoch millis`() {
        val row = reminder.toEntity()
        assertEquals("""["casa","balcón"]""", row.tags)
        assertEquals("""["FULL_SCREEN","VIBRATE"]""", row.actions)
        assertEquals("PAUSED", row.status)
        assertEquals(1_700_000_000_000, row.createdAt)
    }

    @Test
    fun `the firing state survives the trip too`() {
        val firing = reminder.copy(
            snoozedUntil = Instant.ofEpochMilli(1_700_001_000_000),
            lastFiredAt = Instant.ofEpochMilli(1_700_000_700_000),
            armedFor = Instant.ofEpochMilli(1_700_000_800_000),
        )
        assertEquals(firing, firing.toEntity().toDomain())
        val row = firing.toEntity()
        assertEquals(1_700_001_000_000, row.snoozedUntil)
        assertEquals(1_700_000_700_000, row.lastFiredAt)
        assertEquals(1_700_000_800_000, row.armedFor)
    }

    @Test
    fun `a row written before the firing columns existed reads as never fired`() {
        val old = reminder.toEntity().copy(snoozedUntil = null, lastFiredAt = null, armedFor = null)
        val domain = old.toDomain()
        assertEquals(null, domain.snoozedUntil)
        assertEquals(null, domain.lastFiredAt)
        assertEquals(null, domain.armedFor)
    }

    @Test
    fun `a status from the future reads as active`() {
        val row = reminder.toEntity().copy(status = "SNOOZED")
        assertEquals(Status.ACTIVE, row.toDomain().status)
    }

    @Test
    fun `a trigger from the future is dropped without losing the row`() {
        val row = reminder.toEntity().copy(triggers = """[{"type":"at_date_time","at":"2026-08-27T21:30"},{"type":"mood","value":3}]""")
        assertEquals(listOf(Trigger.AtDateTime(LocalDateTime.of(2026, 8, 27, 21, 30))), row.toDomain().triggers)
    }
}
