package dev.rwilco.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import dev.rwilco.notify.hasUsageAccess
import dev.rwilco.ui.components.PermissionFixRow
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.theme.LocalDarkTheme
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.familyColor
import dev.rwilco.notify.hasNotificationPolicyAccess

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
) {
    /** How many of the ten are in the way; zero is a phone that will ring. */
    val problems: Int
        get() = listOf(
            notifications, channels, fullScreen, exactAlarms, alarmVolume,
            throughDnd, unrestricted, battery, overlay, usageAccess,
        ).count { !it }

    val allGood: Boolean get() = problems == 0

    /** The names of the ones in the way, for remembering which of them somebody has already waved off. */
    fun problemNames(): Set<String> = buildSet {
        if (!notifications) add("notifications")
        if (!channels) add("channels")
        if (!fullScreen) add("fullScreen")
        if (!exactAlarms) add("exactAlarms")
        if (!alarmVolume) add("alarmVolume")
        if (!throughDnd) add("throughDnd")
        if (!unrestricted) add("unrestricted")
        if (!battery) add("battery")
        if (!overlay) add("overlay")
        if (!usageAccess) add("usageAccess")
    }
}

/**
 * Whether Home shows its "this phone may not ring" strip: something is in the way that has not
 * been waved off. "Not now" remembers the problems as they were, so a phone that breaks in a
 * *new* way is told again, and the set is cleared once everything is granted — fixed and then
 * broken again is news too.
 */
fun stripShows(readiness: AlertReadiness, dismissed: Set<String>): Boolean = (readiness.problemNames() - dismissed).isNotEmpty()

/**
 * Read again every time the screen comes back: the person may have gone to system settings and
 * changed any of them. Everything starts granted so a fresh screen never flashes red before the
 * first read.
 */
@Composable
fun rememberAlertReadiness(): AlertReadiness {
    val context = LocalContext.current
    var readiness by remember { mutableStateOf(AlertReadiness()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                readiness = AlertReadiness(
                    notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                    channels = !context.anyAlertChannelMuted(),
                    fullScreen = context.canUseFullScreenIntent(),
                    exactAlarms = context.canScheduleExactAlarms(),
                    alarmVolume = context.alarmVolumeIsUp(),
                    throughDnd = context.canGetThroughDnd(),
                    unrestricted = !context.isBackgroundRestricted(),
                    battery = context.ignoresBatteryOptimisations(),
                    overlay = context.canDrawOverlays(),
                    usageAccess = context.hasUsageAccess(),
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return readiness
}

/**
 * What is in the way of a reminder arriving, and the way to fix each one. Location has a card
 * of its own next door.
 */
@Composable
fun AlertPermissionsCard(readiness: AlertReadiness) {
    val context = LocalContext.current
    // A grant needs no bookkeeping here: the dialog resumes the activity behind it, and the
    // resume is what re-reads all ten. Only a refusal has anywhere else to go.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) context.startActivity(appNotificationSettings(context))
    }

    RwilcoCard {
        Column(Modifier.padding(Tokens.spacing.lg)) {
            if (readiness.allGood) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = familyColor(TriggerFamily.PLACE, LocalDarkTheme.current),
                        modifier = Modifier.size(20.dp),
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
                            context.startActivity(appNotificationSettings(context))
                        }
                    },
                )
            }
            if (!readiness.channels) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_channel_muted),
                    action = stringResource(R.string.perm_channel_muted_fix),
                    onFix = { context.startActivity(appNotificationSettings(context)) },
                )
            }
            if (!readiness.fullScreen) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_fullscreen_missing),
                    action = stringResource(R.string.perm_fullscreen_fix),
                    onFix = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")),
                            )
                        }
                    },
                )
            }
            if (!readiness.exactAlarms) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_alarms_missing),
                    action = stringResource(R.string.perm_alarms_fix),
                    onFix = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.startActivity(
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
                    onFix = { context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) },
                )
            }
            if (!readiness.throughDnd) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_dnd_missing),
                    action = stringResource(R.string.perm_dnd_fix),
                    onFix = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
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
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    },
                )
            }
            if (!readiness.battery) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_battery_missing),
                    action = stringResource(R.string.perm_battery_fix),
                    onFix = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                )
            }
            // The two that decide whether a firing takes the screen or knocks: without them the
            // alert is always a banner, which is what the system does on its own.
            if (!readiness.overlay) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_overlay_missing),
                    action = stringResource(R.string.perm_overlay_fix),
                    onFix = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                        )
                    },
                )
            }
            if (!readiness.usageAccess) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_usage_missing),
                    action = stringResource(R.string.perm_usage_fix),
                    onFix = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                )
            }
            // After the red rows, because it is not one: alarms get through every mode but
            // total silence, so this is an offer rather than a fault. The grant can only be
            // given in advance, and total silence is the mode people put on for the night —
            // which is when a morning alarm matters. Under total silence the red row above
            // already asks for the same grant, so this one steps aside.
            if (readiness.throughDnd && !context.hasNotificationPolicyAccess()) {
                SettingsLinkRow(
                    title = stringResource(R.string.perm_dnd_optin),
                    summary = stringResource(R.string.perm_dnd_optin_hint),
                    onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) } },
                )
            }
        }
    }
}

private fun appNotificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
