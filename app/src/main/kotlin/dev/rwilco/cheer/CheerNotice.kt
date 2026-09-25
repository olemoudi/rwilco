package dev.rwilco.cheer

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
 * The silent word of encouragement: its own channel, low and silent, so it can be muted on its
 * own from the system's settings without touching a single alarm; tapping it opens Hechos, where
 * the numbers it is about live. One id, so a word nobody read is replaced rather than stacked.
 * Its channel does not start with `alert_`, so the readiness strip never counts it muted.
 */
object CheerNotice {

    private const val CHANNEL = "rwilco_cheers"
    // 43 the update's, 44 the vault's, 45 and 46 the watch's.
    private const val NOTIF_ID = 47

    fun post(context: Context, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.cheer_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.cheer_channel_description)
                setSound(null, null)
                enableVibration(false)
            },
        )
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_DONE)
        val tap = PendingIntent.getActivity(context, NOTIF_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notification) }
    }
}
