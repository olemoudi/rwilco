package dev.rwilco.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.rwilco.MainActivity
import dev.rwilco.R
import java.time.Duration
import kotlin.math.roundToInt

/**
 * The one thing the place watch says about itself, and only when asked to.
 *
 * Off unless `AppSettings.busyWatchNotice` is on, low importance, silent, and tapping it goes to
 * the log the number came from rather than anywhere else. One stable id, so a second hour of the
 * same trouble replaces the first notice instead of stacking on it.
 */
object WatchNotices {

    private const val CHANNEL = "rwilco_watch"
    // 43 is the update's and 44 the vault's: three notices, three slots, or one replaces another.
    private const val NOTIF_ID = 45

    /** The other thing the watch says, and this one is not opt-in. See [notifyUnmeasured]. */
    private const val UNMEASURED_ID = 46

    fun notifyBusy(context: Context, polls: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.watch_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.watch_channel_description)
                setSound(null, null)
                enableVibration(false)
            },
        )
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_SETTINGS)
        val tap = PendingIntent.getActivity(
            context, NOTIF_ID, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.watch_busy_title))
            .setContentText(context.getString(R.string.watch_busy_text, polls))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.watch_busy_body, polls)))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notification) }
    }

    /**
     * A rate the watch could not measure, and so a reminder that did not ring.
     *
     * **On by default, unlike [notifyBusy], and that is the whole difference between them.** The
     * busy notice is a diagnostic somebody turns on to watch a suspicion; this is the app saying
     * a thing it was asked to do did not happen. The battery has the last word on how often the
     * watch may look ([dev.rwilco.model.batteryFloor]), and under it a ten-minute stay cannot be
     * timed at all — the looks come an hour apart, the vouched minutes never add up, and the
     * count gives up. Silence there is the one failure a person cannot see from any screen: the
     * reminder is still active, the place is still watched, and nothing is wrong.
     *
     * Its own id, so it does not replace the busy notice or get replaced by it, and silent and
     * low like everything else this object says: it is news about the past, not an alarm.
     */
    fun notifyUnmeasured(context: Context, place: String, dwell: Duration, charge: Double?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.watch_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.watch_channel_description)
                setSound(null, null)
                enableVibration(false)
            },
        )
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_SETTINGS)
        val tap = PendingIntent.getActivity(
            context, UNMEASURED_ID, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val minutes = dwell.toMinutes().toInt()
        // Without a battery reading there is nothing to blame it on, and guessing would be worse
        // than the shorter sentence.
        val body = when (charge) {
            null -> context.getString(R.string.watch_unmeasured_body, place, minutes)
            else -> context.getString(R.string.watch_unmeasured_body_battery, place, minutes, (charge * 100).roundToInt())
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.watch_unmeasured_title, place))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(UNMEASURED_ID, notification) }
    }
}
