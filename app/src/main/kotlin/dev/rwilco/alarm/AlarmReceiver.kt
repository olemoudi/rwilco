package dev.rwilco.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.rwilco.RwilcoApplication
import kotlinx.coroutines.launch

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
                app.firing.fire(id, ruleIndex = ruleIndex)
            } catch (t: Throwable) {
                Log.e("RwilcoAlarms", "firing $id failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
