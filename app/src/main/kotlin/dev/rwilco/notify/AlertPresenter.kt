package dev.rwilco.notify

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dev.rwilco.alarm.ReminderScheduler
import dev.rwilco.diag.Diag
import dev.rwilco.model.AppSettings
import dev.rwilco.model.FiringPlan
import dev.rwilco.model.AlertSound
import dev.rwilco.model.Reminder
import dev.rwilco.model.VibrationPattern
import dev.rwilco.ui.alert.AlertActivity
import java.time.LocalTime
import java.time.Instant
import dev.rwilco.model.DEFAULT_SNOOZE_MINUTES
import dev.rwilco.model.Snooze
import dev.rwilco.model.notificationSnoozeOffers

/** Where a firing shows itself. */
enum class AlertPresentation { FULL_SCREEN, BANNER }

/** What is in front of the person right now, as far as we are allowed to know. */
enum class ForegroundApp {
    /** Nothing of anybody's: the home screen. */
    NONE,

    /** Rwilco itself. */
    OURS,

    /** Somebody else's app, in the middle of something. */
    OTHER,

    /** Not allowed to look (usage access not granted). */
    UNKNOWN,
}

/**
 * Whether to take the screen or knock on the door.
 *
 * Taking the screen from somebody mid-sentence in another app is rude in a way an alarm clock
 * never is, and a banner over the home screen is a whisper in an empty room. So: an app open in
 * front of them gets the banner, and everything else — home screen, screen off, lock screen —
 * gets the whole screen.
 *
 * The last two guards are the honest ones: without usage access we cannot tell an app from the
 * home screen, and without "display over other apps" Android will not let a background app
 * start a screen at all. Either missing means falling back to the banner, which is exactly the
 * behaviour the system gives on its own, and Settings says which permission would fix it.
 */
fun alertPresentation(
    fullScreenWanted: Boolean,
    inUse: Boolean,
    foreground: ForegroundApp,
    canOverlay: Boolean,
    /** Whether the system will honour a full-screen intent at all: Android 14+ can refuse it. */
    canFullScreen: Boolean = true,
): AlertPresentation = when {
    !fullScreenWanted -> AlertPresentation.BANNER
    // Screen off or locked: only the system's full-screen intent can light it — and when the
    // system refuses that, the notification has to make the noise itself. Deciding
    // FULL_SCREEN here regardless once muted the notification for a screen that never came.
    !inUse -> if (canFullScreen) AlertPresentation.FULL_SCREEN else AlertPresentation.BANNER
    foreground == ForegroundApp.OTHER -> AlertPresentation.BANNER
    foreground == ForegroundApp.UNKNOWN -> AlertPresentation.BANNER
    !canOverlay -> AlertPresentation.BANNER
    else -> AlertPresentation.FULL_SCREEN
}

/**
 * Puts a firing in front of the person: the notification always, and the alert screen when the
 * decision above says it should take over.
 */
object AlertPresenter {

    /**
     * [takeScreen] false is a repeat of a sound that has already been made: once was the alarm
     * and this is the reminder of the alarm, so it never takes the screen a second time.
     */
    fun show(
        context: Context,
        reminder: Reminder,
        plan: FiringPlan,
        late: Instant?,
        vibration: VibrationPattern = VibrationPattern(),
        sound: AlertSound = AlertSound.System,
        takeScreen: Boolean = true,
        /** The rule whose moment rang, so the screen can say which; null for a snooze or a recurrence. */
        ruleIndex: Int? = null,
        /** Passed straight through to the notification's reason line. */
        defaultTime: LocalTime = AppSettings().defaultTime,
        /** Passed straight through to the notification's buttons. */
        snoozes: List<Snooze> = AppSettings().notificationSnoozeOffers,
        customMinutes: Int = DEFAULT_SNOOZE_MINUTES,
    ) {
        // Every action turned off is an answer too: the moment passes without a word, and the
        // reminder is simply overdue on Home afterwards.
        if (!plan.notification && !plan.fullScreen) {
            Log.i(TAG, "${reminder.id} fired with nothing to do about it")
            return
        }
        val inUse = context.isInUse()
        val foreground = context.foregroundApp()
        val overlay = context.canDrawOverlays()
        val fsi = context.canUseFullScreenIntent()
        val wanted = plan.fullScreen && late == null && takeScreen
        val presentation = alertPresentation(
            fullScreenWanted = wanted,
            inUse = inUse,
            foreground = foreground,
            canOverlay = overlay,
            canFullScreen = fsi,
        )
        // With the screen on, the takeover is ours to start — and it is started BEFORE the
        // notification, because whether it took decides which channel the notification goes
        // on. Posted first, a refused start left a silent card behind a screen that never came.
        // With the screen off or locked, the notification's full-screen intent is what launches
        // the alert: the system does it for us, and doing it here as well would race with it.
        val screenTaken = presentation == AlertPresentation.FULL_SCREEN && inUse && startAlert(context, reminder, ruleIndex)
        val fullScreen = presentation == AlertPresentation.FULL_SCREEN && (screenTaken || !inUse)
        Diag.note(
            "show",
            "r=${reminder.id.take(8)} $presentation screen=${if (screenTaken) "taken" else if (inUse) "refused" else "system"} " +
                "inUse=$inUse fg=$foreground overlay=$overlay fsi=$fsi notif=${NotificationManagerCompat.from(context).areNotificationsEnabled()}",
        )
        AlertNotifications.post(
            context,
            reminder,
            plan,
            late,
            fullScreen = fullScreen,
            vibration = vibration,
            chosen = sound,
            ruleIndex = ruleIndex,
            defaultTime = defaultTime,
            snoozes = snoozes,
            customMinutes = customMinutes,
        )
        // Notifications switched off make post() a silent no-op, and the moment is already
        // spent. The one thing left that can reach the person is the screen itself, which knows
        // how to show over the lock and turn the display on; it is worth a try from anywhere.
        // From a screen that is off or locked too: the notification's full-screen intent is
        // what would have launched it, and a notification that never posted launches nothing.
        if (!screenTaken && wanted && !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w(TAG, "notifications are off; trying the screen for ${reminder.id}")
            startAlert(context, reminder, ruleIndex)
        }
    }

    /** The alert screen, started from here; false when the system would not let a background app start it. */
    private fun startAlert(context: Context, reminder: Reminder, ruleIndex: Int?): Boolean = runCatching {
        context.startActivity(
            Intent(context, AlertActivity::class.java)
                .setData(ReminderScheduler.reminderUri(reminder.id))
                .apply { if (ruleIndex != null) putExtra(ReminderScheduler.EXTRA_RULE, ruleIndex) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { Log.w(TAG, "could not take the screen; the notification carries it", it) }.isSuccess

    private const val TAG = "RwilcoAlerts"
}

/** Screen on and unlocked: somebody is holding the phone. */
fun Context.isInUse(): Boolean {
    val power = getSystemService(PowerManager::class.java)
    val keyguard = getSystemService(KeyguardManager::class.java)
    val awake = power?.isInteractive ?: true
    val locked = keyguard?.isKeyguardLocked ?: false
    return awake && !locked
}

fun Context.canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

/**
 * Since Android 14 a full-screen intent is only for calls and alarms, and everyone else gets a
 * heads-up notification instead unless the person says otherwise. Sideloaded apps land on the
 * wrong side of that line by default.
 */
fun Context.canUseFullScreenIntent(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: false

/**
 * What was resumed last. Usage access is the only way an ordinary app can ask, and it is a
 * permission the person grants by hand in system settings — so [ForegroundApp.UNKNOWN] is the
 * normal answer until they do.
 */
fun Context.foregroundApp(): ForegroundApp {
    if (!hasUsageAccess()) return ForegroundApp.UNKNOWN
    val stats = getSystemService(UsageStatsManager::class.java) ?: return ForegroundApp.UNKNOWN
    val now = System.currentTimeMillis()
    val resumed = runCatching {
        val events = stats.queryEvents(now - LOOK_BACK_MS, now)
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = event.packageName
        }
        last
    }.getOrNull() ?: return ForegroundApp.NONE
    return when {
        resumed == packageName -> ForegroundApp.OURS
        resumed in homePackages() -> ForegroundApp.NONE
        else -> ForegroundApp.OTHER
    }
}

fun Context.hasUsageAccess(): Boolean {
    val ops = getSystemService(AppOpsManager::class.java) ?: return false
    val mode = runCatching {
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
    }.getOrDefault(AppOpsManager.MODE_ERRORED)
    return mode == AppOpsManager.MODE_ALLOWED
}

/**
 * Every launcher installed, not just the default one: a phone that has been through two of them
 * still answers HOME with both, and being on any of them is being on the home screen.
 */
private fun Context.homePackages(): Set<String> = runCatching {
    packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        .mapNotNullTo(HashSet()) { it.activityInfo?.packageName }
}.getOrDefault(emptySet())

/** Long enough to survive a screen that has been idle for a moment, short enough to be current. */
private const val LOOK_BACK_MS = 10 * 60 * 1000L
