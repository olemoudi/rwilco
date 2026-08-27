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
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
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
import java.time.Duration
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

    /**
     * A phone moved by mock location is teleported, and no accelerometer anywhere feels it — so
     * a real sensor here would talk the watch out of looking (`stepWithoutLooking`) and this
     * test would be testing the emulator's sensor hub. It says instead what it says in a process
     * that was killed between two checks: I was not listening. WHEN the watch looks is pinned on
     * the JVM; what this asks is what a real fused provider hands back.
     */
    private val silent = object : MotionSensor(context) {
        override fun consume(): Boolean? = null
    }
    private val watcher by lazy {
        PlaceWatcher(context, app.repository, app.firing, store, app.placeLog, app.settingsStore, app.clock, silent)
    }

    // Puerta del Sol, Madrid; the reminders' place.
    private val homeLat = 40.4169
    private val homeLng = -3.7035
    private val radius = 200

    /** The watch's key for the one place a seeded reminder carries. */
    private fun key(id: String, transition: Transition) =
        GeofenceIds.encode(id, 0, Trigger.Location(homeLat, homeLng, radius, transition, "Casa"))

    /** The watch's key for the place at [index] of a seeded reminder. */
    private fun keyAt(id: String, index: Int, transition: Transition) =
        GeofenceIds.encode(id, index, Trigger.Location(homeLat, homeLng, radius, transition, "Casa"))

    private fun conditionKey(id: String) =
        GeofenceIds.encodeCondition(id, 0, 0, Condition.AtPlace(homeLat, homeLng, radius, "Casa", inside = true))

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
        watcher.check()
        var state = store.read()
        assertEquals(mapOf(key(arriving, Transition.ENTER) to false, key(leaving, Transition.EXIT) to false), state.inside)
        assertNull(app.repository.get(arriving)!!.lastFiredAt)
        assertNotNull(state.nextCheckAt)
        assertFalse("GPS five kilometres out", state.precise)

        // Closing in fast: the next look is soon, but still no GPS at 1.3 km from the line.
        moveTo(south = 1_500.0, at = t0 + 120_000)
        watcher.check()
        state = store.read()
        assertFalse(state.precise)
        assertTrue(state.lastGapM!! in 1_200.0..1_400.0)

        // Three hundred metres out and moving: GPS for the last stretch.
        moveTo(south = 300.0, at = t0 + 240_000)
        watcher.check()
        state = store.read()
        assertTrue("GPS should be on ${state.lastGapM} m from the line", state.precise)
        assertNull(app.repository.get(arriving)!!.lastFiredAt)

        // Inside: the arriving rule rings, the leaving one does not.
        moveTo(south = 50.0, at = t0 + 300_000)
        watcher.check()
        state = store.read()
        assertEquals(true, state.inside[key(arriving, Transition.ENTER)])
        val rangAt = app.repository.get(arriving)!!.lastFiredAt
        assertNotNull("arriving should have rung", rangAt)
        assertNull(app.repository.get(leaving)!!.lastFiredAt)

        // The geofence reports the same arrival a moment later: an echo, dropped.
        app.firing.fire(arriving, ruleIndex = 0)
        assertEquals(rangAt, app.repository.get(arriving)!!.lastFiredAt)

        // Standing inside: nothing new, and the watch knows it is still.
        moveTo(south = 52.0, at = t0 + 360_000)
        watcher.check()
        state = store.read()
        assertEquals(1, state.stillStreak)
        assertEquals(rangAt, app.repository.get(arriving)!!.lastFiredAt)

        // Clearly out the other side: leaving rings, arriving is untouched.
        moveTo(south = -400.0, at = t0 + 420_000)
        watcher.check()
        state = store.read()
        assertEquals(false, state.inside[key(leaving, Transition.EXIT)])
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
        watcher.check()
        assertEquals(true, store.read().inside[key(arriving, Transition.ENTER)])
        assertNull("standing at home is not arriving", app.repository.get(arriving)!!.lastFiredAt)

        moveTo(south = 500.0, at = t0 + 120_000)
        watcher.check()
        assertEquals(false, store.read().inside[key(arriving, Transition.ENTER)])
        assertNull(app.repository.get(arriving)!!.lastFiredAt)

        moveTo(south = 40.0, at = t0 + 240_000)
        watcher.check()
        assertNotNull("coming back is arriving", app.repository.get(arriving)!!.lastFiredAt)
    }

    @Test
    fun aSloppyFixDoesNotGetYouIn() = runBlocking {
        val arriving = seed("arrive", Transition.ENTER)
        moveTo(south = 1_000.0, at = t0)
        watcher.check()
        // Centre inside, but the fix could be anywhere within 600 m: not an arrival.
        moveTo(south = 50.0, at = t0 + 120_000, accuracy = 600f)
        watcher.check()
        assertEquals(false, store.read().inside[key(arriving, Transition.ENTER)])
        assertNull(app.repository.get(arriving)!!.lastFiredAt)
        // A proper fix in the same spot is.
        moveTo(south = 50.0, at = t0 + 240_000, accuracy = 12f)
        watcher.check()
        assertNotNull(app.repository.get(arriving)!!.lastFiredAt)
    }

    @Test
    fun aPlaceOutsideItsHoursIsNotEvenLookedAt() = runBlocking {
        val now = app.clock.instant().atZone(app.clock.zone).toLocalTime()
        // A window that opens four hours out — past the two-hour run-up, so the gate is shut
        // for the whole test.
        val window = Condition.TimeWindow(now.plusHours(4).withSecond(0), now.plusHours(6).withSecond(0))
        val fenced = seed("fenced", Transition.ENTER, conditions = listOf(window))
        moveTo(south = 1_000.0, at = t0)
        watcher.check()
        moveTo(south = 50.0, at = t0 + 120_000)
        watcher.check()
        // Arriving cannot ring for two more hours, so the watch does not spend a fix finding
        // out that somebody arrived: the circle was never judged at all.
        assertNull("the watch read a position it could do nothing with", store.read().inside[key(fenced, Transition.ENTER)])
        assertNull("and nothing rang", app.repository.get(fenced)!!.lastFiredAt)
        // Left alone is not given up on: the next look is the hour the window opens.
        val next = store.read().nextCheckAt
        assertNotNull("the watch stopped instead of sleeping", next)
        assertTrue("woke for a window that has not opened: $next", next!! > Instant.now().plusSeconds(3_600))
    }

    @Test
    fun aPausedCircleRidesAlongOnTheFixAnotherOnePaidFor() = runBlocking {
        // The other half of aPlaceOutsideItsHoursIsNotEvenLookedAt. Shut out of the hours it
        // needs, "fenced" buys no fix of its own and never will until they come round — but
        // "live" is watching the same city and pays for one every few minutes, and judging one
        // more circle against a fix already in hand costs arithmetic. So it is told.
        val time = app.clock.instant().atZone(app.clock.zone).toLocalTime()
        val window = Condition.TimeWindow(time.plusHours(4).withSecond(0), time.plusHours(6).withSecond(0))
        val fenced = seed("fenced", Transition.ENTER, conditions = listOf(window))
        val live = seed("live", Transition.ENTER)
        val paused = key(fenced, Transition.ENTER)

        moveTo(south = 1_000.0, at = t0)
        watcher.check()
        assertEquals("the paused circle was not told where the phone was", false, store.read().inside[paused])

        // And it is told the arrival too. Told, and nothing else: it is not on the list a
        // crossing may be rung from, by either eye.
        moveTo(south = 50.0, at = t0 + 120_000)
        watcher.check()
        assertEquals(true, store.read().inside[paused])
        assertNull("a circle outside its hours rang", app.repository.get(fenced)!!.lastFiredAt)
        assertFalse("the fence rang a circle outside its hours", watcher.accept(paused, Transition.ENTER))
        assertNull(app.repository.get(fenced)!!.lastFiredAt)
        assertNotNull("the circle that paid for the fix should have rung", app.repository.get(live)!!.lastFiredAt)
    }

    @Test
    fun aCircleIsWatchedTheRunUpBeforeItsWindowOpens() = runBlocking {
        // The gate is not the stroke of the window: it is [PlaceWatchPolicy.WINDOW_LEAD] before
        // it. A watch that started at the stroke would spend its first fix on a baseline, and
        // somebody who walked in a minute later would not have arrived anywhere.
        val now = app.clock.instant().atZone(app.clock.zone).toLocalTime()
        val window = Condition.TimeWindow(now.plusHours(1).withSecond(0), now.plusHours(3).withSecond(0))
        val soon = seed("runup", Transition.ENTER, conditions = listOf(window))
        moveTo(south = 1_000.0, at = t0)
        watcher.check()
        assertEquals(
            "a window an hour out is inside the run-up and should already be watched",
            false,
            store.read().inside[key(soon, Transition.ENTER)],
        )
    }

    @Test
    fun aLookThatFindsNothingToWatchForgetsWhatItCannotVouchFor() = runBlocking {
        val now = app.clock.instant().atZone(app.clock.zone).toLocalTime()
        val window = Condition.TimeWindow(now.plusHours(4).withSecond(0), now.plusHours(6).withSecond(0))
        val fenced = seed("stale", Transition.ENTER, conditions = listOf(window))
        val id = key(fenced, Transition.ENTER)
        // Last night's answer, still in the store: the window closed hours ago and nothing has
        // looked since. Left standing, a card reads it as "no se cumple ahora mismo" — a
        // verdict on a circle nobody is watching.
        store.write(store.read().copy(inside = mapOf(id to false)))
        watcher.check()
        assertNull("a look kept an answer it had no business vouching for", store.read().inside[id])
    }

    @Test
    fun underAllACircleWaitsForTheRunUpOfTheSoonestSiblingMoment() = runBlocking {
        // The card that found this: "el 26 de cada mes, y cuando llegue a casa", read as
        // "todos". Both have to happen, so the moment is the earliest the set can ring — and
        // a circle watched from today to a moment six hours off buys nothing the geofence is
        // not already recording for free.
        val far = seedAllWithMoment("allfar", at = app.clock.instant().plus(Duration.ofHours(6)))
        moveTo(south = 1_000.0, at = t0)
        watcher.check()
        assertNull(
            "watched a circle whose set cannot ring for six hours",
            store.read().inside[keyAt(far, 1, Transition.ENTER)],
        )

        // Inside the run-up it is watched like any other circle.
        cancelWatchAlarm()
        val near = seedAllWithMoment("allnear", at = app.clock.instant().plus(Duration.ofMinutes(90)))
        moveTo(south = 1_000.0, at = t0 + 60_000)
        watcher.check()
        assertEquals(false, store.read().inside[keyAt(near, 1, Transition.ENTER)])
    }

    @Test
    fun underAllTheLastRulePendingIsWatchedWhateverTheHour() = runBlocking {
        // The moment has already happened this round, so nothing stands between this circle
        // and the ring: it is the one that completes the set, and it is watched now.
        val last = seedAllWithMoment("alllast", at = app.clock.instant().plus(Duration.ofHours(6)), fired = setOf(0))
        moveTo(south = 1_000.0, at = t0)
        watcher.check()
        assertEquals(false, store.read().inside[keyAt(last, 1, Transition.ENTER)])
    }

    @Test
    fun aCircleOnlyAskedAboutIsWatchedJustBeforeItsMoment() = runBlocking {
        // "A las X, y sólo si estoy en casa": the phone's position matters at X and at no
        // other time, so a circle two hours from being asked about is not judged at all, and
        // the next look is the lead before that moment.
        val later = app.clock.instant().plus(Duration.ofHours(2))
        val asked = seedClock("asked", at = later)
        moveTo(south = 1_000.0, at = t0)
        watcher.check()
        assertNull("judged a circle nobody is going to ask about for two hours", store.read().inside[conditionKey(asked)])
        val next = store.read().nextCheckAt
        assertNotNull("left alone is not given up on", next)
        val lead = Duration.between(next!!, later)
        assertTrue("the look should be the lead before the moment, not $lead", lead.toMinutes() in 4..6)

        // With the moment a few minutes off, the circle is watched and the answer is in hand.
        cancelWatchAlarm()
        val soonId = seedClock("soon", at = app.clock.instant().plus(Duration.ofMinutes(3)))
        moveTo(south = 1_000.0, at = t0 + 60_000)
        watcher.check()
        assertEquals(false, store.read().inside[conditionKey(soonId)])
    }

    @Test
    fun underTogetherAPlaceIsAskedAboutJustBeforeTheMomentAndTheMomentRings() = runBlocking {
        // "Al llegar a casa" y "a las 16:00", a la vez. Read as a state, the place has nothing
        // to be caught in the act of — arriving at noon and staying is being at home at four —
        // so it buys no run-up. What the set needs is where the phone is AT the moment, and one
        // look before it is the whole cost. Two hours out the circle is worth nothing.
        val far = app.clock.instant().plus(Duration.ofHours(2))
        val waiting = seedTogether("far", at = far)
        moveTo(south = 50.0, at = t0)
        watcher.check()
        assertNull("a circle only asked about at the moment was watched two hours early", store.read().inside[key(waiting, Transition.ENTER)])
        val next = store.read().nextCheckAt
        assertNotNull("left alone is not given up on", next)
        val lead = Duration.between(next!!, far)
        assertTrue("the look should be the lead before the moment, not $lead", lead.toMinutes() in 4..6)

        // Inside the lead it is watched like any other circle — and so, free of charge, is the
        // one still two hours from being asked, on the fix this one paid for.
        cancelWatchAlarm()
        val moment = app.clock.instant().plus(Duration.ofSeconds(25))
        val asking = seedTogether("soon", at = moment)
        moveTo(south = 50.0, at = t0 + 60_000)
        watcher.check()
        assertEquals(true, store.read().inside[key(asking, Transition.ENTER)])
        assertEquals("the circle two hours out rode along", true, store.read().inside[key(waiting, Transition.ENTER)])

        // And it is the MOMENT that rings, not the place: the alarm carries its rule index, the
        // set is folded into it as "y estoy en casa", and that is answered from the fix the
        // look above left behind. Nothing here rang the place.
        val deadline = System.currentTimeMillis() + 40_000
        while (app.repository.get(asking)!!.lastFiredAt == null && System.currentTimeMillis() < deadline) Thread.sleep(500)
        assertNotNull("at home when the moment came, and nothing rang", app.repository.get(asking)!!.lastFiredAt)
        assertNull("the circle two hours out rang", app.repository.get(waiting)!!.lastFiredAt)
    }

    @Test
    fun aPlaceThatHasRungIsOwedALeavingBeforeItRingsAgain() = runBlocking {
        // "Al llegar a casa, cada día": rang and was dealt with at home, two days ago. The
        // rest is long over, so the place is armed again — but arriving is something that
        // happens after leaving, and neither the geofence's word nor a fix inside is that.
        // At home FIRST, then the rule, as writtenWhileAtHome… does: a fence registered around
        // a phone already inside reports nothing, which is also what a real phone does.
        moveTo(south = 50.0, at = t0)
        val bins = "watch-bins"
        val twoDaysAgo = app.clock.instant().minus(Duration.ofDays(2)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
        val written = twoDaysAgo.minus(Duration.ofHours(1))
        app.repository.save(
            Reminder(
                id = bins,
                text = "Sacar la basura",
                rules = listOf(TriggerRule(Trigger.Location(homeLat, homeLng, radius, Transition.ENTER, "Casa"))),
                recurrence = Recurrence.After(1, RecurrenceUnit.DAYS),
                status = Status.ACTIVE,
                createdAt = written,
                updatedAt = written,
                lastFiredAt = twoDaysAgo,
                lastDealtAt = twoDaysAgo.plusSeconds(60),
            ),
        )
        Thread.sleep(1_500)
        cancelWatchAlarm()
        val key = key(bins, Transition.ENTER)
        watcher.check()
        assertEquals(true, store.read().inside[key])
        assertEquals("standing at home is not arriving", twoDaysAgo, app.repository.get(bins)!!.lastFiredAt)

        // Play Services re-reading the line the phone never left: not an arrival.
        assertFalse("still inside as far as the watch knows", watcher.accept(key, Transition.ENTER))
        // Nor is a fix inside.
        moveTo(south = 52.0, at = t0 + 60_000)
        watcher.check()
        assertEquals(twoDaysAgo, app.repository.get(bins)!!.lastFiredAt)

        // Seen outside — by the system's own word this time — and the next arrival rings.
        assertFalse("a leaving is written down, never rung", watcher.accept(key, Transition.EXIT))
        assertEquals(false, store.read().inside[key])
        assertTrue("back after a leaving is arriving", watcher.accept(key, Transition.ENTER))
        app.firing.fire(bins, ruleIndex = 0)
        assertTrue(app.repository.get(bins)!!.lastFiredAt!! > twoDaysAgo)

        // Dealt with again, just now: the place rests until tomorrow, keeps its memory, and a
        // crossing meanwhile is written down but does not ring.
        app.firing.dismiss(bins)
        Thread.sleep(1_500)
        cancelWatchAlarm()
        watcher.check()
        val resting = store.read()
        assertNotNull("resting is not forgotten", resting.inside[key])
        assertTrue("the next look is when the rest is up: ${resting.nextCheckAt}", resting.nextCheckAt!! > Instant.now().plusSeconds(3_600))
        assertFalse("a crossing during the rest does not ring", watcher.accept(key, Transition.EXIT).also { } || watcher.accept(key, Transition.ENTER))
        assertEquals(true, store.read().inside[key])
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
    private suspend fun seed(
        id: String,
        transition: Transition,
        conditions: List<Condition> = emptyList(),
        recurrence: Recurrence = Recurrence.None,
    ): String {
        val now = app.clock.instant()
        app.repository.save(
            Reminder(
                id = "watch-$id",
                text = "Place test $id",
                rules = listOf(TriggerRule(Trigger.Location(homeLat, homeLng, radius, transition, "Casa"), conditions)),
                recurrence = recurrence,
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

    /**
     * "El 26 de cada mes, y cuando llegue a casa": a set read as "todos" whose rule 0 is a
     * moment at [at] and whose rule 1 is the place. Returns its id.
     */
    private suspend fun seedAllWithMoment(id: String, at: Instant, fired: Set<Int> = emptySet()): String {
        val now = app.clock.instant()
        app.repository.save(
            Reminder(
                id = "watch-$id",
                text = "All test $id",
                rules = listOf(
                    TriggerRule(Trigger.AtDateTime(java.time.LocalDateTime.ofInstant(at, app.clock.zone))),
                    TriggerRule(Trigger.Location(homeLat, homeLng, radius, Transition.ENTER, "Casa")),
                ),
                ruleMatch = RuleMatch.ALL,
                firedRules = fired,
                status = Status.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )
        Thread.sleep(1_500)
        cancelWatchAlarm()
        return "watch-$id"
    }

    /**
     * "Al llegar a casa" y "a las [at]", a la vez: rule 0 is the place, rule 1 the moment.
     * The place folds to nothing (a moment cannot be a state), so it never rings on its own;
     * the moment folds the place in as "y estoy en casa". Returns its id.
     */
    private suspend fun seedTogether(id: String, at: Instant): String {
        val now = app.clock.instant()
        app.repository.save(
            Reminder(
                id = "watch-$id",
                text = "Together test $id",
                rules = listOf(
                    TriggerRule(Trigger.Location(homeLat, homeLng, radius, Transition.ENTER, "Casa")),
                    TriggerRule(Trigger.AtDateTime(java.time.LocalDateTime.ofInstant(at, app.clock.zone))),
                ),
                ruleMatch = RuleMatch.TOGETHER,
                status = Status.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )
        Thread.sleep(1_500)
        cancelWatchAlarm()
        return "watch-$id"
    }

    /** A clock rule at [at] that only asks whether the phone is at home; returns its id. */
    private suspend fun seedClock(id: String, at: Instant): String {
        val now = app.clock.instant()
        app.repository.save(
            Reminder(
                id = "watch-$id",
                text = "Clock test $id",
                rules = listOf(
                    TriggerRule(
                        Trigger.AtDateTime(java.time.LocalDateTime.ofInstant(at, app.clock.zone)),
                        listOf(Condition.AtPlace(homeLat, homeLng, radius, "Casa", inside = true)),
                    ),
                ),
                status = Status.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )
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
