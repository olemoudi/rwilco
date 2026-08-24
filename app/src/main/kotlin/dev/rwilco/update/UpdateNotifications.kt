package dev.rwilco.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.rwilco.MainActivity
import dev.rwilco.R

/** "Update ready — tap to install" prompt for when the system wants confirmation. */
object UpdateNotifications {

    /** Low importance: an update prompt is never urgent enough to buzz. */
    private const val CHANNEL = "rwilco_updates"

    // One id for the whole conversation: "ready", then "you cancelled, here's the way back".
    // They are the same subject, so the second must replace the first rather than pile on it.
    private const val NOTIF_ID = 43

    /** MainActivity opens Settings when launched with this extra set to [DEST_SETTINGS]. */
    const val EXTRA_DEST = "dest"
    const val DEST_SETTINGS = "settings"

    fun notifyConfirmationNeeded(context: Context, confirmIntent: Intent) {
        val tap = PendingIntent.getActivity(
            context, 0, confirmIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(
            context,
            title = context.getString(R.string.update_ready_title),
            text = context.getString(R.string.update_ready_text),
            tap = tap,
        )
    }

    /**
     * Posted when the install was declined — the "Cancel" that gets tapped by reflex. Deep-links
     * into the app's own update settings rather than back into the system dialog, because the
     * session that dialog belonged to is gone: what survives is the downloaded APK, and the
     * button there installs it with no network at all.
     */
    fun notifyInstallDeclined(context: Context) {
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_DEST, DEST_SETTINGS)
        val tap = PendingIntent.getActivity(
            context, NOTIF_ID, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(
            context,
            title = context.getString(R.string.update_declined_title),
            text = context.getString(R.string.update_declined_text),
            tap = tap,
        )
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
    }

    private fun post(context: Context, title: String, text: String, tap: PendingIntent?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            // Ongoing, and this is the one notification that earns it: a swipe is how an update
            // gets lost for good. It goes away when the install reaches a terminal state.
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notification) }
    }
}
