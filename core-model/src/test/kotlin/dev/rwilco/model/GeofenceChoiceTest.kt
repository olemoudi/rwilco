package dev.rwilco.model

import dev.rwilco.model.Fixtures.reminder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** Which circles deserve one of the hundred fences — the pure half of `GeofenceManager.sync`. */
class GeofenceChoiceTest {

    private val casa = Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa")
    private val club = Trigger.Location(40.5, -3.7, 100, Presence.INSIDE, "Club")
    private val nine = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 9, 0))
    private val door = SnoozePlace.Arrive(SavedPlace("Casa", 40.4169, -3.7035, 200)).circle()

    @Test
    fun `only active reminders with place rules buy fences`() {
        val choices = geofenceChoices(
            listOf(
                reminder(casa, id = "a"),
                reminder(casa, id = "b", status = Status.PAUSED),
                reminder(nine, id = "c"),
            ),
        )
        assertEquals(listOf("a"), choices.map { GeofenceIds.reminderIdOf(it.first) })
        assertEquals(casa, choices.single().second)
    }

    @Test
    fun `a condition's circle is never fenced`() {
        // A geofence reports a crossing and a condition has none; the watch tracks its state.
        val fenced = geofenceChoices(listOf(reminder(nine, conditions = listOf(Condition.AtPlace(40.4, -3.7, 150, "Casa")))))
        assertTrue(fenced.isEmpty())
    }

    @Test
    fun `a ticked place under todos keeps its fence for the crossing back`() {
        val set = reminder(casa, club).copy(ruleMatch = RuleMatch.ALL, firedRules = setOf(0))
        val ids = geofenceChoices(listOf(set)).map { it.first }
        assertEquals(2, ids.size, "the ticked casa still watched for the crossing back, the club still waited on")
        assertTrue(ids.any { GeofenceIds.triggerIndexOf(it) == 0 })
        assertTrue(ids.any { GeofenceIds.triggerIndexOf(it) == 1 })
    }

    @Test
    fun `the snooze circle outranks its reminder's own rules`() {
        val choices = geofenceChoices(listOf(reminder(nine, club, id = "w").copy(snoozedToPlace = door)))
        val only = choices.single()
        assertTrue(GeofenceIds.isSnooze(only.first))
        assertEquals(door, only.second)
    }

    @Test
    fun `past the hundred the newest win and a snooze circle is never cut`() {
        val crowd = (1..105).map { reminder(casa.copy(lat = 40.0 + it * 0.001), id = "r$it") }
        val waitingOldest = reminder(nine, id = "r0").copy(snoozedToPlace = door)
        val choices = geofenceChoices(listOf(waitingOldest) + crowd)
        assertEquals(MAX_GEOFENCES, choices.size)
        assertTrue(choices.any { GeofenceIds.isSnooze(it.first) }, "the snooze is the whole of its alarm and is never cut")
        assertFalse(choices.any { GeofenceIds.reminderIdOf(it.first) == "r1" }, "the oldest ordinary fence is the one cut")
        assertTrue(choices.any { GeofenceIds.reminderIdOf(it.first) == "r105" })
    }
}
