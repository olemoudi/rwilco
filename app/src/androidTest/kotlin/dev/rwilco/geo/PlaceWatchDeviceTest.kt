package dev.rwilco.geo

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.location.Location
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Condition
import dev.rwilco.model.PlaceWatchState
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * The place watch on a real device, with the phone moved by hand: mock locations through the
 * fused provider, the watch asked to look after each move. What only a device can answer is
 * whether the fused provider hands the watch what it was given, whether a crossing goes all
 * the way through [dev.rwilco.alarm.ReminderFiring] to a ring, and whether the receiver the
 * alarm wakes does the same. Everything about WHEN it looks is pinned on the JVM.
 *
 * Needs a Google APIs image and the mock-location appop, which the test grants itself.
 */
@RunWith(AndroidJUnit4::class)
class PlaceWatchDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as RwilcoApplication
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val store by lazy { PlaceWatchStore(context) }

    // Puerta del Sol, Madrid; the reminders' place.
    private val homeLat = 40.4169
    private val homeLng = -3.7035
    private val radius = 200

    /** Fix times are synthetic and ordered, ten minutes back so they are never in the future. */
    private val t0 = System.currentTimeMillis() - 10 * 60_000L

    @Before
    fun grantAndReset() {
        val ui = instrumentation.uiAutomation
        for (permission in listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )) {
            runCatching { ui.grantRuntimePermission(context.packageName, permission) }
        }
        ui.executeShellCommand("appops set ${context.packageName} android:mock_location allow").close()
        runBlocking {
            app.repository.deleteAll()
            // The app's own start-up sync may have planned a look from whatever the last test
            // left in the database; with the table empty, a sync cancels it. Then a clean slate.
            Thread.sleep(1_000)
            app.placeWatcher.sync()
            cancelWatchAlarm()
            store.write(PlaceWatchState())
        }
        Tasks.await(fused.setMockMode(true), 10, TimeUnit.SECONDS)
        assertTrue("the test needs 'allow all the time'", context.hasBackgroundLocation())
    }

    @After
    fun tidy() {
        runCatching { Tasks.await(fused.setMockMode(false), 10, TimeUnit.SECONDS) }
        runBlocking { app.repository.deleteAll() }
        cancelWatchAlarm()
    }

    @Test
    fun arrivingIsSeenOnceLeavingIsSeenAndAnEchoIsDropped() = runBlocking {
        val arriving = seed("arrive", Transition.ENTER)
        val leaving = seed("leave", Transition.EXIT)

        // Far away: a baseline, no events, a look planned well ahead.
        moveTo(south = 5_000.0, at = t0)
        app.placeWatcher.check()
        var state = store.read()
        assertEquals(mapOf("$arriving#0" to false, "$leaving#0" to false), state.inside)
        assertNull(app.repository.get(arriving)!!.lastFiredAt)
        assertNotNull(state.nextCheckAt)
        assertFalse("GPS five kilometres out", state.precise)

        // Closing in fast: the next look is soon, but still no GPS at 1.3 km from the line.
        moveTo(south = 1_500.0, at = t0 + 120_000)
        app.placeWatcher.check()
        state = store.read()
        assertFalse(state.precise)
        assertTrue(state.lastGapM!! in 1_200.0..1_400.0)

        // Three hundred metres out and moving: GPS for the last stretch.
        moveTo(south = 300.0, at = t0 + 240_000)
        app.placeWatcher.check()
        state = store.read()
        assertTrue("GPS should be on ${state.lastGapM} m from the line", state.precise)
        assertNull(app.repository.get(arriving)!!.lastFiredAt)

        // Inside: the arriving rule rings, the leaving one does not.
        moveTo(south = 50.0, at = t0 + 300_000)
        app.placeWatcher.check()
        state = store.read()
        assertEquals(true, state.inside["$arriving#0"])
        val rangAt = app.repository.get(arriving)!!.lastFiredAt
        assertNotNull("arriving should have rung", rangAt)
        assertNull(app.repository.get(leaving)!!.lastFiredAt)

        // The geofence reports the same arrival a moment later: an echo, dropped.
        app.firing.fire(arriving, ruleIndex = 0)
        assertEquals(rangAt, app.repository.get(arriving)!!.lastFiredAt)

        // Standing inside: nothing new, and the watch knows it is still.
        moveTo(south = 52.0, at = t0 + 360_000)
        app.placeWatcher.check()
        state = store.read()
        assertEquals(1, state.stillStreak)
        assertEquals(rangAt, app.repository.get(arriving)!!.lastFiredAt)

        // Clearly out the other side: leaving rings, arriving is untouched.
        moveTo(south = -400.0, at = t0 + 420_000)
        app.placeWatcher.check()
        state = store.read()
        assertEquals(false, state.inside["$leaving#0"])
        assertNotNull("leaving should have rung", app.repository.get(leaving)!!.lastFiredAt)
        assertEquals(rangAt, app.repository.get(arriving)!!.lastFiredAt)
    }

    @Test
    fun writtenWhileAtHomeDoesNotRingUntilYouLeaveAndComeBack() = runBlocking {
        // Standing at home FIRST, then the rule: the phone's geofence is registered with the
        // phone already inside (no initial trigger), and the watch has no history to ring from.
        // The other way round the geofence would see a genuine move in — and be right to ring.
        moveTo(south = 40.0, at = t0)
        val arriving = seed("arrive", Transition.ENTER)
        app.placeWatcher.check()
        assertEquals(true, store.read().inside["$arriving#0"])
        assertNull("standing at home is not arriving", app.repository.get(arriving)!!.lastFiredAt)

        moveTo(south = 500.0, at = t0 + 120_000)
        app.placeWatcher.check()
        assertEquals(false, store.read().inside["$arriving#0"])
        assertNull(app.repository.get(arriving)!!.lastFiredAt)

        moveTo(south = 40.0, at = t0 + 240_000)
        app.placeWatcher.check()
        assertNotNull("coming back is arriving", app.repository.get(arriving)!!.lastFiredAt)
    }

    @Test
    fun aSloppyFixDoesNotGetYouIn() = runBlocking {
        val arriving = seed("arrive", Transition.ENTER)
        moveTo(south = 1_000.0, at = t0)
        app.placeWatcher.check()
        // Centre inside, but the fix could be anywhere within 600 m: not an arrival.
        moveTo(south = 50.0, at = t0 + 120_000, accuracy = 600f)
        app.placeWatcher.check()
        assertEquals(false, store.read().inside["$arriving#0"])
        assertNull(app.repository.get(arriving)!!.lastFiredAt)
        // A proper fix in the same spot is.
        moveTo(south = 50.0, at = t0 + 240_000, accuracy = 12f)
        app.placeWatcher.check()
        assertNotNull(app.repository.get(arriving)!!.lastFiredAt)
    }

    @Test
    fun aConditionOutsideItsHoursKeepsThePlaceQuiet() = runBlocking {
        val now = app.clock.instant().atZone(app.clock.zone).toLocalTime()
        // A two-hour window that starts two hours from now: never open during the test.
        val window = Condition.TimeWindow(now.plusHours(2).withSecond(0), now.plusHours(4).withSecond(0))
        val fenced = seed("fenced", Transition.ENTER, conditions = listOf(window))
        moveTo(south = 1_000.0, at = t0)
        app.placeWatcher.check()
        moveTo(south = 50.0, at = t0 + 120_000)
        app.placeWatcher.check()
        assertEquals("the watch saw the arrival", true, store.read().inside["$fenced#0"])
        assertNull("but the rule's hours said no", app.repository.get(fenced)!!.lastFiredAt)
    }

    @Test
    fun theAlarmsReceiverLooksToo() = runBlocking {
        seed("arrive", Transition.ENTER)
        moveTo(south = 3_000.0, at = t0)
        val before = store.read()
        context.sendBroadcast(Intent(context, PlaceCheckReceiver::class.java).setAction(PlaceCheckReceiver.ACTION))
        // A look leaves a fix behind and plans the next one; either is proof, both are asked for.
        val deadline = System.currentTimeMillis() + 20_000
        var after = store.read()
        while ((after.lastFix == null || after.nextCheckAt == before.nextCheckAt) && System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
            after = store.read()
        }
        assertNotNull("the receiver never looked", after.lastFix)
        assertTrue("the receiver looked but planned nothing", after.nextCheckAt != before.nextCheckAt)
    }

    @Test
    fun syncLooksSoonAndForgetsPlacesThatAreGone() = runBlocking {
        val id = seed("arrive", Transition.ENTER)
        app.placeWatcher.sync()
        val planned = store.read().nextCheckAt
        assertNotNull(planned)
        assertTrue("the first look should be moments away", planned!! <= Instant.now().plusSeconds(30))
        cancelWatchAlarm()
        app.repository.delete(id)
        app.placeWatcher.sync()
        val after = store.read()
        assertTrue(after.inside.isEmpty())
        assertNull(after.nextCheckAt)
    }

    /** One reminder with one place rule at home; returns its id. */
    private suspend fun seed(id: String, transition: Transition, conditions: List<Condition> = emptyList()): String {
        val now = app.clock.instant()
        app.repository.save(
            Reminder(
                id = "watch-$id",
                text = "Place test $id",
                rules = listOf(TriggerRule(Trigger.Location(homeLat, homeLng, radius, transition, "Casa"), conditions)),
                status = Status.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )
        // The app syncs the watch on every change and plans a look five seconds out; the test
        // wants to be the only one looking, so that alarm goes.
        Thread.sleep(1_500)
        cancelWatchAlarm()
        return "watch-$id"
    }

    /** The phone is now [south] metres south of home (north when negative). */
    private fun moveTo(south: Double, at: Long, accuracy: Float = 10f) {
        val location = Location("mock").apply {
            latitude = homeLat - south / 111_195.0
            longitude = homeLng
            this.accuracy = accuracy
            time = at
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        Tasks.await(fused.setMockLocation(location), 10, TimeUnit.SECONDS)
    }

    /** The same PendingIntent the watch arms, so cancelling it cancels the watch's alarm. */
    private fun cancelWatchAlarm() {
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, PlaceCheckReceiver::class.java).setAction(PlaceCheckReceiver.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        context.getSystemService(AlarmManager::class.java).cancel(intent)
    }
}
