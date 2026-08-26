package dev.rwilco.notify

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager

/**
 * The handful of questions "will a reminder actually reach somebody" comes down to, asked of
 * the phone. They live together because two places need them — the Settings card that offers
 * to fix each one, and the diagnostics report that writes them all down — and because a phone
 * that answers "no" to any of them is a phone where this app fails silently.
 */

fun Context.canScheduleExactAlarms(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false

fun Context.ignoresBatteryOptimisations(): Boolean =
    getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

fun Context.isBackgroundRestricted(): Boolean =
    getSystemService(ActivityManager::class.java)?.isBackgroundRestricted ?: false

/**
 * Do Not Disturb lets alarms through unless it is on total silence, and the alerts are alarms
 * to it (see [AlertNotifications]). Total silence is the one mode only policy access gets past —
 * and it is the mode people put on for the night, which is when a morning timer matters.
 */
fun Context.canGetThroughDnd(): Boolean {
    val manager = getSystemService(NotificationManager::class.java) ?: return true
    return manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_NONE || manager.isNotificationPolicyAccessGranted
}

/** What Do Not Disturb is set to, and whether this app may cross it. For the report. */
fun Context.dndDescription(): String {
    val manager = getSystemService(NotificationManager::class.java) ?: return "?"
    val filter = when (manager.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "TOTAL_SILENCE"
        else -> "?"
    }
    return "$filter/policyAccess=${if (manager.isNotificationPolicyAccessGranted) "y" else "n"}"
}

/** Every alert plays on the alarm stream, so this slider is the only one that can mute them. */
fun Context.alarmVolumeIsUp(): Boolean {
    val audio = getSystemService(AudioManager::class.java) ?: return true
    return runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) > audio.getStreamMinVolume(AudioManager.STREAM_ALARM) }.getOrDefault(true)
}

/** "7/15", for the report: a number says more than "up" when somebody says it is too quiet. */
fun Context.alarmVolumeDescription(): String {
    val audio = getSystemService(AudioManager::class.java) ?: return "?"
    return runCatching { "${audio.getStreamVolume(AudioManager.STREAM_ALARM)}/${audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)}" }.getOrDefault("?")
}

/** A channel muted by hand is invisible to `areNotificationsEnabled`; this is the check it lacks. */
fun Context.anyAlertChannelMuted(): Boolean {
    val manager = getSystemService(NotificationManager::class.java) ?: return false
    return runCatching {
        manager.notificationChannels.any { it.id.startsWith(AlertNotifications.ALERT_CHANNEL_PREFIX) && it.importance == NotificationManager.IMPORTANCE_NONE }
    }.getOrDefault(false)
}
