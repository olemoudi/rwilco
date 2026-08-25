package dev.rwilco.notify

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import dev.rwilco.alarm.ReminderScheduler
import dev.rwilco.model.FiringPlan
import dev.rwilco.model.AlertSound
import dev.rwilco.model.Reminder
import dev.rwilco.model.VibrationPattern
import dev.rwilco.ui.alert.AlertActivity
import java.time.Instant

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
): AlertPresentation = when {
    !fullScreenWanted -> AlertPresentation.BANNER
    // Screen off or locked: the full-screen intent is the system's own job and needs no
    // permission of ours.
    !inUse -> AlertPresentation.FULL_SCREEN
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
    ) {
        // Every action turned off is an answer too: the moment passes without a word, and the
        // reminder is simply overdue on Home afterwards.
        if (!plan.notification && !plan.fullScreen) {
            Log.i(TAG, "${reminder.id} fired with nothing to do about it")
            return
        }
        val inUse = context.isInUse()
        val presentation = alertPresentation(
            fullScreenWanted = plan.fullScreen && late == null && takeScreen,
            inUse = inUse,
            foreground = context.foregroundApp(),
            canOverlay = context.canDrawOverlays(),
        )
        AlertNotifications.post(
            context,
            reminder,
            plan,
            late,
            fullScreen = presentation == AlertPresentation.FULL_SCREEN,
            vibration = vibration,
            chosen = sound,
        )
        // With the screen off or locked, the notification's full-screen intent is what launches
        // the alert — the system does it for us, and doing it here as well would race with it.
        if (presentation == AlertPresentation.FULL_SCREEN && inUse) {
            runCatching {
                context.startActivity(
                    Intent(context, AlertActivity::class.java)
                        .setData(ReminderScheduler.reminderUri(reminder.id))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            }.onFailure { Log.w(TAG, "could not take the screen; the notification carries it", it) }
        }
    }

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
