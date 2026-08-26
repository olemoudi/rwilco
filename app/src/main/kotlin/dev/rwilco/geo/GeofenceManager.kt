package dev.rwilco.geo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dev.rwilco.data.ReminderRepository
import dev.rwilco.model.Status
import dev.rwilco.model.pendingRules
import dev.rwilco.model.recurrenceInCharge
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** What came of trying to register the places; the Settings card turns this into a sentence. */
enum class GeofenceState {
    /** Registered, or there was nothing to register. */
    ARMED,

    /** "Allow all the time" has not been given, so a place cannot be watched in the background. */
    NO_PERMISSION,

    /** Location is off on the phone, or Play Services refused. */
    UNAVAILABLE,
}

/**
 * The places the phone watches for. Registration is wholesale — remove everything, add what
 * should be there — because the alternative is diffing against a list Play Services will not
 * show us, and geofences are cheap to re-add. The system drops them all on a reboot and on a
 * Play Services update, so [sync] runs from the same places the alarms are re-armed from.
 */
class GeofenceManager(
    private val context: Context,
    private val repository: ReminderRepository,
) {

    private val client: GeofencingClient by lazy { LocationServices.getGeofencingClient(context) }

    suspend fun sync(): GeofenceState {
        val places = repository.openNow()
            // Once a recurrence is in charge the rules are not asked again, a place among them.
            .filter { it.status == Status.ACTIVE && !it.recurrenceInCharge }
            .flatMap { reminder ->
                // Only the rules still waiting to happen. Under "todos" a place that has
                // already been ticked off has nothing left to report, and a geofence is not
                // free: a hundred is the app's whole allowance and Play Services watches every
                // one of them. The circles named by *conditions* are not here at all — a
                // geofence reports a crossing and a condition has none; PlaceWatcher tracks
                // their state instead.
                val pending = reminder.pendingRules().toSet()
                reminder.rules.mapIndexedNotNull { index, rule ->
                    if (index !in pending) return@mapIndexedNotNull null
                    (rule.trigger as? Trigger.Location)?.let { GeofenceIds.encode(reminder.id, index, it) to it }
                }
            }
            // A hard limit of 100 per app: past that Play Services refuses the whole batch, so
            // the newest places are the ones that get watched rather than nothing at all.
            .takeLast(MAX_GEOFENCES)

        if (!hasBackgroundLocation()) {
            removeAll()
            return if (places.isEmpty()) GeofenceState.ARMED else GeofenceState.NO_PERMISSION
        }
        removeAll()
        if (places.isEmpty()) return GeofenceState.ARMED

        val geofences = places.map { (id, place) ->
            Geofence.Builder()
                .setRequestId(id)
                .setCircularRegion(place.lat, place.lng, place.radiusM.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                    if (place.transition == Transition.ENTER) Geofence.GEOFENCE_TRANSITION_ENTER else Geofence.GEOFENCE_TRANSITION_EXIT,
                )
                // A place reminder that fires the instant a GPS fix wobbles across the line is
                // worse than one that fires half a minute late, and the responsiveness is what
                // buys that: Play Services is allowed to take a minute to be sure. (The
                // loitering delay below only ever applies to a DWELL transition, which these
                // fences do not ask for; it is set so that adding DWELL later cannot throw.)
                .setLoiteringDelay(LOITERING_MS)
                .setNotificationResponsiveness(RESPONSIVENESS_MS)
                .build()
        }
        val request = GeofencingRequest.Builder()
            // No INITIAL_TRIGGER: being already at home when the reminder is created is not
            // arriving home, and firing on registration is how a place reminder rings at the
            // moment you write it.
            .setInitialTrigger(0)
            .addGeofences(geofences)
            .build()
        // Bounded: this sits in the chain every process start runs (re-arm, geofences, place
        // watch), and a Play Services that never answers would otherwise hold the place watch's
        // own sync behind it for ever.
        return withTimeoutOrNull(REGISTER_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                runCatching {
                    client.addGeofences(request, pendingIntent())
                        .addOnSuccessListener {
                            Log.i(TAG, "watching ${geofences.size} places")
                            if (continuation.isActive) continuation.resume(GeofenceState.ARMED)
                        }
                        .addOnFailureListener { error ->
                            Log.w(TAG, "could not watch places", error)
                            if (continuation.isActive) continuation.resume(GeofenceState.UNAVAILABLE)
                        }
                }.onFailure {
                    Log.w(TAG, "geofencing unavailable", it)
                    if (continuation.isActive) continuation.resume(GeofenceState.UNAVAILABLE)
                }
            }
        } ?: GeofenceState.UNAVAILABLE.also { Log.w(TAG, "Play Services did not answer in time") }
    }

    private fun removeAll() {
        runCatching { client.removeGeofences(pendingIntent()) }
    }

    /** Background location is what makes a place reminder work when the app is not open. */
    fun hasBackgroundLocation(): Boolean = context.hasBackgroundLocation()

    /** Mutable on purpose: Play Services fills the transition into this intent. */
    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, GeofenceReceiver::class.java).setAction(GeofenceReceiver.ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private companion object {
        const val TAG = "RwilcoGeo"
        const val MAX_GEOFENCES = 100
        const val LOITERING_MS = 30_000
        const val RESPONSIVENESS_MS = 60_000
        const val REGISTER_TIMEOUT_MS = 15_000L
    }
}
