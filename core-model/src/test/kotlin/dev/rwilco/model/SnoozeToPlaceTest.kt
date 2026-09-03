package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.reminder
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDateTime

/**
 * What a reminder put off until a place is, everywhere the model is asked about it: what Home
 * shows, what the alarm is set for (nothing), what the watch and the fences are handed (that
 * circle and nothing else), how the id is told apart, how the column travels, and which of the
 * two offers a phone gets. The journeys — arriving, leaving, sleeping through — are in
 * `SnoozeJourneyTest`.
 */
class SnoozeToPlaceTest {

    private val home = SavedPlace("Casa", 40.4169, -3.7035, 200)
    private val office = SavedPlace("Oficina", 40.4500, -3.6900, 150)
    private val homeDoor = SnoozePlace.Arrive(home).circle()
    private val nine = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 28, 9, 0))
    private val clubRule = Trigger.Location(40.5, -3.7, 100, Presence.INSIDE, "Club")

    private fun waiting(vararg triggers: Trigger, place: Trigger.Location = homeDoor) =
        reminder(*triggers).copy(snoozedToPlace = place)

    // --- what it is ---

    @Test
    fun `the doorway in, whichever side the phone is on`() {
        assertEquals(Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa", onCrossing = true), homeDoor)
        val here = hereCircle(Fix(40.45, -3.69, 20.0, now), "aquí")
        assertEquals(Trigger.Location(40.45, -3.69, SNOOZE_HERE_MIN_RADIUS_M, Presence.OUTSIDE, "aquí", onCrossing = true), here)
    }

    @Test
    fun `Home files it under whenever, with the snooze said, and the alarm is set for nothing`() {
        val put = waiting(nine)
        assertEquals(NextFire.WhenAt(homeDoor, snoozed = true), nextFire(put, now, zone, defaultTime))
        assertNull(nextWake(put, now, zone, defaultTime))
        val groups = groupForHome(listOf(put), now, zone, defaultTime)
        assertNull(groups.hero, "a place with no floor is never the hero")
        assertEquals(setOf(Section.WHENEVER), groups.sections.keys)
        // The same reminder with no snooze is tomorrow's nine: the hero, on a Home with nothing else.
        assertNotNull(groupForHome(listOf(reminder(nine)), now, zone, defaultTime).hero)
    }

    @Test
    fun `put off is an answer to the ring and the missed-firing pass, and the net waits two days`() {
        val rang = waiting(nine).copy(lastFiredAt = now.minusSeconds(600), armedFor = now.minusSeconds(600))
        assertFalse(rang.awaitingAnswer(now))
        assertNull(missedFire(rang, now))
        // The net's one word about a wait: not "it got away" but "it is still waiting", due
        // two of the longest waits after the ring the snooze answered (SafetyNetTest).
        assertEquals(
            NetWord.WAITING,
            rang.netDue(now.plus(Duration.ofDays(2)), zone, defaultTime, SafetyNetSettings())?.word,
        )
        assertTrue(rang.copy(snoozedToPlace = null).awaitingAnswer(now), "and without it the ring is still owed one")
    }

    @Test
    fun `hecho on a one-shot waiting at a door finishes it`() {
        assertEquals(Status.DONE, statusAfterDismissal(waiting(nine).copy(lastFiredAt = now.minusSeconds(60)), now, zone, defaultTime))
    }

    // --- the circle the watch and the fences get ---

    @Test
    fun `the watch is handed that circle and nothing else, ungated`() {
        val put = waiting(nine, clubRule)
        val circles = put.watchedCircles(now, zone, defaultTime)
        val only = circles.single()
        assertEquals(SNOOZE_RULE, only.ruleIndex)
        assertNull(only.opensAt, "never gated: it is what the reminder is waiting for")
        assertFalse(only.resting)
        assertEquals(Crossing.RINGS, only.place.crossing)
        assertTrue(only.place.onCrossing)
        assertEquals(Transition.ENTER, only.place.transition)
        assertEquals("Casa", only.place.label)
        assertTrue(GeofenceIds.isSnooze(only.place.id))
        assertEquals(put.id, GeofenceIds.reminderIdOf(only.place.id))
        // Without the snooze the club's own circle is back.
        val own = reminder(nine, clubRule).watchedCircles(now, zone, defaultTime).single()
        assertEquals(1, own.ruleIndex)
        assertFalse(GeofenceIds.isSnooze(own.place.id))
    }

    @Test
    fun `leaving here is a circle waiting for the exit`() {
        val here = hereCircle(Fix(40.45, -3.69, 20.0, now), "aquí")
        val only = waiting(nine, place = here).watchedCircles(now, zone, defaultTime).single()
        assertEquals(Transition.EXIT, only.place.transition)
        assertTrue(only.place.id.endsWith(",X!"))
    }

    @Test
    fun `a paused reminder waits at no door`() {
        assertEquals(emptyList<Gated>(), waiting(nine).copy(status = Status.PAUSED).watchedCircles(now, zone, defaultTime))
    }

    // --- the id ---

    @Test
    fun `the snooze id is told apart from a rule's and a condition's, and reads as an id`() {
        val id = GeofenceIds.encodeSnooze("r1", homeDoor)
        assertEquals("r1#s@40.41690,-3.70350,200,E!", id)
        assertTrue(GeofenceIds.isSnooze(id))
        assertEquals("r1", GeofenceIds.reminderIdOf(id))
        assertNull(GeofenceIds.triggerIndexOf(id), "no rule behind it, like a condition's circle")
        assertTrue(GeofenceIds.looksLikeId(id))
        assertFalse(GeofenceIds.isSnooze(GeofenceIds.encode("r1", 0, homeDoor)))
        assertFalse(GeofenceIds.isSnooze(GeofenceIds.encodeCondition("r1", 0, 0, Condition.AtPlace(40.4169, -3.7035, 200, "Casa"))))
        assertFalse(GeofenceIds.isSnooze("Café #1 @ Sol"))
    }

    @Test
    fun `putting a reminder off to a place changes what the fences register`() {
        val before = reminder(nine, clubRule).watchedCircles(now, zone, defaultTime).map { it.place.id }
        val after = waiting(nine, clubRule).watchedCircles(now, zone, defaultTime).map { it.place.id }
        assertNotEquals(geofenceFingerprint(before, true), geofenceFingerprint(after, true))
    }

    // --- the column ---

    @Test
    fun `the place travels as JSON and anything unreadable is no snooze at all`() {
        val raw = ReminderCodec.encodeTrigger(homeDoor)
        assertEquals(homeDoor, ReminderCodec.decodeTrigger(raw))
        assertNull(ReminderCodec.decodeTrigger(null))
        assertNull(ReminderCodec.decodeTrigger(""))
        assertNull(ReminderCodec.decodeTrigger("{\"type\":\"from_the_future\"}"))
        assertNull(ReminderCodec.decodeTrigger("not json"))
    }

    @Test
    fun `the history line keeps which way and the name`() {
        assertEquals("arrive:Casa", homeDoor.snoozeDetail())
        assertEquals(Presence.INSIDE to "Casa", snoozeDetailOf("arrive:Casa"))
        assertEquals(Presence.OUTSIDE to "aquí", snoozeDetailOf(hereCircle(Fix(1.0, 1.0, 5.0, now), "aquí").snoozeDetail()))
        assertNull(snoozeDetailOf("2026-08-27T13:00:00Z"), "a clock snooze's detail is a clock")
    }

    // --- the offers ---

    @Test
    fun `the place offered is the one the reminders use most, and the first saved when none is used`() {
        val saved = listOf(home, office)
        assertEquals(home, mostUsedPlace(saved, emptyList()))
        val atOffice = Trigger.Location(office.lat + 0.00005, office.lng, 150, Presence.INSIDE, "Trabajo")
        val usesOffice = listOf(
            reminder(atOffice, id = "a"),
            reminder(nine, conditions = listOf(Condition.AtPlace(office.lat, office.lng, 150, "Oficina")), id = "b"),
            reminder(Trigger.Location(home.lat, home.lng, 200, Presence.INSIDE, "Casa"), id = "c"),
        )
        assertEquals(office, mostUsedPlace(saved, usesOffice), "two reminders name the office, one names home")
        assertNull(mostUsedPlace(emptyList(), usesOffice))
        val far = reminder(Trigger.Location(41.0, -3.0, 100, Presence.INSIDE, "Elsewhere"), id = "d")
        assertEquals(home, mostUsedPlace(saved, listOf(far)), "a place none of them names leaves the order as saved")
    }

    @Test
    fun `arriving is not offered from inside, and nothing is offered without a grant`() {
        val watch = PlaceWatchState()
        assertEquals(listOf(SnoozePlace.Arrive(home), SnoozePlace.LeaveHere(SNOOZE_HERE_MIN_RADIUS_M)), snoozePlaceOffers(listOf(home), emptyList(), watch, locationAllowed = true))
        assertEquals(listOf(SnoozePlace.LeaveHere(SNOOZE_HERE_MIN_RADIUS_M)), snoozePlaceOffers(emptyList(), emptyList(), watch, locationAllowed = true))
        assertEquals(emptyList<SnoozePlace>(), snoozePlaceOffers(listOf(home), emptyList(), watch, locationAllowed = false))
        val insideHome = PlaceWatchState(inside = mapOf(GeofenceIds.encode("x", 0, homeDoor.copy(onCrossing = false)) to true))
        assertFalse(arriveOffered(home, insideHome))
        assertEquals(listOf(SnoozePlace.LeaveHere(SNOOZE_HERE_MIN_RADIUS_M)), snoozePlaceOffers(listOf(home), emptyList(), insideHome, locationAllowed = true))
        val outsideHome = PlaceWatchState(inside = mapOf(GeofenceIds.encode("x", 0, homeDoor.copy(onCrossing = false)) to false))
        assertTrue(arriveOffered(home, outsideHome))
        // A doorway's own lean is not a word about where the phone is.
        val doorwayLean = PlaceWatchState(inside = mapOf(GeofenceIds.encode("x", 0, homeDoor) to true))
        assertTrue(arriveOffered(home, doorwayLean))
    }

    @Test
    fun `a position speaks for here only while it is fresh and tight`() {
        assertTrue(Fix(1.0, 1.0, 30.0, now).speaksForHere(now.plusSeconds(60)))
        assertFalse(Fix(1.0, 1.0, 30.0, now).speaksForHere(now.plus(Duration.ofMinutes(3))), "too old")
        assertFalse(Fix(1.0, 1.0, 400.0, now).speaksForHere(now), "sloppier than the circle it would draw")
    }

    @Test
    fun `aqui is as small as the fix can defend`() {
        // The complaint it comes from: "al salir de aquí" set in a park next door to the house
        // never rang, because leaving takes the radius PLUS the fix's own doubt — a flat 150 m
        // with a ±50 m position is two hundred metres of walking.
        assertEquals(SNOOZE_HERE_MIN_RADIUS_M, hereRadiusM(15.0), "under the open sky, the floor")
        assertEquals(SNOOZE_HERE_MIN_RADIUS_M, hereRadiusM(50.0), "and at fifty metres of doubt, still the floor")
        assertEquals(140, hereRadiusM(70.0), "indoors it grows with the doubt, not with a guess")
        assertEquals(300, hereRadiusM(HERE_FIX_MAX_ACCURACY_M), "the widest there is: nothing sloppier may draw it at all")
        // And the circle written is that radius around the fix.
        assertEquals(140, hereCircle(Fix(40.0, -3.0, 70.0, now), "aquí").radiusM)
    }

    @Test
    fun `a position is worth drawing for longer than it is worth waking somebody with`() {
        // The map's blue dot and the circle "al salir de aquí" draws are two different
        // questions about the same fix: one is looked at, the other is slept through.
        val fix = Fix(1.0, 1.0, 30.0, now)
        assertTrue(fix.worthDrawing(now.plus(Duration.ofMinutes(10))), "ten minutes old is still roughly here")
        assertFalse(fix.speaksForHere(now.plus(Duration.ofMinutes(10))), "and nowhere near tight enough to ring on")
        assertFalse(fix.worthDrawing(now.plus(Duration.ofMinutes(20))), "past the watch's own cadence it is not a dot, it is a memory")
        assertFalse(Fix(1.0, 1.0, 400.0, now).worthDrawing(now), "a fix vaguer than that is not where anybody is")
    }
}
