package dev.rwilco.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.Instant

/**
 * The sound that comes back until somebody deals with the reminder.
 *
 * A chain of one-shot alarms, each carrying how many plays have gone out and which ring they
 * belong to. No column, no migration, nothing written down: a chain that lives in its own
 * alarms needs no memory, and a chain that is cancelled leaves none behind. A reboot loses it,
 * which is the honest behaviour — the alarms it would have been racing are gone too, and
 * `rearmAndCatchUp` speaks up about what was missed.
 *
 * Not exact alarms. A repeat is a nudge and Doze holding one back by a few minutes is Doze
 * being right: a phone that has been still and dark for an hour is a phone nobody is ignoring
 * a reminder on.
 */
class SoundRepeater(private val context: Context) {

    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** The next play of [reminderId]'s round, [played] having already gone out. */
    fun schedule(reminderId: String, played: Int, rangAt: Instant, at: Instant) {
        runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent(reminderId, played, rangAt))
        }.onFailure { Log.w(TAG, "could not line up the next play for $reminderId", it) }
    }

    /** Dealt with, snoozed, paused, gone: whatever the reason, the round is over. */
    fun cancel(reminderId: String) {
        runCatching { alarms.cancel(intent(reminderId, played = 0, rangAt = Instant.EPOCH)) }
    }

    /**
     * One PendingIntent per reminder, so cancelling needs only the id.
     *
     * The extras are deliberately outside the intent's identity (no data, no action difference),
     * which is what lets FLAG_UPDATE_CURRENT replace round three with round four rather than
     * leaving both armed — and what lets [cancel] match without knowing which round is pending.
     */
    private fun intent(reminderId: String, played: Int, rangAt: Instant): PendingIntent = PendingIntent.getBroadcast(
        context,
        reminderId.hashCode(),
        Intent(context, SoundRepeatReceiver::class.java)
            .setAction(SoundRepeatReceiver.ACTION)
            .putExtra(SoundRepeatReceiver.EXTRA_ID, reminderId)
            .putExtra(SoundRepeatReceiver.EXTRA_PLAYED, played)
            .putExtra(SoundRepeatReceiver.EXTRA_RANG_AT, rangAt.toEpochMilli()),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "RwilcoAlarms"
    }
}
