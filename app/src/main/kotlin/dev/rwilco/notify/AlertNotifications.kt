package dev.rwilco.notify

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.rwilco.R
import dev.rwilco.alarm.AlertActionReceiver
import dev.rwilco.alarm.ReminderScheduler
import dev.rwilco.ui.alert.AlertActivity
import dev.rwilco.model.FiringPlan
import dev.rwilco.model.Reminder
import dev.rwilco.model.Snooze
import dev.rwilco.model.VibrationPattern
import dev.rwilco.model.notificationPattern
import java.time.Instant

/**
 * The notification a firing leaves behind, and the channels it needs.
 *
 * A channel's sound and vibration are fixed the moment it is created and can never be changed
 * afterwards, so there is one channel per combination the app can ask for rather than one
 * channel edited in place. The `_v1` in the ids is the way out if the sound ever has to change:
 * bump it and the new channels are created alongside.
 *
 * Which is also how the vibration setting reaches a notification: the chosen rhythm is part of
 * the id, so changing it means a different channel rather than an edit Android would ignore.
 * Only the rhythm — a channel's pattern is durations and nothing else, with no way to say how
 * hard, so gentle and strong are the same notification. The strength is honoured on the
 * full-screen alert, which drives the motor itself.
 */
object AlertNotifications {

    private const val GROUP = "alerts"
    private const val VERSION = "v1"
    const val CHANNEL_MISSED = "missed_$VERSION"

    fun ensureChannels(context: Context, vibration: VibrationPattern = VibrationPattern()) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP, context.getString(R.string.notif_group_alerts)),
        )
        for (sound in listOf(false, true)) {
            for (vibrate in listOf(false, true)) {
                manager.createNotificationChannel(alertChannel(context, sound, vibrate, vibration))
            }
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MISSED, context.getString(R.string.notif_channel_missed), NotificationManager.IMPORTANCE_DEFAULT).apply {
                group = GROUP
                description = context.getString(R.string.notif_channel_missed_description)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun post(
        context: Context,
        reminder: Reminder,
        plan: FiringPlan,
        late: Instant?,
        fullScreen: Boolean = plan.fullScreen,
        vibration: VibrationPattern = VibrationPattern(),
    ) {
        ensureChannels(context, vibration)
        val channel = if (late != null) CHANNEL_MISSED else channelId(plan.notificationSound, plan.notificationVibrate, vibration)
        val open = activityIntent(context, reminder.id)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (late != null) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, context.getString(R.string.alert_done), actionIntent(context, reminder.id, AlertActionReceiver.ACTION_DONE, null))
            .addAction(
                0,
                context.getString(R.string.snooze_ten_minutes),
                actionIntent(context, reminder.id, AlertActionReceiver.ACTION_SNOOZE, Snooze.TEN_MINUTES),
            )
            // Three is what a notification shows; the rest of the offers are on the alert screen,
            // which the banner opens.
            .addAction(
                0,
                context.getString(R.string.snooze_two_hours),
                actionIntent(context, reminder.id, AlertActionReceiver.ACTION_SNOOZE, Snooze.TWO_HOURS),
            )
        if (reminder.tags.isNotEmpty()) builder.setContentText(reminder.tags.joinToString(" · "))
        if (late != null) builder.setSubText(context.getString(R.string.alert_missed_subtext))
        // A full-screen alert is a request, not a promise: the system may refuse it (since
        // Android 14 it is for calls and alarms unless the person says otherwise), and then this
        // is simply a heads-up notification with the same buttons. [fullScreen] is the caller's
        // own decision on top of that — see AlertPresenter: an app open in front of somebody
        // gets the banner and nothing else.
        if (fullScreen && late == null) builder.setFullScreenIntent(open, true)
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(reminder.id), builder.build()) }
    }

    fun cancel(context: Context, reminderId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(notificationId(reminderId)) }
    }

    fun notificationId(reminderId: String): Int = reminderId.hashCode()

    private fun channelId(sound: Boolean, vibrate: Boolean, vibration: VibrationPattern): String {
        // The rhythm only belongs in the id of a channel that actually vibrates; a silent one
        // would otherwise get two ids for one behaviour.
        val rhythm = if (vibrate) "_${vibration.rhythm.name.first().lowercase()}" else ""
        return "alert_${VERSION}_s${if (sound) 1 else 0}_v${if (vibrate) 1 else 0}$rhythm"
    }

    private fun alertChannel(context: Context, sound: Boolean, vibrate: Boolean, vibration: VibrationPattern): NotificationChannel {
        val nameRes = when {
            sound && vibrate -> R.string.notif_channel_sound_vibrate
            sound -> R.string.notif_channel_sound
            vibrate -> R.string.notif_channel_vibrate
            else -> R.string.notif_channel_quiet
        }
        return NotificationChannel(channelId(sound, vibrate, vibration), context.getString(nameRes), NotificationManager.IMPORTANCE_HIGH).apply {
            group = GROUP
            if (sound) {
                // Alarm usage on purpose: somebody who ticked "Sonido" on a reminder means to
                // hear it, and a notification tone at notification volume is exactly what a
                // phone face-down on a table swallows.
                setSound(alarmSound(context), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            } else {
                setSound(null, null)
            }
            enableVibration(vibrate)
            if (vibrate) vibrationPattern = notificationPattern(vibration).toLongArray()
        }
    }

    private fun alarmSound(context: Context): Uri? =
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private fun activityIntent(context: Context, reminderId: String): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, AlertActivity::class.java)
            .setData(ReminderScheduler.reminderUri(reminderId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(context: Context, reminderId: String, action: String, snooze: Snooze?): PendingIntent {
        val intent = Intent(context, AlertActionReceiver::class.java)
            .setAction(action)
            // The action is part of what tells two PendingIntents apart; the data is what tells
            // two reminders apart. Both, or "Hecho" on one reminder finishes another.
            .setData(ReminderScheduler.reminderUri(reminderId))
        if (snooze != null) intent.putExtra(AlertActionReceiver.EXTRA_SNOOZE, snooze.name)
        return PendingIntent.getBroadcast(
            context,
            // Which snooze it is lives in an extra, and extras are NOT part of what makes two
            // PendingIntents the same — two snooze buttons on one notification would be one
            // PendingIntent, and FLAG_UPDATE_CURRENT would quietly make both of them the last
            // one built. The request code is part of the identity, so it carries the difference.
            snooze?.let { it.ordinal + 1 } ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
