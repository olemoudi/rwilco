package dev.rwilco.model

import dev.rwilco.model.Fixtures.defaultTime
import dev.rwilco.model.Fixtures.now
import dev.rwilco.model.Fixtures.zone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The place condition — "y sólo si estoy en casa" — and what the editor can say about a set of
 * rules before anybody waits a week to find out it never rings.
 */
class PlaceConditionTest {

    // Puerta del Sol, and a point 1.5 km north of it.
    private val homeLat = 40.4169
    private val homeLng = -3.7035
    private val home = Condition.AtPlace(homeLat, homeLng, radiusM = 200, label = "Casa")
    private val office = Condition.AtPlace(homeLat + 1_500 / 111_195.0, homeLng, radiusM = 150, label = "Oficina")

    private fun at(metresNorth: Double, accuracy: Double = 10.0) = Fix(homeLat + metresNorth / 111_195.0, homeLng, accuracyM = accuracy, at = now)

    @Test
    fun `a fix too sloppy to answer is no answer, and the condition holds`() {
        val atHome = Condition.AtPlace(homeLat, homeLng, 200, "Casa", inside = true)
        val away = Condition.AtPlace(homeLat, homeLng, 200, "Casa", inside = false)
        val sloppy = at(400.0, accuracy = 1500.0)
        assertTrue(atHome.holdsAt(now, zone, sloppy), "a kilometre of doubt cannot say no")
        assertTrue(away.holdsAt(now, zone, sloppy))
        assertFalse(atHome.holdsAt(now, zone, at(400.0)), "a good fix outside still says no")
    }

    private val evening = Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0))
    private val nineAm = Trigger.AtTime(LocalTime.of(9, 0), java.time.DayOfWeek.entries.toSet())
    private val arriveHome = Trigger.Location(homeLat, homeLng, 200, Presence.INSIDE, "Casa")

    @Test
    fun `a place condition is about where the phone is, and holds when nobody knows`() {
        assertTrue(home.holdsAt(now, zone, at(0.0)), "standing in the middle of it")
        assertFalse(home.holdsAt(now, zone, at(5_000.0)), "five kilometres away")
        assertFalse(home.copy(inside = false).holdsAt(now, zone, at(0.0)))
        assertTrue(home.copy(inside = false).holdsAt(now, zone, at(5_000.0)))
        // The house rule: what nothing can vouch for rings. A reminder that never arrives is
        // the failure somebody notices.
        assertTrue(home.holdsAt(now, zone, where = null))
        assertTrue(home.copy(inside = false).holdsAt(now, zone, where = null))
    }

    @Test
    fun `a time window never needs to know where anybody is`() {
        val nine = LocalDateTime.of(2026, 8, 27, 9, 0).atZone(zone).toInstant()
        assertFalse(evening.holdsAt(nine, zone, at(0.0)))
        assertFalse(evening.holdsAt(nine, zone, where = null))
    }

    @Test
    fun `the scheduler arms in spite of a place condition, because nothing knows where you will be`() {
        // On its own the rule is armed for nine tomorrow...
        val bare = nextFireOfRule(TriggerRule(nineAm), "r", now, zone, defaultTime)
        assertNotNull(bare)
        // ...and adding "y sólo si estoy en casa" must not change that: it is asked when the
        // alarm goes off, not now. An hours condition, which CAN be asked, still moves it.
        val placed = nextFireOfRule(TriggerRule(nineAm, listOf(home)), "r", now, zone, defaultTime)
        assertEquals(bare, placed)
        assertFalse(home.knownInAdvance)
        assertTrue(evening.knownInAdvance)
    }

    @Test
    fun `two circles that cannot both be true are a rule that can never ring`() {
        // Disjoint: 1.5 km apart with radii of 200 and 150.
        assertTrue(TriggerRule(nineAm, listOf(home, office)).placesConflict())
        // Overlapping: a kilometre-wide circle a kilometre away reaches home's 200 m.
        val quarter = Condition.AtPlace(homeLat + 1_000 / 111_195.0, homeLng, radiusM = 1_000, label = "Barrio")
        assertFalse(TriggerRule(nineAm, listOf(home, quarter)).placesConflict())
        // The same circle asked for and ruled out.
        assertTrue(TriggerRule(nineAm, listOf(home, home.copy(inside = false))).placesConflict())
        // A small circle that has to hold, entirely inside one that must not.
        assertTrue(TriggerRule(nineAm, listOf(home, home.copy(radiusM = 1_000, label = "Barrio", inside = false))).placesConflict())
        // Two places to avoid never conflict: the rest of the world is available.
        assertFalse(TriggerRule(nineAm, listOf(home.copy(inside = false), office.copy(inside = false))).placesConflict())
    }

    @Test
    fun `the trigger's own place counts as one of the circles`() {
        // "Al llegar a casa, y sólo si estoy en la oficina."
        assertTrue(TriggerRule(arriveHome, listOf(office)).placesConflict())
        assertFalse(TriggerRule(arriveHome, listOf(home)).placesConflict(), "arriving where you must be is fine")
        // Leaving home means being outside it, so "al salir de casa, y sólo si estoy en casa"
        // is the same contradiction the other way round.
        val leaveHome = arriveHome.copy(presence = Presence.OUTSIDE)
        assertTrue(TriggerRule(leaveHome, listOf(home)).placesConflict())
        assertFalse(TriggerRule(leaveHome, listOf(office)).placesConflict())
    }

    @Test
    fun `a rule whose hours never meet its moments is called out`() {
        val impossible = TriggerRule(nineAm, listOf(evening))
        assertNull(nextFireOfRule(impossible, "r", now, zone, defaultTime), "the search gives up, which is the finding")
        val said = warnings(listOf(impossible), now, zone, defaultTime)
        assertEquals(listOf(ValidationWarning.NeverFires(0)), said)
        // And the same rule with hours it can actually meet says nothing at all.
        assertTrue(warnings(listOf(TriggerRule(nineAm, listOf(Condition.TimeWindow(LocalTime.of(8, 0), LocalTime.of(10, 0))))), now, zone, defaultTime).isEmpty())
    }

    @Test
    fun `a moment in the past is still just a moment in the past`() {
        val gone = TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2020, 1, 1, 9, 0)))
        assertEquals(listOf(ValidationWarning.InPast(0)), warnings(listOf(gone), now, zone, defaultTime))
    }

    @Test
    fun `under all, one rule that cannot happen takes the whole reminder with it`() {
        val gone = TriggerRule(Trigger.AtDateTime(LocalDateTime.of(2020, 1, 1, 9, 0)))
        val rules = listOf(gone, TriggerRule(arriveHome))
        // Under ANY the dud is one dud: the place still rings it.
        assertEquals(listOf(ValidationWarning.InPast(0)), warnings(rules, now, zone, defaultTime, RuleMatch.ANY))
        // Under ALL the set never completes, so nothing ever rings.
        val strict = warnings(rules, now, zone, defaultTime, RuleMatch.ALL)
        assertTrue(ValidationWarning.InPast(0) in strict)
        assertTrue(ValidationWarning.NeverCompletes(0) in strict)
    }

    @Test
    fun `a place and a clock under all are probably meant as one conditioned rule`() {
        val rules = listOf(TriggerRule(arriveHome), TriggerRule(nineAm))
        assertEquals(
            listOf(ValidationWarning.BetterAsCondition(placeIndex = 0, clockIndex = 1)),
            warnings(rules, now, zone, defaultTime, RuleMatch.ALL),
        )
        // Not under ANY, where "either one" is exactly what it says.
        assertTrue(warnings(rules, now, zone, defaultTime, RuleMatch.ANY).isEmpty())
        // And not once somebody has met conditions: the advice is about discovering them.
        val conditioned = listOf(TriggerRule(arriveHome, listOf(evening)), TriggerRule(nineAm))
        assertTrue(warnings(conditioned, now, zone, defaultTime, RuleMatch.ALL).isEmpty())
    }

    @Test
    fun `a watched circle that fires nothing reports nothing`() {
        val silent = WatchedPlace("r1#0c0", homeLat, homeLng, 200, Transition.ENTER, "Casa", Crossing.NOTHING)
        val loud = silent.copy(id = "r1#0", crossing = Crossing.RINGS)
        val outside = PlaceWatchState(inside = mapOf(silent.id to false, loud.id to false))
        val step = stepPlaceWatch(outside, at(0.0), listOf(silent, loud), now)
        assertEquals(listOf(PlaceEvent(loud.id, Transition.ENTER)), step.events, "the silent one rang")
        assertEquals(true, step.state.inside[silent.id], "but its state is tracked all the same")
    }

    @Test
    fun `a place condition survives a round trip, and an old build simply drops it`() {
        val rules = listOf(TriggerRule(nineAm, listOf(home, evening)))
        assertEquals(rules, ReminderCodec.decodeRules(ReminderCodec.encodeRules(rules)))
    }

    // ---- what the watch remembers, which is what a firing should ask --------------------

    @Test
    fun `the watch is asked about a circle by its geometry, not by whose rule it is`() {
        // The same doorway is watched under a different id by every rule that names it, and by
        // both sides of it. They are all the same question about the same place, and a
        // condition carries the place without an id to look it up by.
        val home = Trigger.Location(40.4169, -3.7035, 50, Presence.INSIDE, "Casa")
        val leaving = home.copy(presence = Presence.OUTSIDE)
        val state = PlaceWatchState(
            inside = mapOf(
                GeofenceIds.encode("r1", 0, home) to false,
                GeofenceIds.encode("r2", 3, leaving) to false,
            ),
        )
        assertEquals(false, state.sideOf(40.4169, -3.7035, 50))
        // A circle nobody has judged has no answer, which is not the same as "outside".
        assertNull(state.sideOf(40.4169, -3.7035, 150), "a different circle is a different question")
        assertNull(PlaceWatchState().sideOf(40.4169, -3.7035, 50))
    }

    @Test
    fun `a fix sloppier than the circle cannot overrule what the watch saw`() {
        // The evening this is about. A fifty-metre circle is the tightest the app allows and is
        // smaller than an ordinary network fix is accurate, so measuring the fix against it
        // resolves to "yes" wherever the phone is — which is what rang a reminder at somebody
        // twenty minutes after their own geofences had said they had gone.
        val casa = Condition.AtPlace(40.4169, -3.7035, 50, "Casa", inside = true)
        val vague = Fix(40.4169, -3.7035, accuracyM = 80.0, at = now)
        assertTrue(casa.holdsAt(now, zone, vague), "measured against the fix, the fence is a no-op")

        val home = Trigger.Location(40.4169, -3.7035, 50, Presence.INSIDE, "Casa")
        val gone = PlaceWatchState(lastFix = vague, inside = mapOf(GeofenceIds.encode("r1", 0, home) to false))
        assertEquals(false, gone.sideOf(casa.lat, casa.lng, casa.radiusM), "the watch knew")
    }
}
