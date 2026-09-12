package dev.rwilco.vault

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.rwilco.MainActivity
import dev.rwilco.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The one thing the backup says out loud: that it has stopped and why. Low importance, silent,
 * one notification replaced rather than piled on — and taken down by the next upload that
 * goes through. It deep-links to the Backup screen, where the two choices a conflict needs are.
 */
object VaultNotifications {

    private const val CHANNEL = "rwilco_vault"
    private const val NOTIF_ID = 44

    fun notifyAttention(context: Context, outcome: VaultOutcome) {
        val (title, text) = when (outcome) {
            VaultOutcome.AUTH -> R.string.vault_notice_auth_title to R.string.vault_notice_auth_text
            VaultOutcome.REPO_MISSING -> R.string.vault_notice_repo_title to R.string.vault_notice_repo_text
            VaultOutcome.CONFLICT -> R.string.vault_notice_conflict_title to R.string.vault_notice_conflict_text
            else -> return
        }
        post(context, context.getString(title), context.getString(text))
    }

    /**
     * Nothing has gone through for a long time and changes are waiting — which no outcome says,
     * because the one this happens under (a network failure) is the one the vault retries in
     * silence. On the same id as the rest, so it replaces rather than piles on, and the next copy
     * that lands takes it down with everything else ([cancel]).
     *
     * [since] is when the remote last had this phone's data.
     */
    fun notifyStale(context: Context, since: Instant) {
        val stamp = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(since.atZone(ZoneId.systemDefault()))
        post(
            context,
            context.getString(R.string.vault_notice_stale_title),
            context.getString(R.string.vault_notice_stale_text, stamp),
        )
    }

    private fun post(context: Context, title: String, text: String) {
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_BACKUP)
        val tap = PendingIntent.getActivity(
            context, NOTIF_ID, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.vault_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, notification) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
    }
}
