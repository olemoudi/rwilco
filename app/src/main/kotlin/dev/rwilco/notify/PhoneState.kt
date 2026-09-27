package dev.rwilco.notify

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioDeviceInfo
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
/** Whether the app may cross total silence at all — the grant behind [canGetThroughDnd]'s worst case. */
fun Context.hasNotificationPolicyAccess(): Boolean =
    getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

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

/**
 * Where a reminder's sound would go right now and how loud it can be there, for a line of the
 * report: "headset=bt music=y media=4/25 alarm=5/7" (0.161.0).
 *
 * The alarm slider alone never answered "it rang, but very quietly". With headphones connected
 * and something playing, Android holds an alarm played into them six decibels under the media
 * volume, whatever the alarm slider says (`AudioPolicyManager::computeVolume`, and for five
 * seconds after the music stops) — so a quiet ring is the four of these together, and a report
 * that had only the last of them read as a phone that should have been loud.
 */
fun Context.audioDescription(): String {
    val audio = getSystemService(AudioManager::class.java) ?: return "audio=?"
    val media = runCatching { "${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}" }.getOrDefault("?")
    return audioLine(AlertAudio.headset(this)?.type, runCatching { audio.isMusicActive }.getOrDefault(false), media, alarmVolumeDescription())
}

/** [audioDescription]'s words, apart from the phone that answers them. */
fun audioLine(headsetType: Int?, musicActive: Boolean, media: String, alarm: String): String {
    val headset = when (headsetType) {
        null -> "none"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt"
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing"
        else -> "type$headsetType"
    }
    return "headset=$headset music=${if (musicActive) "y" else "n"} media=$media alarm=$alarm"
}

/** A channel muted by hand is invisible to `areNotificationsEnabled`; this is the check it lacks. */
fun Context.anyAlertChannelMuted(): Boolean = mutedAlertChannelId() != null

/**
 * The muted alert channel, by id, so the fix can open that channel's own page rather than the
 * app's list. Every `alert_*` channel left is one this tone and rhythm ring — the others are
 * swept at [AlertNotifications.ensureChannels] — so any muted one is a real problem.
 */
fun Context.mutedAlertChannelId(): String? {
    val manager = getSystemService(NotificationManager::class.java) ?: return null
    return runCatching {
        manager.notificationChannels.firstOrNull { it.id.startsWith(AlertNotifications.ALERT_CHANNEL_PREFIX) && it.importance == NotificationManager.IMPORTANCE_NONE }?.id
    }.getOrNull()
}
