package dev.rwilco.model

import dev.rwilco.model.Fixtures.now
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SavedPlaceMoveTest {

    private val office = SavedPlace("Oficina", 40.501234, -3.661234, 200)
    private val moved = SavedPlace("Oficina", 40.502, -3.662, 250)
    private val home = SavedPlace("Casa", 40.43, -3.67, 50)

    private fun leaving(place: SavedPlace, radiusM: Int = place.radiusM, label: String = place.label) =
        Trigger.Location(place.lat, place.lng, radiusM, Presence.OUTSIDE, label, onCrossing = true)

    private fun fence(place: SavedPlace) = Condition.AtPlace(place.lat, place.lng, place.radiusM, place.label)

    private fun reminder(
        id: String,
        vararg rules: TriggerRule,
        status: Status = Status.ACTIVE,
        recurrence: Recurrence = Recurrence.None,
        snoozedToPlace: Trigger.Location? = null,
    ) = Reminder(
        id = id,
        text = "registrar jornada",
        rules = rules.toList(),
        status = status,
        recurrence = recurrence,
        snoozedToPlace = snoozedToPlace,
        createdAt = now.minusSeconds(1_000),
        updatedAt = now.minusSeconds(500),
    )

    @Test
    fun `a reminder on the old pin moves with it, and one somewhere else is not touched`() {
        val all = listOf(
            reminder("office", TriggerRule(leaving(office))),
            reminder("home", TriggerRule(leaving(home))),
        )
        val changed = movePlaceIn(all, office, moved)
        assertEquals(listOf("office"), changed.map { it.id })
        assertEquals(leaving(moved), changed.single().rules.single().trigger)
    }

    @Test
    fun `what a copy tuned for itself stays its own`() {
        val own = reminder("own", TriggerRule(leaving(office, radiusM = 400, label = "el curro")))
        val trigger = movePlaceIn(listOf(own), office, moved).single().rules.single().trigger as Trigger.Location
        assertEquals(moved.lat, trigger.lat)
        assertEquals(moved.lng, trigger.lng)
        assertEquals(400, trigger.radiusM, "its own radius, not the saved one")
        assertEquals("el curro", trigger.label, "its own name, not the saved one")
        assertEquals(Presence.OUTSIDE, trigger.presence)
        assertTrue(trigger.onCrossing)
    }

    @Test
    fun `a rename alone reaches the copies that carried the old name`() {
        val renamed = office.copy(label = "Curro")
        val changed = movePlaceIn(listOf(reminder("a", TriggerRule(leaving(office)))), office, renamed)
        assertEquals("Curro", (changed.single().rules.single().trigger as Trigger.Location).label)
    }

    @Test
    fun `the fences move too, on the rules and on the calendar`() {
        val nine = Trigger.AtDateTime(java.time.LocalDateTime.of(2026, 9, 24, 9, 0))
        val calendar = Recurrence.Calendar(
            Trigger.Repeat(startsOn = LocalDate.of(2026, 9, 1), unit = RepeatUnit.DAY),
            conditions = listOf(fence(office)),
        )
        val changed = movePlaceIn(
            listOf(reminder("a", TriggerRule(nine, listOf(fence(office))), recurrence = calendar)),
            office,
            moved,
        ).single()
        assertEquals(listOf(fence(moved)), changed.rules.single().conditions)
        assertEquals(listOf(fence(moved)), changed.recurrence.conditions)
    }

    @Test
    fun `a snooze waiting at that door moves with the door`() {
        val waiting = reminder("a", snoozedToPlace = leaving(office))
        assertEquals(leaving(moved), movePlaceIn(listOf(waiting), office, moved).single().snoozedToPlace)
    }

    @Test
    fun `done reminders are history and are left as they were written`() {
        val done = reminder("a", TriggerRule(leaving(office)), status = Status.DONE)
        val paused = reminder("b", TriggerRule(leaving(office)), status = Status.PAUSED)
        assertEquals(listOf("b"), movePlaceIn(listOf(done, paused), office, moved).map { it.id })
    }

    @Test
    fun `saving the place unchanged changes nothing`() {
        assertEquals(emptyList<Reminder>(), movePlaceIn(listOf(reminder("a", TriggerRule(leaving(office)))), office, office))
    }

    @Test
    fun `presets made on the place move with it`() {
        val presets = listOf(
            Preset(id = "p", name = "jornada", rules = listOf(TriggerRule(leaving(office))), createdAt = now),
            Preset(id = "q", name = "casa", rules = listOf(TriggerRule(leaving(home))), createdAt = now),
        )
        val after = movePlaceInPresets(presets, office, moved)
        assertEquals(leaving(moved), after[0].rules.single().trigger)
        assertEquals(presets[1], after[1])
    }
}
