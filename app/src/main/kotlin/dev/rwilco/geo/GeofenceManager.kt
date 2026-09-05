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
import dev.rwilco.diag.Diag
import dev.rwilco.model.Presence
import dev.rwilco.model.dwell
import dev.rwilco.model.geofenceChoices
import dev.rwilco.model.geofenceFingerprint
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
 *
 * **But not on every process start any more.** Wholesale meant the fences were torn down and
 * put back every time this ran, and it runs from `Application.onCreate` — which the place
 * watch's own alarm reaches every few minutes to an hour on a phone that kills the process.
 * A crossing in the gap between the remove and the add is a crossing nobody saw. So a sync
 * first works out what it *would* register ([geofenceFingerprint]: the ids, which carry their
 * circles, and whether it is allowed to watch at all) and compares it with what it last did
 * ([GeofenceStore]); the same answer leaves Play Services alone. [force] is for the moments
 * the system's copy is known to be gone — a reboot, an update, a `GEOFENCE_NOT_AVAILABLE`,
 * the six-hourly net — all of which reach here through [dev.rwilco.alarm.RearmWorker].
 */
class GeofenceManager(
    private val context: Context,
    private val repository: ReminderRepository,
    private val store: GeofenceStore,
) {

    private val client: GeofencingClient by lazy { LocationServices.getGeofencingClient(context) }

    suspend fun sync(force: Boolean = false): GeofenceState {
        // Which circles deserve one of the hundred fences is arithmetic on the rules, and
        // lives with the rest of the arithmetic (geofenceChoices, core-model) where a JVM
        // test can hold it still. This side keeps the radios.
        val places = geofenceChoices(repository.openNow())

        val permitted = hasBackgroundLocation()
        val fingerprint = geofenceFingerprint(places.map { it.first }, permitted)
        if (!force && store.read() == fingerprint) {
            Diag.note("geo", "fences=${places.size} unchanged")
            return if (places.isEmpty() || permitted) GeofenceState.ARMED else GeofenceState.NO_PERMISSION
        }
        // Whatever happens below, the memory is of the *outcome*: written once the fences are
        // known to be in, cleared when they are not, so a refusal is asked again next time.
        store.write(null)
        if (!permitted) {
            removeAll()
            store.write(fingerprint)
            Diag.note("geo", "fences=${places.size} not permitted${if (force) " (forced)" else ""}")
            return if (places.isEmpty()) GeofenceState.ARMED else GeofenceState.NO_PERMISSION
        }
        val removed = removeAll()
        if (places.isEmpty()) {
            store.write(fingerprint)
            return GeofenceState.ARMED
        }

        val geofences = places.map { (id, place) ->
            // **A rate asks the system to time it too, and that costs nothing.** A doorway with
            // a rate on it ("al llegar a casa, y cuando lleve diez minutos allí") is counted by
            // the place watch out of its own positions, four of them, which is the second
            // opinion and the one that works in both directions. Play Services will time the
            // same wait for free, out of signals this app never sees, and report it as a DWELL —
            // so the fence asks for one, and whichever eye finishes first is the one that rings.
            // Only inwards: there is no such transition for staying *away* from a circle, and
            // that half is the watch's alone.
            val rate = place.dwell?.takeIf { place.presence == Presence.INSIDE }
            Geofence.Builder()
                .setRequestId(id)
                .setCircularRegion(place.lat, place.lng, place.radiusM.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                // Both crossings, whichever the rule waits for: the other one is what tells the
                // place watch the phone has been on the far side of the line, which is what a
                // place that has already rung is owed before it may ring again — and the
                // system sees a leaving the watch's own hourly look would miss. The receiver
                // only rings the one the rule asked for; the other is written down.
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT or
                        (if (rate != null) Geofence.GEOFENCE_TRANSITION_DWELL else 0),
                )
                // A place reminder that fires the instant a GPS fix wobbles across the line is
                // worse than one that fires half a minute late, and the responsiveness is what
                // buys that: Play Services is allowed to take a minute to be sure.
                //
                // The loitering delay only applies to a DWELL transition. On a fence that asks
                // for one it is the rate itself — which is why the rate is in the id
                // ([GeofenceIds]): change the rate and this is a different fence, registered
                // afresh, rather than one still timing the old wait.
                .setLoiteringDelay(rate?.toMillis()?.toInt() ?: LOITERING_MS)
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
        val state = withTimeoutOrNull(REGISTER_TIMEOUT_MS) {
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
        // Registered on top of a remove nobody answered is not "registered": the remove can
        // still land late and take these fences with it, and a fingerprint written over that
        // is six blind hours. The memory stays empty instead, and the next sync from any door
        // does the whole thing again.
        if (state == GeofenceState.ARMED && removed) store.write(fingerprint)
        if (state == GeofenceState.ARMED && !removed) Diag.note("geo", "remove unanswered; fences up but not trusted")
        Diag.note("geo", "fences=${places.size} ${state.name.lowercase()}${if (force) " (forced)" else ""}")
        return state
    }

    /**
     * Awaited, because the add that follows registers on the same PendingIntent: a remove that
     * completed after it would take every fence just registered with it, and nothing would ring
     * until the next sync. Bounded like the add, so a Play Services that never answers cannot
     * hold the chain behind it — and whether it answered inside the bound is handed back, so
     * the caller knows when the fences it is about to add cannot be trusted to stay.
     */
    private suspend fun removeAll(): Boolean =
        withTimeoutOrNull(REMOVE_TIMEOUT_MS) {
            runCatching { client.removeGeofences(pendingIntent()).await() }
        } != null

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
        const val LOITERING_MS = 30_000
        const val RESPONSIVENESS_MS = 60_000
        const val REGISTER_TIMEOUT_MS = 15_000L
        const val REMOVE_TIMEOUT_MS = 5_000L
    }
}
