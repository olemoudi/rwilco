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

/**
 * Whether the phone will actually let a reminder through, and the way to fix it when it will
 * not. Each of these fails silently, which is the worst way for a reminders app to fail: the
 * person only finds out by not being reminded. Location has a card of its own next door.
 *
 * Ten states, read again every time the screen comes back: the five grants that decide how a
 * firing is shown, and the five ways the phone itself can hold one back — battery
 * optimisation, a restricted background, Do Not Disturb on total silence, the alarm volume at
 * zero, and a reminder channel muted by hand.
 */
@Composable
fun AlertPermissionsCard() {
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(true) }
    var fullScreen by remember { mutableStateOf(true) }
    var exactAlarms by remember { mutableStateOf(true) }
    var overlay by remember { mutableStateOf(true) }
    var usageAccess by remember { mutableStateOf(true) }
    var battery by remember { mutableStateOf(true) }
    var unrestricted by remember { mutableStateOf(true) }
    var throughDnd by remember { mutableStateOf(true) }
    var alarmVolume by remember { mutableStateOf(true) }
    var channels by remember { mutableStateOf(true) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
                fullScreen = context.canUseFullScreenIntent()
                exactAlarms = context.canScheduleExactAlarms()
                overlay = context.canDrawOverlays()
                usageAccess = context.hasUsageAccess()
                battery = context.ignoresBatteryOptimisations()
                unrestricted = !context.isBackgroundRestricted()
                throughDnd = context.canGetThroughDnd()
                alarmVolume = context.alarmVolumeIsUp()
                channels = !context.anyAlertChannelMuted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifications = granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!granted) context.startActivity(appNotificationSettings(context))
    }

    RwilcoCard {
        Column(Modifier.padding(Tokens.spacing.lg)) {
            if (notifications && fullScreen && exactAlarms && overlay && usageAccess && battery && unrestricted && throughDnd && alarmVolume && channels) {
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
            if (!notifications) {
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
            if (!channels) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_channel_muted),
                    action = stringResource(R.string.perm_channel_muted_fix),
                    onFix = { context.startActivity(appNotificationSettings(context)) },
                )
            }
            if (!fullScreen) {
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
            if (!exactAlarms) {
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
            if (!alarmVolume) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_volume_missing),
                    action = stringResource(R.string.perm_volume_fix),
                    onFix = { context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) },
                )
            }
            if (!throughDnd) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_dnd_missing),
                    action = stringResource(R.string.perm_dnd_fix),
                    onFix = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
                )
            }
            // The two that decide whether the app is allowed to keep its promises with the
            // screen off: a restricted background runs no safety net, and battery optimisation
            // is what the phone's own "app killers" hide behind.
            if (!unrestricted) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_restricted_missing),
                    action = stringResource(R.string.perm_restricted_fix),
                    onFix = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    },
                )
            }
            if (!battery) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_battery_missing),
                    action = stringResource(R.string.perm_battery_fix),
                    onFix = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                )
            }
            // The two that decide whether a firing takes the screen or knocks: without them the
            // alert is always a banner, which is what the system does on its own.
            if (!overlay) {
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
            if (!usageAccess) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_usage_missing),
                    action = stringResource(R.string.perm_usage_fix),
                    onFix = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                )
            }
        }
    }
}

private fun appNotificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
