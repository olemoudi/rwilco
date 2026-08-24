package dev.rwilco.ui.settings

import android.Manifest
import android.app.AlarmManager
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
import dev.rwilco.ui.components.PermissionFixRow
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.theme.LocalDarkTheme
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.familyColor

/**
 * Whether the phone will actually let a reminder through, and the way to fix it when it will
 * not. Three things can stand in the way and each fails silently, which is the worst way for a
 * reminders app to fail: the person only finds out by not being reminded.
 */
@Composable
fun AlertPermissionsCard(needsPlaces: Boolean) {
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(true) }
    var fullScreen by remember { mutableStateOf(true) }
    var exactAlarms by remember { mutableStateOf(true) }
    var backgroundLocation by remember { mutableStateOf(true) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
                fullScreen = context.canUseFullScreenIntent()
                exactAlarms = context.canScheduleExactAlarms()
                backgroundLocation = context.hasBackgroundLocation()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifications = granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!granted) context.startActivity(appNotificationSettings(context))
    }

    val placesOk = !needsPlaces || backgroundLocation
    val askBackgroundLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        backgroundLocation = granted || context.hasBackgroundLocation()
        // Since Android 11 "allow all the time" is only reachable from the app's own settings
        // page; the request above is refused without a dialog, so this is the way through.
        if (!backgroundLocation) context.startActivity(appDetailsSettings(context))
    }

    RwilcoCard {
        Column(Modifier.padding(Tokens.spacing.lg)) {
            if (notifications && fullScreen && exactAlarms && placesOk) {
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
            if (needsPlaces && !backgroundLocation) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_background_location_missing),
                    action = stringResource(R.string.perm_background_location_fix),
                    onFix = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            askBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        } else {
                            context.startActivity(appDetailsSettings(context))
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
        }
    }
}

private fun appDetailsSettings(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

private fun Context.hasBackgroundLocation(): Boolean {
    val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine) return false
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
}

private fun appNotificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

/**
 * Since Android 14 a full-screen intent is only for calls and alarms, and everyone else gets a
 * heads-up notification instead unless the person says otherwise. Sideloaded apps land on the
 * wrong side of that line by default.
 */
private fun Context.canUseFullScreenIntent(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: false

private fun Context.canScheduleExactAlarms(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
