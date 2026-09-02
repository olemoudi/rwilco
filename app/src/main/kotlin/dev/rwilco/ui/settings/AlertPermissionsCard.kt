package dev.rwilco.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import kotlin.math.roundToInt
import android.media.AudioManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.rwilco.R
import dev.rwilco.model.TriggerFamily
import dev.rwilco.notify.alarmVolumeIsUp
import dev.rwilco.notify.anyAlertChannelMuted
import dev.rwilco.notify.canDrawOverlays
import dev.rwilco.notify.canGetThroughDnd
import dev.rwilco.notify.canScheduleExactAlarms
import dev.rwilco.notify.ignoresBatteryOptimisations
import dev.rwilco.notify.isBackgroundRestricted
import dev.rwilco.notify.canUseFullScreenIntent
import dev.rwilco.notify.mutedAlertChannelId
import dev.rwilco.ui.components.LocalSnackbar
import dev.rwilco.notify.hasUsageAccess
import dev.rwilco.ui.components.PermissionFixRow
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.theme.LocalDarkTheme
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.familyColor
import dev.rwilco.notify.hasNotificationPolicyAccess
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The ten states that decide whether a reminder actually arrives: the five grants that say how
 * a firing is shown, and the five ways the phone itself can hold one back — battery
 * optimisation, a restricted background, Do Not Disturb on total silence, the alarm volume at
 * zero, and a reminder channel muted by hand.
 *
 * Held out here rather than inside the card because the group that folds the card away has to
 * be able to say, without opening, that something is wrong. Every one of these fails silently,
 * which is the worst way for a reminders app to fail: the person only finds out by not being
 * reminded — so a fold that hid it would be worse than no fold at all.
 */
data class AlertReadiness(
    val notifications: Boolean = true,
    val channels: Boolean = true,
    val fullScreen: Boolean = true,
    val exactAlarms: Boolean = true,
    val alarmVolume: Boolean = true,
    val throughDnd: Boolean = true,
    val unrestricted: Boolean = true,
    val battery: Boolean = true,
    val overlay: Boolean = true,
    val usageAccess: Boolean = true,
    /**
     * Whether the app may cross Do Not Disturb on total silence. Not one of the ten: alarms get
     * through every other mode, so this is an offer rather than a fault — but it is read here
     * with the rest, so the card stays a pure function of one snapshot.
     */
    val policyAccess: Boolean = true,
    /** The muted channel's id when [channels] is false, so the fix opens that channel's page. Not one of the ten. */
    val mutedChannelId: String? = null,
    /**
     * Whether this is an answer at all. Everything above starts granted so a fresh screen never
     * flashes red before the first read — which means the default is a *guess*, and anything
     * that acts on "all good" (Home's strip and what it remembers) must wait for a real one.
     */
    val read: Boolean = false,
) {
    /**
     * How many of the seven that decide whether a reminder *arrives* are in the way; zero is a
     * phone that will ring. The three that only decide how it appears — the full screen, the
     * overlay, usage access — are [quirks], and counted apart (0.68.0): they used to weigh the
     * same as notifications being off, so a person who had refused usage access on purpose
     * (a fair thing to refuse) was told for ever that their phone might not ring.
     */
    val problems: Int
        get() = listOf(notifications, channels, exactAlarms, alarmVolume, throughDnd, unrestricted, battery).count { !it }

    /** The ones that change how a reminder shows itself, never whether it arrives. */
    val quirks: Int get() = listOf(fullScreen, overlay, usageAccess).count { !it }

    val allGood: Boolean get() = problems == 0

    /** The names of the ones in the way, for remembering which of them somebody has already waved off. Worst first. */
    fun problemNames(): Set<String> = buildSet {
        if (!notifications) add("notifications")
        if (!channels) add("channels")
        if (!alarmVolume) add("alarmVolume")
        if (!throughDnd) add("throughDnd")
        if (!exactAlarms) add("exactAlarms")
        if (!unrestricted) add("unrestricted")
        if (!battery) add("battery")
    }
}

/** The problem in a few words, for the strip on Home; see [AlertReadiness.problemNames]. */
fun readinessShortRes(name: String): Int = when (name) {
    "notifications" -> R.string.readiness_short_notifications
    "channels" -> R.string.readiness_short_channels
    "alarmVolume" -> R.string.readiness_short_alarmVolume
    "throughDnd" -> R.string.readiness_short_throughDnd
    "exactAlarms" -> R.string.readiness_short_exactAlarms
    "unrestricted" -> R.string.readiness_short_unrestricted
    else -> R.string.readiness_short_battery
}

/**
 * Whether Home shows its "this phone may not ring" strip: something is in the way that has not
 * been waved off. Never on the optimistic default ([AlertReadiness.read]) — that one says
 * nothing is wrong before anything has been looked at.
 *
 * "Ahora no" remembers the problems by name, so a phone that breaks in a *new* way is told
 * again; what keeps that promise is the pruning on the other side ([liveDismissals]).
 */
fun stripShows(readiness: AlertReadiness, dismissed: Set<String>): Boolean =
    readiness.read && (readiness.problemNames() - dismissed).isNotEmpty()

/**
 * What is still worth remembering as waved off: only the problems that are still there.
 *
 * A problem fixed drops out, so if it comes back it is news again — which is the promise, and
 * which emptying the set at "all good" alone does not keep: with one other thing still in the
 * way, "all good" never arrives and a channel muted for the second time was never mentioned.
 */
fun liveDismissals(readiness: AlertReadiness, dismissed: Set<String>): Set<String> =
    if (!readiness.read) dismissed else dismissed intersect readiness.problemNames()

/**
 * Read again every time the screen comes back: the person may have gone to system settings and
 * changed any of them. Everything starts granted so a fresh screen never flashes red before the
 * first read, and [AlertReadiness.read] says which of the two it is.
 *
 * **Off the main thread.** Twelve of these are binder calls — usage access, app-ops, the
 * notification channels, battery optimisation — and Home asks for them on every resume, on the
 * frame it is being drawn in. In Settings it was a screen somebody opens on purpose; on Home it
 * is the hot path, and this app's rule is that the UI reads state that is already in memory.
 */
@Composable
fun rememberAlertReadiness(): AlertReadiness {
    val context = LocalContext.current
    var readiness by remember { mutableStateOf(AlertReadiness()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { readiness = withContext(Dispatchers.IO) { context.readAlertReadiness() } }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return readiness
}

/** The twelve reads themselves, in one place and off the main thread. */
fun Context.readAlertReadiness(): AlertReadiness = AlertReadiness(
    notifications = NotificationManagerCompat.from(this).areNotificationsEnabled(),
    channels = !anyAlertChannelMuted(),
    mutedChannelId = mutedAlertChannelId(),
    fullScreen = canUseFullScreenIntent(),
    exactAlarms = canScheduleExactAlarms(),
    alarmVolume = alarmVolumeIsUp(),
    throughDnd = canGetThroughDnd(),
    unrestricted = !isBackgroundRestricted(),
    battery = ignoresBatteryOptimisations(),
    overlay = canDrawOverlays(),
    usageAccess = hasUsageAccess(),
    policyAccess = hasNotificationPolicyAccess(),
    read = true,
)

/**
 * What is in the way of a reminder arriving, and the way to fix each one. Location has a card
 * of its own next door.
 */
@Composable
fun AlertPermissionsCard(readiness: AlertReadiness) {
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val pageUnavailable = stringResource(R.string.settings_page_unavailable)
    val volumeRaised = stringResource(R.string.perm_volume_raised)
    // Every fix button goes through this: a page this phone does not have is the app's own
    // page and a word about it, not a crash (see openSettingsPage).
    val open: (Intent) -> Unit = { intent -> if (!context.openSettingsPage(intent)) snackbar.show(pageUnavailable) }
    // A grant needs no bookkeeping here: the dialog resumes the activity behind it, and the
    // resume is what re-reads all ten. Only a refusal has anywhere else to go.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) open(appNotificationSettings(context))
    }

    RwilcoCard {
        Column(Modifier.padding(Tokens.spacing.lg)) {
            if (readiness.allGood) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = familyColor(TriggerFamily.PLACE, LocalDarkTheme.current),
                        modifier = Modifier.size(Tokens.sizes.glyphMedium),
                    )
                    Spacer(Modifier.width(Tokens.spacing.sm))
                    Text(stringResource(R.string.perm_all_good), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!readiness.notifications) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_notifications_missing),
                    action = stringResource(R.string.perm_notifications_fix),
                    onFix = {
                        val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        if (needsRuntimePermission) {
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            open(appNotificationSettings(context))
                        }
                    },
                )
            }
            if (!readiness.channels) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_channel_muted),
                    action = stringResource(R.string.perm_channel_muted_fix),
                    // The channel's own page, where the switch is: the app's list showed four
                    // channels with the same names and no way to tell which one was meant.
                    onFix = { open(readiness.mutedChannelId?.let { context.channelSettingsIntent(it) } ?: appNotificationSettings(context)) },
                )
            }
            if (!readiness.exactAlarms) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_alarms_missing),
                    action = stringResource(R.string.perm_alarms_fix),
                    onFix = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            open(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                            )
                        }
                    },
                )
            }
            if (!readiness.alarmVolume) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_volume_missing),
                    action = stringResource(R.string.perm_volume_fix),
                    // The one problem on this list the app can fix by itself: the alarm stream
                    // needs no permission, and the sound card next door has the same slider.
                    onFix = {
                        val audio = context.getSystemService(AudioManager::class.java)
                        val raised = audio != null && runCatching {
                            audio.setStreamVolume(AudioManager.STREAM_ALARM, (audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 0.6f).roundToInt().coerceAtLeast(1), 0)
                        }.isSuccess
                        if (raised) snackbar.show(volumeRaised) else open(Intent(Settings.ACTION_SOUND_SETTINGS))
                    },
                )
            }
            if (!readiness.throughDnd) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_dnd_missing),
                    action = stringResource(R.string.perm_dnd_fix),
                    onFix = { open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
                )
            }
            // The two that decide whether the app is allowed to keep its promises with the
            // screen off: a restricted background runs no safety net, and battery optimisation
            // is what the phone's own "app killers" hide behind.
            if (!readiness.unrestricted) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_restricted_missing),
                    action = stringResource(R.string.perm_restricted_fix),
                    onFix = {
                        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    },
                )
            }
            if (!readiness.battery) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_battery_missing),
                    action = stringResource(R.string.perm_battery_fix),
                    onFix = {
                        // The one-tap dialog for this app, not the phone-wide list that opens
                        // filtered to "not optimised" — which this app is not in, so "Excluir"
                        // landed somebody on a list to search. The list is the fallback for
                        // the few builds that refuse the dialog.
                        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                        if (runCatching { open(ask) }.isFailure) open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    },
                )
            }
            // The three that decide whether a firing takes the screen or knocks: without them
            // the alert is a banner, which is what the system does on its own. Said under a
            // line of their own and never in red: refusing one is a choice, not a fault.
            if (readiness.quirks > 0) {
                Text(
                    text = stringResource(R.string.perm_quirks_heading),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Tokens.spacing.lg),
                )
            }
            if (!readiness.fullScreen) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_fullscreen_missing),
                    action = stringResource(R.string.perm_fullscreen_fix),
                    quiet = true,
                    onFix = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
                        }
                    },
                )
            }
            if (!readiness.overlay) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_overlay_missing),
                    action = stringResource(R.string.perm_overlay_fix),
                    quiet = true,
                    onFix = {
                        open(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                        )
                    },
                )
            }
            if (!readiness.usageAccess) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_usage_missing),
                    action = stringResource(R.string.perm_usage_fix),
                    quiet = true,
                    onFix = { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                )
            }
            // After the red rows, because it is not one: alarms get through every mode but
            // total silence, so this is an offer rather than a fault. The grant can only be
            // given in advance, and total silence is the mode people put on for the night —
            // which is when a morning alarm matters. Under total silence the red row above
            // already asks for the same grant, so this one steps aside.
            if (readiness.throughDnd && !readiness.policyAccess) {
                SettingsLinkRow(
                    title = stringResource(R.string.perm_dnd_optin),
                    summary = stringResource(R.string.perm_dnd_optin_hint),
                    onClick = { open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
                )
            }
        }
    }
}

private fun appNotificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
