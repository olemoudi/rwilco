package dev.rwilco.alarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.RwilcoApplication
import dev.rwilco.data.FiringKind
import dev.rwilco.model.Crossing
import dev.rwilco.model.Fix
import dev.rwilco.model.GeofenceIds
import dev.rwilco.model.PlaceWatchState
import dev.rwilco.model.Presence
import dev.rwilco.model.Reminder
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.hereCircle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import androidx.test.rule.GrantPermissionRule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDateTime

/**
 * "Cuando llegue a casa" and "al salir de aquí", through the real doors: the row, the watch's
 * memory of which side of the line the phone starts on, the geofence's crossing accepted by the
 * watch, and the firing it turns into. Pure arithmetic is `SnoozeJourneyTest`'s; this is the
 * plumbing between `ReminderFiring`, `PlaceWatcher` and Room that only a device runs.
 */
@RunWith(AndroidJUnit4::class)
class SnoozePlaceFiringTest {

    /** "All the time": without it the watch prunes every memory on sync, and no place can be waited at. */
    @get:Rule
    val location: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    )

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RwilcoApplication
    private val id = "snooze-place"
    private val home = Trigger.Location(40.4169, -3.7035, 200, Presence.INSIDE, "Casa", onCrossing = true)

    /** A fix in the street, well outside home. */
    private fun outside() = Fix(40.4500, -3.6900, accuracyM = 20.0, at = app.clock.instant())

    @Before
    fun oneRingLetGo() = runBlocking {
        app.repository.deleteAll()
        app.placeWatch.write(PlaceWatchState())
        val now = app.clock.instant()
        val anHourAgo = now.minus(Duration.ofHours(1))
        // Rang an hour ago and nobody answered: the shape every snooze is an answer to.
        app.repository.save(
            Reminder(
                id = id,
                text = "Comprar filtros para la cafetera",
                rules = listOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.ofInstant(anHourAgo, app.clock.zone)))),
                createdAt = anHourAgo,
                updatedAt = anHourAgo,
                lastFiredAt = anHourAgo,
                armedFor = anHourAgo,
            ),
        )
    }

    @Test
    fun arrivingHomeRingsAReminderPutOffUntilHome() = runBlocking {
        val rangBefore = app.repository.get(id)!!.lastFiredAt!!
        app.firing.snoozeToPlace(id, home, outside(), app.placeWatcher::remember)

        val waiting = app.repository.get(id)!!
        assertEquals(home, waiting.snoozedToPlace)
        assertNull("a clock and a place are never both set", waiting.snoozedUntil)
        assertNull("nothing on the clock is armed while it waits", waiting.armedFor)
        val snoozeId = GeofenceIds.encodeSnooze(id, home)
        assertEquals("the watch starts from the side the fix says", false, app.placeWatch.read().inside[snoozeId])
        assertEquals("that circle is the only one the watch is asked to spend on", listOf(snoozeId), app.placeWatcher.places().map { it.id })
        assertEquals("arrive:Casa", app.repository.history(id, 5).first { it.kind == FiringKind.SNOOZED }.detail)

        // A clock alarm delivered late for the rule it used to be armed for is a stray.
        app.firing.fire(id, ruleIndex = 0)
        assertEquals("a stray alarm rang it", rangBefore, app.repository.get(id)!!.lastFiredAt)

        // Leaving is not the crossing it waits for; arriving is, and the geofence's word goes
        // through the watch first, as the receiver sends it.
        assertEquals(Crossing.NOTHING, app.placeWatcher.accept(snoozeId, Transition.EXIT))
        assertEquals(Crossing.RINGS, app.placeWatcher.accept(snoozeId, Transition.ENTER))
        app.firing.fire(id, viaSnoozePlace = true)

        val rang = app.repository.get(id)!!
        assertTrue("it did not ring on arrival", rang.lastFiredAt!! > rangBefore)
        assertNull("the place is spent by the ring", rang.snoozedToPlace)
        assertNull("no rule behind the ring", rang.lastFiredRule)
        assertEquals(FiringKind.RANG, app.repository.history(id, 5).first().kind)

        // A second crossing for a snooze no longer waiting is nothing.
        val after = rang.lastFiredAt
        app.firing.fire(id, viaSnoozePlace = true)
        assertEquals(after, app.repository.get(id)!!.lastFiredAt)
    }

    @Test
    fun leavingHereRingsAReminderPutOffUntilLeaving() = runBlocking {
        val fix = outside()
        val here = hereCircle(fix, "aquí")
        app.firing.snoozeToPlace(id, here, fix, app.placeWatcher::remember)
        val snoozeId = GeofenceIds.encodeSnooze(id, here)
        assertEquals("the circle was drawn around the phone, so it starts inside", true, app.placeWatch.read().inside[snoozeId])
        assertEquals("leave:aquí", app.repository.history(id, 5).first { it.kind == FiringKind.SNOOZED }.detail)

        assertEquals("still here", Crossing.NOTHING, app.placeWatcher.accept(snoozeId, Transition.ENTER))
        assertEquals(Crossing.RINGS, app.placeWatcher.accept(snoozeId, Transition.EXIT))
        app.firing.fire(id, viaSnoozePlace = true)
        val rang = app.repository.get(id)!!
        assertNotNull(rang.lastFiredAt)
        assertNull(rang.snoozedToPlace)
    }

    @Test
    fun aClockSnoozeGivenAfterwardsTakesThePlaceBack() = runBlocking {
        app.firing.snoozeToPlace(id, home, outside(), app.placeWatcher::remember)
        app.firing.snooze(id, dev.rwilco.model.Snooze.TEN_MINUTES)
        val row = app.repository.get(id)!!
        assertNull(row.snoozedToPlace)
        assertNotNull(row.snoozedUntil)
        assertEquals("the watch has nothing left to spend on", emptyList<String>(), app.placeWatcher.places().map { it.id })
    }
}
