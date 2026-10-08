package dev.rwilco.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.model.Action
import dev.rwilco.model.LATE_IS_MISSED
import dev.rwilco.model.firingPlan
import dev.rwilco.ui.alert.LockedAlertActivity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/*
 * Ringing before the first unlock (0.172.0).
 *
 * A reboot clears every alarm, and the one that re-arms them — BOOT_COMPLETED — only arrives once
 * somebody has unlocked the phone: until then the database is in storage the phone has not
 * decrypted. A phone that restarted at three in the morning (a crash, a flat battery, the security
 * restart after days locked) said nothing at seven, and at twenty to eight, unlocked, the seven
 * o'clock reminder arrived as a quiet "did not ring on time" card.
 *
 * So the next moment of every reminder is mirrored where the phone can read it locked — the moment
 * and nothing else, never the words — and a locked boot arms those. One that comes round before the
 * unlock rings with a generic "you have a reminder" (the owner's call, 2026-10-08: nothing of the
 * reminder is shown over the lock screen), and once the phone is unlocked it becomes the reminder it
 * was, quietly, rather than a missed one.
 */

/** One reminder's next armed moment, as a locked phone can see it. No words. */
@Serializable
data class LockedWake(val id: String, val at: Long, val rule: Int? = null, val loud: Boolean = true)

/**
 * The mirror: the next moments ([wakes]), the ones already announced locked ([announced]), and
 * whether a locked boot armed anything ([armedLocked]) — what tells the unlock there is tidying up
 * to do, so every other pass costs one small read.
 */
@Serializable
data class LockedMirrorState(
    val wakes: List<LockedWake> = emptyList(),
    val announced: List<LockedWake> = emptyList(),
    val armedLocked: Boolean = false,
)

/** What a locked boot does with the mirror: the alarms to arm, and the moments to announce at once. */
data class LockedPlan(val arm: List<LockedWake>, val announceNow: List<LockedWake>)

/**
 * Which mirrored moments a locked boot arms and which it announces straight away. Only the loud
 * ones — a moment that would not have made a sound is not worth a generic one. Ahead of [now], an
 * alarm; passed while the phone was restarting, by less than the moment a late ring stops being
 * live ([LATE_IS_MISSED]), at once; older than that, nothing: the catch-up after the unlock says it.
 * A moment already announced is never announced again.
 */
fun lockedPlan(state: LockedMirrorState, now: Instant): LockedPlan {
    val announced = state.announced.mapTo(HashSet()) { it.id to it.at }
    val open = state.wakes.filter { it.loud && (it.id to it.at) !in announced }
    val (ahead, passed) = open.partition { it.at > now.toEpochMilli() }
    return LockedPlan(arm = ahead, announceNow = passed.filter { now.toEpochMilli() - it.at <= LATE_IS_MISSED.toMillis() })
}

/** The mirror's file, in device-protected storage: the one place a locked phone can read. */
object LockedMirror {
    private const val FILE = "locked-wakes.json"
    private const val TAG = "RwilcoLocked"
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(context: Context): File = File(context.createDeviceProtectedStorageContext().filesDir, FILE)

    @Synchronized
    fun read(context: Context): LockedMirrorState = runCatching {
        val file = file(context)
        if (!file.isFile) LockedMirrorState() else json.decodeFromString(LockedMirrorState.serializer(), file.readText())
    }.getOrDefault(LockedMirrorState())

    /** Written whole and then moved into place, so a process dying half-way leaves the last one. */
    @Synchronized
    fun update(context: Context, transform: (LockedMirrorState) -> LockedMirrorState) {
        val before = read(context)
        val after = transform(before)
        if (after == before) return
        runCatching {
            val file = file(context)
            file.parentFile?.mkdirs()
            val next = File(file.parentFile, "$FILE.next")
            next.writeText(json.encodeToString(LockedMirrorState.serializer(), after))
            check(next.renameTo(file)) { "could not move the mirror into place" }
        }.onFailure { Log.w(TAG, "could not write the locked-boot mirror", it) }
    }
}

fun Context.isUserUnlocked(): Boolean = getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

/** The phone has booted and nobody has unlocked it yet: arm the mirrored moments. */
class LockedBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        // Unlocked already (no PIN, or quick fingers): BOOT_COMPLETED re-arms everything for real.
        if (context.isUserUnlocked()) return
        val plan = lockedPlan(LockedMirror.read(context), Instant.now())
        Log.i(TAG, "locked boot: arming ${plan.arm.size}, announcing ${plan.announceNow.size}")
        if (plan.arm.isNotEmpty()) LockedMirror.update(context) { it.copy(armedLocked = true) }
        for (wake in plan.announceNow) LockedAlerts.announce(context, wake)
        for (wake in plan.arm) LockedAlerts.arm(context, wake)
    }

    private companion object {
        const val TAG = "RwilcoLocked"
    }
}

/**
 * Whether a reminder is said aloud before the first unlock: only one that makes a sound unlocked.
 * The locked channel has one tone, the alarm's, so a full screen asked for in silence was the
 * alarm at alarm volume — and the generic word without a sound is a card nobody would see before
 * the real one replaces it at the unlock.
 */
fun soundsWhileLocked(actions: Set<Action>): Boolean = firingPlan(actions).sound

/** A mirrored moment came round. Still locked, it rings generically; unlocked, the real alarm has it. */
class LockedAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.isUserUnlocked()) return
        val id = intent.data?.lastPathSegment ?: return
        val at = intent.getLongExtra(LockedAlerts.EXTRA_AT, -1L).takeIf { it >= 0 } ?: return
        val wake = LockedMirror.read(context).wakes.firstOrNull { it.id == id && it.at == at } ?: return
        LockedAlerts.announce(context, wake)
    }
}

/** The generic word and its alarm: everything here runs with the phone locked. */
object LockedAlerts {
    const val EXTRA_AT = "at"
    private const val CHANNEL = "locked_v1"
    private const val TAG = "RwilcoLocked"

    private fun uri(id: String) = "rwilco://locked/$id".toUri()

    private fun alarmIntent(context: Context, wake: LockedWake): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, LockedAlarmReceiver::class.java).setData(uri(wake.id)).putExtra(EXTRA_AT, wake.at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun arm(context: Context, wake: LockedWake) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(wake.at, show), alarmIntent(context, wake))
        }.onFailure { Log.w(TAG, "could not arm a locked-boot alarm", it) }
    }

    /** The generic word, loud, over the lock screen; written down so the unlock knows it was said. */
    fun announce(context: Context, wake: LockedWake) {
        LockedMirror.update(context) { state ->
            if (state.announced.any { it.id == wake.id && it.at == wake.at }) state else state.copy(announced = state.announced + wake)
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)
        val screen = PendingIntent.getActivity(
            context,
            notificationId(wake.id),
            Intent(context, LockedAlertActivity::class.java).setData(uri(wake.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.locked_alert_title))
            .setContentText(context.getString(R.string.locked_alert_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(wake.at)
            .setShowWhen(true)
            .setContentIntent(open)
            .setFullScreenIntent(screen, true)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(wake.id), notification) }
            .onFailure { Log.w(TAG, "could not post the locked word", it) }
    }

    /**
     * Its own channel, because the alert channels carry the chosen tone, and a tone of the person's
     * own lives in storage a locked phone cannot read: the phone's alarm sound, at alarm volume.
     */
    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.locked_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.locked_channel_description)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            // Honoured only with Do Not Disturb access, which the alert channels ask for too.
            setBypassDnd(true)
        }
        runCatching { manager.createNotificationChannel(channel) }
    }

    fun notificationId(id: String): Int = "locked:$id".hashCode()

    /**
     * Every generic word still up, and the tone each is playing. The screen says no more than "a
     * reminder", so its "Silenciar" is for all of them: two due the same minute were two cards on
     * one screen, and only the first went quiet.
     */
    fun silenceAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { manager.cancel(it.tag, it.id) } }
    }

    /**
     * Unlocked: the locked alarms still standing go, and so do the generic words — the reminders
     * they stood for are told about properly now. Cheap when there is nothing: the alarms are
     * looked up rather than cancelled blind.
     */
    fun settle(context: Context, state: LockedMirrorState) {
        if (!state.armedLocked && state.announced.isEmpty()) return
        val alarms = context.getSystemService(AlarmManager::class.java)
        for (wake in state.wakes) {
            val armed = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, LockedAlarmReceiver::class.java).setData(uri(wake.id)).putExtra(EXTRA_AT, wake.at),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: continue
            runCatching { alarms?.cancel(armed) }
            armed.cancel()
        }
        val manager = NotificationManagerCompat.from(context)
        for (wake in state.announced) runCatching { manager.cancel(notificationId(wake.id)) }
    }
}
