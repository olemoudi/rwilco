package dev.rwilco.geo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import dev.rwilco.alarm.ReminderFiring
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.Fix
import dev.rwilco.model.Movement
import dev.rwilco.model.NoteKind
import dev.rwilco.model.PlaceWatchPolicy
import dev.rwilco.model.PlaceWatchState
import dev.rwilco.model.Status
import dev.rwilco.model.pendingRules
import dev.rwilco.model.place
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import dev.rwilco.model.WatchPlan
import dev.rwilco.model.WatchNote
import dev.rwilco.model.WatchedPlace
import dev.rwilco.model.blindRetry
import dev.rwilco.model.busyNotice
import dev.rwilco.model.crossingIsNews
import dev.rwilco.model.pollsSince
import dev.rwilco.model.remembering
import dev.rwilco.model.stepPlaceWatch
import dev.rwilco.model.stepWithoutLooking
import dev.rwilco.model.stirredWait
import dev.rwilco.notify.WatchNotices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * The app's own eye on its places, alongside the phone's geofences.
 *
 * The geofences are the net: free, always on, and the system's own word on where the phone
 * is. This is the second opinion, and the one that decides its own cost. However many places
 * are being waited on, there is ONE alarm, ONE fix and ONE decision: each check reads a single
 * fix, judges every place against it with hysteresis (`stepPlaceWatch`), fires the crossings
 * that match a rule, and sets the next alarm to the soonest look any one place asks for — from
 * two minutes walking up to a door to an afternoon for a place three provinces away, which no
 * road gets anybody to sooner.
 *
 * What it costs: a fix is wifi/cell unless the line is close and the phone moving, when it is
 * GPS for a few seconds; the alarm is allow-while-idle, which Doze holds to one per nine
 * minutes — and a phone in Doze is a phone that is not moving, so nothing is lost. At the
 * ceiling that is a GPS fix every two minutes for the minutes it takes to arrive, well under a
 * percent of a day's battery; at the floor, no fix at all — see `stepWithoutLooking`, which
 * spends the phone's own motion sensor ([MotionSensor], free) instead of its radios.
 *
 * A firing both the geofence and this watch see is fired once: [ReminderFiring] drops a place
 * firing that repeats within minutes.
 */
class PlaceWatcher(
    private val context: Context,
    private val repository: ReminderRepository,
    private val firing: ReminderFiring,
    private val store: PlaceWatchStore,
    private val log: PlaceLogStore,
    private val settings: SettingsStore,
    private val clock: Clock,
    /** The phone's own answer to "have you moved?", which costs nothing to ask. */
    private val motion: MotionSensor = MotionSensor(context),
) {

    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val fused: FusedLocationProviderClient by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val battery = BatteryGauge(context)

    /**
     * The look this process last set an alarm for, and how far the nearest line was when it did.
     * In memory rather than in the store because that is exactly where they are worth having:
     * the sensor only speaks for the process that armed it, so [stirred] and these two are valid
     * together or not at all, and reading them costs nothing on a sensor callback that must not
     * block.
     */
    @Volatile
    private var plannedAt: Instant? = null

    @Volatile
    private var plannedGapM: Double? = null

    init {
        motion.onMotion = ::stirred
    }

    /** What the Settings card shows: last fix, nearest line, next look. */
    val state = store.state

    /**
     * Every circle the open reminders need watched, keyed the way the geofences are.
     *
     * Two kinds, and the difference is whether they may ring. A rule's *trigger* is an event to
     * be caught, and only while the rule is still pending: under "todos" a rule whose moment
     * has already happened is ticked off in `firedRules` and watching its place again all week
     * is a fix an hour for an answer nobody is waiting for. A rule's *conditions* can name
     * circles too ("y sólo si estoy en casa"), and those are watched for their state alone —
     * `fires = false`, so `stepPlaceWatch` never turns one into a firing — because the answer
     * has to be in hand at the moment some other trigger goes off.
     */
    suspend fun places(): List<WatchedPlace> = repository.openNow()
        .filter { it.status == Status.ACTIVE }
        .flatMap { reminder ->
            val pending = reminder.pendingRules().toSet()
            reminder.rules.flatMapIndexed { index, rule ->
                val trigger = (rule.trigger as? Trigger.Location)
                    ?.takeIf { index in pending }
                    ?.let { place ->
                        WatchedPlace(GeofenceIds.encode(reminder.id, index), place.lat, place.lng, place.radiusM, place.transition, place.label)
                    }
                val asked = rule.conditions.mapIndexedNotNull { at, condition ->
                    condition.place?.let { place ->
                        WatchedPlace(
                            id = GeofenceIds.encodeCondition(reminder.id, index, at),
                            lat = place.lat,
                            lng = place.lng,
                            radiusM = place.radiusM,
                            // Waiting to be there reads as an arrival, waiting not to be as a
                            // leaving; it is the cadence that reads it, never a firing.
                            transition = if (place.inside) Transition.ENTER else Transition.EXIT,
                            label = place.label,
                            fires = false,
                        )
                    }
                }
                listOfNotNull(trigger) + asked
            }
        }

    /**
     * The list of places changed, or the app started: forget places that are gone, and look soon
     * if there is anything new to look at. A place added while standing inside it is baselined
     * by that look, not rung.
     *
     * "If" is the whole point. This runs on every process start, and the process starts every
     * time an alarm reaches an app the system had cleaned up — including the place watch's own
     * alarm. Looking soon unconditionally would mean a second fix five seconds after every
     * check, all day, for a list of places that had not changed since the last one. So the
     * pending look is left standing unless a place has never been judged (nothing in `inside`
     * knows it) or nothing is pending at all, which is also what a reboot looks like.
     */
    suspend fun sync() {
        val places = places()
        val current = store.read()
        if (places.isEmpty() || !context.hasBackgroundLocation()) {
            cancel()
            store.write(current.copy(inside = emptyMap(), nextCheckAt = null))
            return
        }
        val ids = places.mapTo(HashSet()) { it.id }
        val judged = current.inside.filterKeys { it in ids }
        val now = clock.instant()
        // Whichever comes first: what the store remembers, and what a stir already pulled
        // forward in this process (which the store will not have caught up with yet).
        val pending = listOfNotNull(current.nextCheckAt, plannedAt).filter { it > now }.minOrNull()
        val at = if (pending != null && ids.all { it in judged }) pending else now + SOON
        store.write(current.copy(inside = judged, nextCheckAt = at))
        scheduleAt(at)
    }

    /**
     * A crossing the phone's geofences report, judged against what this watch knows and written
     * down if it stands. False means the app's own last fix already had the phone on that side
     * of the line: Play Services re-reading a line nobody crossed, which is what makes a place
     * reminder ring at somebody who never left home.
     */
    suspend fun accept(placeId: String, transition: Transition): Boolean {
        val state = store.read()
        val now = clock.instant()
        val label = runCatching { places().firstOrNull { it.id == placeId }?.label }.getOrNull()
        if (!crossingIsNews(state, placeId, transition, now)) {
            Log.i(TAG, "geofence says $transition at $placeId, but we were already there")
            log.note(WatchNote(at = now, kind = NoteKind.ECHO, place = label ?: placeId, inside = state.inside[placeId]))
            return false
        }
        store.write(state.remembering(placeId, transition))
        log.note(WatchNote(at = now, kind = NoteKind.FENCE, place = label ?: placeId, inside = transition == Transition.ENTER))
        return true
    }

    /** One look: where is the phone, what did it cross, when to look again. Run by the alarm. */
    suspend fun check() {
        val places = places()
        if (places.isEmpty() || !context.hasBackgroundLocation()) {
            cancel()
            return
        }
        val before = store.read()
        val now = clock.instant()
        val charge = battery.remaining()
        // What the phone felt while nobody was looking; the next listening window starts here.
        val sensed = motion.consume()
        val rest = stepWithoutLooking(before, places, now, sensed, charge)
        val rested = rest?.plan
        if (rest != null && rested != null) {
            store.write(rest.state)
            Log.i(TAG, "nothing has moved; no fix taken, next look in ${rested.wait.toMinutes()} min")
            write(NoteKind.REST, now, rest.state, rested, rest.movement, charge)
            scheduleAt(now + rested.wait, rested.gapM)
            return
        }
        val fix = readFix(precise = before.precise)
        // A fix the watch would not vouch for is a fix it must not judge by.
        //
        // When nothing fresh answers — location switched off, the provider cold — the fallback
        // is whatever the phone had lying around, and that can be this morning's. Judging
        // places by it writes the wrong answer into `inside`, and then the geofence's own word
        // on a real arrival is thrown away as "we were already there" (crossingIsNews). The
        // bound is the same one crossingIsNews uses, because it is the same question.
        val stale = fix != null && Duration.between(fix.at, now) > PlaceWatchPolicy.SPEED_MEMORY
        if (fix == null || stale) {
            // Nothing to go on — location off, or nothing answered. Not a reason to give up,
            // but a reason to ask less often: a phone with location switched off will still
            // have it switched off in ten minutes, and in ten minutes after that.
            val wait = blindRetry(before.blindStreak, NO_FIX_RETRY)
            Log.w(TAG, "no fix worth having${if (stale) " (stale)" else ""}; trying again in ${wait.toMinutes()} min")
            val at = now + wait
            store.write(before.copy(nextCheckAt = at, blindStreak = before.blindStreak + 1))
            write(NoteKind.BLIND, now, before, plan = null, movement = Movement(sensed = sensed), charge = charge)
            scheduleAt(at)
            return
        }
        // A fix resets the blind streak: PlaceWatchState is rebuilt from scratch by the step.
        val step = stepPlaceWatch(before, fix, places, now, sensed, charge)
        store.write(step.state)
        for (event in step.events) {
            val reminderId = GeofenceIds.reminderIdOf(event.placeId)
            Log.i(TAG, "watch saw ${event.transition} at ${event.placeId}")
            firing.fire(reminderId, ruleIndex = GeofenceIds.triggerIndexOf(event.placeId))
        }
        val plan = step.plan
        if (plan == null) {
            cancel()
            return
        }
        Log.i(TAG, "${plan.gapM.toInt()} m from ${plan.nearest.label}; next look in ${plan.wait.toMinutes()} min${if (plan.precise) " (gps)" else ""}")
        write(NoteKind.FIX, now, step.state, plan, step.movement, charge)
        scheduleAt(now + plan.wait, plan.gapM)
    }

    /**
     * One line of the account, and the one thing the watch ever says about itself unprompted.
     *
     * The notice is off unless it has been asked for, and even then it is the log's own rule
     * that decides ([WatchLog.busyNotice]): more polls in the last hour than any cadence in the
     * policy should be able to produce, and nothing said about that hour already.
     */
    private suspend fun write(
        kind: NoteKind,
        now: Instant,
        state: PlaceWatchState,
        plan: WatchPlan?,
        movement: Movement,
        charge: Double?,
    ) {
        val written = log.note(
            WatchNote(
                at = now,
                kind = kind,
                waitS = plan?.wait?.seconds ?: state.nextCheckAt?.let { Duration.between(now, it).seconds },
                gapM = plan?.gapM,
                place = plan?.nearest?.label,
                inside = plan?.nearest?.let { state.inside[it.id] },
                speedMps = movement.speedMps,
                movedM = movement.movedM,
                sensed = movement.sensed,
                stillStreak = state.stillStreak,
                charge = charge?.let { (it * 100).roundToInt() },
                precise = plan?.precise ?: false,
            ),
        )
        if (!written.busyNotice(now)) return
        if (!settings.settings.first().busyWatchNotice) return
        WatchNotices.notifyBusy(context, written.notes.pollsSince(now - PlaceWatchPolicy.BUSY_WINDOW))
        log.noticed(now)
    }

    /**
     * The phone has gone somewhere, and the watch had settled on a long wait because it had not.
     *
     * This is what the half-hour rest inside a place with a "when I leave" rule costs, bought
     * back: the sensor fires as somebody actually walks out, and the look that was twenty-five
     * minutes away moves to [stirredWait] from now. It only ever moves a look *earlier*, only
     * when the nearest line is close enough for going somewhere to mean anything (deep inland of
     * everywhere, or three provinces from the only place being watched, a stir means nothing and
     * the plan stands), and the sensor's one-shot re-arming caps it at one early look per check.
     *
     * Runs off the sensor's delivery thread ([MotionSensor] hands it to a coroutine), so the
     * two binder calls and the log line it writes are nowhere near the main looper.
     */
    private suspend fun stirred() {
        val planned = plannedAt ?: return
        val gap = plannedGapM ?: return
        if (gap >= PlaceWatchPolicy.NEAR_M) return
        val now = clock.instant()
        val at = now + stirredWait(battery.remaining())
        if (planned <= at) return
        Log.i(TAG, "the phone stirred ${gap.toInt()} m from a line; looking sooner")
        scheduleAt(at, gap)
        // Its own line in the log, but not through `write`: moving an alarm is not a poll, and
        // counting it as one would have the watch complain about the thing that saves it work.
        log.note(WatchNote(at = now, kind = NoteKind.STIR, waitS = Duration.between(now, at).seconds, gapM = gap, sensed = true))
    }

    /**
     * One fix from the fused provider, GPS when [precise] and the cheaper blend otherwise, or
     * whatever it had lying around if nothing fresh comes in time. The receiver that runs
     * this has ten seconds in all, so the wait is short and the fallback is the point.
     */
    private suspend fun readFix(precise: Boolean): Fix? {
        val priority = if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val fresh = withTimeoutOrNull(FIX_TIMEOUT_MS) {
            val cancel = CancellationTokenSource()
            try {
                runCatching { fused.getCurrentLocation(priority, cancel.token).await() }
                    .onFailure { Log.w(TAG, "current location failed", it) }
                    .getOrNull()
            } finally {
                cancel.cancel()
            }
        }
        val location = fresh ?: runCatching { fused.lastLocation.await() }.getOrNull() ?: return null
        return location.toFix()
    }

    private fun Location.toFix() = Fix(
        lat = latitude,
        lng = longitude,
        // No accuracy is a fix from a provider that will not say: treat it as very rough.
        accuracyM = if (hasAccuracy()) accuracy.toDouble() else UNKNOWN_ACCURACY_M,
        at = Instant.ofEpochMilli(time),
    )

    private fun scheduleAt(at: Instant, gapM: Double? = null) {
        plannedAt = at
        // A schedule with no plan behind it (a sync, a blind retry) knows of no line to be near,
        // and a stir has nothing to judge itself against until the next real look.
        plannedGapM = gapM
        val intent = pendingIntent()
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
            }
        }.onFailure { Log.w(TAG, "could not set the next look", it) }
    }

    private fun cancel() {
        runCatching { alarms.cancel(pendingIntent()) }
        motion.stop()
        plannedAt = null
        plannedGapM = null
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, PlaceCheckReceiver::class.java).setAction(PlaceCheckReceiver.ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "RwilcoGeo"
        const val FIX_TIMEOUT_MS = 7_000L
        const val UNKNOWN_ACCURACY_M = 500.0
        val SOON: Duration = Duration.ofSeconds(5)
        val NO_FIX_RETRY: Duration = Duration.ofMinutes(10)
    }
}

/** A Play Services task as a suspension; failure is a null result, the caller logs it. */
private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resume(task.result) else continuation.resume(null)
    }
}
