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
}
