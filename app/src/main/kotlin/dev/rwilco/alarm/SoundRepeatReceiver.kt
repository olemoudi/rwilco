package dev.rwilco.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.rwilco.RwilcoApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/** Time to make the same noise again, if the reminder is still waiting to be dealt with. */
class SoundRepeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val played = intent.getIntExtra(EXTRA_PLAYED, 0)
        val rangAt = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_RANG_AT, 0L))
        val ruleIndex = ReminderScheduler.ruleIndexOf(intent)
        val app = context.applicationContext as RwilcoApplication
        val pending = goAsync()
        app.appScope.launch {
            try {
                withTimeoutOrNull(BUDGET_MS) { app.firing.playAgain(id, played, rangAt, ruleIndex) }
                    ?: Log.w(TAG, "the repeat for $id ran out of time")
            } catch (t: Throwable) {
                Log.e(TAG, "the repeat for $id failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "dev.rwilco.alarm.SOUND_AGAIN"
        const val EXTRA_ID = "id"
        const val EXTRA_PLAYED = "played"
        const val EXTRA_RANG_AT = "rang_at"
        private const val TAG = "RwilcoAlarms"
        private const val BUDGET_MS = 9_000L
    }
}
