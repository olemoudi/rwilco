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
import dev.rwilco.model.Fix
import dev.rwilco.model.PlaceWatchState
import dev.rwilco.model.Status
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import dev.rwilco.model.WatchedPlace
import dev.rwilco.model.crossingIsNews
import dev.rwilco.model.remembering
import dev.rwilco.model.stepPlaceWatch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.resume

/**
 * The app's own eye on its places, alongside the phone's geofences.
 *
 * The geofences are the net: free, always on, and the system's own word on where the phone
 * is. This is the second opinion, and the one that decides its own cost. Each check reads one
 * fix, judges every place with hysteresis (`stepPlaceWatch`), fires the crossings that match a
 * rule, and sets ONE alarm for the next look — as far off as the nearest line and the phone's
 * speed allow, from two minutes moving up to a door to an hour standing still across town.
 *
 * What it costs: a fix is wifi/cell unless the line is close and the phone moving, when it is
 * GPS for a few seconds; the alarm is allow-while-idle, which Doze holds to one per nine
 * minutes — and a phone in Doze is a phone that is not moving, so nothing is lost. At the
 * ceiling that is a GPS fix every two minutes for the minutes it takes to arrive, well under a
 * percent of a day's battery; at the floor, a wifi fix an hour.
 *
 * A firing both the geofence and this watch see is fired once: [ReminderFiring] drops a place
 * firing that repeats within minutes.
 */
class PlaceWatcher(
    private val context: Context,
    private val repository: ReminderRepository,
    private val firing: ReminderFiring,
    private val store: PlaceWatchStore,
    private val clock: Clock,
) {

    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val fused: FusedLocationProviderClient by lazy { LocationServices.getFusedLocationProviderClient(context) }

    /** What the Settings card shows: last fix, nearest line, next look. */
    val state = store.state

    /** The places the open reminders wait on, keyed the way the geofences are. */
    suspend fun places(): List<WatchedPlace> = repository.openNow()
        .filter { it.status == Status.ACTIVE }
        .flatMap { reminder ->
            reminder.rules.mapIndexedNotNull { index, rule ->
                (rule.trigger as? Trigger.Location)?.let { place ->
                    WatchedPlace(GeofenceIds.encode(reminder.id, index), place.lat, place.lng, place.radiusM, place.transition, place.label)
                }
            }
        }

    /**
     * The list of places changed, or the app started: forget places that are gone and look
     * soon. A place added while standing inside it is baselined by that look, not rung.
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
        val at = clock.instant() + SOON
        store.write(current.copy(inside = current.inside.filterKeys { it in ids }, nextCheckAt = at))
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
        if (!crossingIsNews(state, placeId, transition, clock.instant())) {
            Log.i(TAG, "geofence says $transition at $placeId, but we were already there")
            return false
        }
        store.write(state.remembering(placeId, transition))
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
        val fix = readFix(precise = before.precise)
        if (fix == null) {
            // Nothing to go on — location off, or nothing answered. Not a reason to give up.
            Log.w(TAG, "no fix; trying again in ${NO_FIX_RETRY.toMinutes()} min")
            val at = now + NO_FIX_RETRY
            store.write(before.copy(nextCheckAt = at))
            scheduleAt(at)
            return
        }
        val step = stepPlaceWatch(before, fix, places, now)
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
        scheduleAt(now + plan.wait)
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

    private fun scheduleAt(at: Instant) {
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
