package dev.rwilco.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.rwilco.RwilcoApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A reminder's moment arrived. Everything real happens in [ReminderFiring]. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = ReminderScheduler.reminderIdOf(intent) ?: return
        val ruleIndex = ReminderScheduler.ruleIndexOf(intent)
        val app = context.applicationContext as RwilcoApplication
        // goAsync: the work is a database read and a notification, and a receiver that returns
        // before them is a reminder that never rings.
        val pending = goAsync()
        app.appScope.launch {
            try {
                // Bounded under the broadcast's own budget: past it the system finishes the
                // receiver itself, and a finish() of our own on top of that throws.
                val done = withTimeoutOrNull(BUDGET_MS) { app.firing.fire(id, ruleIndex = ruleIndex) }
                if (done == null) Log.e("RwilcoAlarms", "firing $id ran out of time")
            } catch (t: Throwable) {
                Log.e("RwilcoAlarms", "firing $id failed", t)
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    private companion object {
        const val BUDGET_MS = 9_000L
    }
}
