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

    // Keys: what the rule remembers the saved place by.

    private val keyed = office.copy(id = "office-key")
    private val keyedMoved = moved.copy(id = "office-key")

    private fun keyedLeaving(lat: Double, lng: Double, label: String = keyed.label, placeId: String? = keyed.id) =
        Trigger.Location(lat, lng, keyed.radiusM, Presence.OUTSIDE, label, onCrossing = true, placeId = placeId)

    private fun trigger(reminder: Reminder) = reminder.rules.single().trigger as Trigger.Location

    @Test
    fun `a rule is found by its key wherever its pin has drifted, and goes to the new pin`() {
        val drifted = reminder("a", TriggerRule(keyedLeaving(40.4, -3.6, label = "el curro")))
        val after = trigger(movePlaceIn(listOf(drifted), keyed, keyedMoved).single())
        assertEquals(moved.lat, after.lat)
        assertEquals(moved.lng, after.lng)
        assertEquals("el curro", after.label, "a name of its own is kept")
        assertEquals("office-key", after.placeId)
    }

    @Test
    fun `a rule keyed to another place is not this one's, even on the same pin`() {
        val other = reminder("a", TriggerRule(keyedLeaving(office.lat, office.lng, placeId = "somewhere-else")))
        assertEquals(emptyList<Reminder>(), movePlaceIn(listOf(other), keyed, keyedMoved))
    }

    @Test
    fun `a rule from before keys is known by its name too, and is given the key`() {
        val stale = reminder("a", TriggerRule(keyedLeaving(40.4, -3.6, label = "  oficina ", placeId = null)))
        val after = trigger(movePlaceIn(listOf(stale), keyed, keyedMoved).single())
        assertEquals(moved.lat, after.lat, "the place moved, so the copy goes to where it is now")
        assertEquals("office-key", after.placeId)
    }

    @Test
    fun `a rename reaches an unkeyed copy on an old pin without moving it`() {
        val stale = reminder("a", TriggerRule(keyedLeaving(40.4, -3.6, placeId = null)))
        val after = trigger(movePlaceIn(listOf(stale), keyed, keyed.copy(label = "Curro")).single())
        assertEquals(40.4, after.lat, "the edit did not move the place, so nothing moves the copy")
        assertEquals("Curro", after.label)
        assertEquals("office-key", after.placeId, "and from now on it is found by the key")
    }

    @Test
    fun `places kept before keys are named the same on every read, and apart`() {
        val legacy = listOf(office, office, home.copy(id = "home-key"))
        val first = legacy.withPlaceIds()
        assertEquals(first, legacy.withPlaceIds(), "stable until written")
        assertTrue(first.all { it.id.isNotBlank() })
        assertTrue(first[0].id != first[1].id, "two identical places are still two places")
        assertEquals("home-key", first[2].id, "a key already given is never replaced")
    }

    @Test
    fun `the key of a pin is the saved place exactly on it`() {
        val places = listOf(keyed, home.copy(id = "home-key"))
        assertEquals("office-key", places.idAt(office.lat, office.lng))
        assertEquals(null, places.idAt(office.lat + 0.000001, office.lng))
        assertEquals(null, places.idAt(null, null))
    }

    @Test
    fun `a snooze to a saved place carries its key`() {
        assertEquals("office-key", SnoozePlace.Arrive(keyed).circle().placeId)
    }

    @Test
    fun `deleting a place is asked about what still rings by it, found the way an edit finds it`() {
        val all = listOf(
            reminder("pin", TriggerRule(leaving(office))),
            reminder("key", TriggerRule(keyedLeaving(40.4, -3.6))),
            reminder("name", TriggerRule(keyedLeaving(40.4, -3.6, label = "OFICINA", placeId = null))),
            reminder("fence", TriggerRule(Trigger.AtDateTime(java.time.LocalDateTime.of(2026, 9, 24, 9, 0)), listOf(fence(office)))),
            reminder("snooze", snoozedToPlace = leaving(office)),
            reminder("done", TriggerRule(leaving(office)), status = Status.DONE),
            reminder("home", TriggerRule(leaving(home))),
            reminder("other-key", TriggerRule(keyedLeaving(office.lat, office.lng, placeId = "somewhere-else"))),
        )
        assertEquals(listOf("pin", "key", "name", "fence", "snooze"), placeUsersOf(all, keyed).map { it.id })
    }
}
