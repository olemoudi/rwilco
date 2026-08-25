package dev.rwilco.geo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.rwilco.RwilcoApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The alarm the place watch set for itself: time to look where the phone is. */
class PlaceCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext as RwilcoApplication
        // goAsync buys ten seconds; the check reads a fix inside seven and the rest is a
        // database read and an alarm. Past the budget the receiver lets go rather than ANR.
        val pending = goAsync()
        app.appScope.launch {
            try {
                withTimeoutOrNull(BUDGET_MS) { app.placeWatcher.check() }
                    ?: Log.w(TAG, "place check ran out of time")
            } catch (t: Throwable) {
                Log.e(TAG, "place check failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "dev.rwilco.geo.CHECK"
        private const val TAG = "RwilcoGeo"
        private const val BUDGET_MS = 9_000L
    }
}
