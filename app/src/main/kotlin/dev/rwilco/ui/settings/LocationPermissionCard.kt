package dev.rwilco.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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

/** How much of the phone's location Rwilco can see, in the order the system grants it. */
enum class LocationAccess { NONE, WHILE_IN_USE, ALWAYS }

/**
 * Where a place reminder stands or falls.
 *
 * Shown whether or not a place reminder exists yet, because "all the time" is the one permission
 * that has to be in place *before* it is needed: the trigger is written once and then waited on
 * for weeks, and a refusal at that point is silent. What actually watches in the background is
 * the geofencing in `GeofenceManager` — the phone's own location stack, which costs no battery
 * worth naming; Rwilco never polls a position of its own.
 */
@Composable
fun LocationPermissionCard(needsPlaces: Boolean) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(context.locationAccess()) }
    var locationOn by remember { mutableStateOf(context.isLocationEnabled()) }

    // The grant can change while we are away — the person may have gone to system settings and
    // come back — so it is re-read every time the screen is resumed rather than once.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                access = context.locationAccess()
                locationOn = context.isLocationEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val askForeground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = context.locationAccess()
        // A flat refusal cannot be asked again from here: the system stops showing the dialog.
        if (access == LocationAccess.NONE) context.startActivity(appDetails(context))
    }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        access = context.locationAccess()
        // Since Android 11 "all the time" only exists on the app's own settings page; the
        // request above is refused without ever showing a dialog, so this is the way through.
        if (access != LocationAccess.ALWAYS) context.startActivity(appDetails(context))
    }

    RwilcoCard {
        Column(Modifier.padding(Tokens.spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (access == LocationAccess.ALWAYS) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = familyColor(TriggerFamily.PLACE, LocalDarkTheme.current),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(Tokens.spacing.sm))
                }
                Text(
                    text = stringResource(
                        when (access) {
                            LocationAccess.NONE -> R.string.perm_location_none
                            LocationAccess.WHILE_IN_USE -> R.string.perm_location_while_in_use
                            LocationAccess.ALWAYS -> R.string.perm_location_always
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(Tokens.spacing.xs))
            Text(
                text = stringResource(if (needsPlaces) R.string.perm_location_why_needed else R.string.perm_location_why),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (access) {
                LocationAccess.NONE -> PermissionFixRow(
                    text = stringResource(R.string.perm_location_missing),
                    action = stringResource(R.string.perm_location_fix),
                    onFix = {
                        askForeground.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    },
                )
                LocationAccess.WHILE_IN_USE -> PermissionFixRow(
                    text = stringResource(R.string.perm_background_location_missing),
                    action = stringResource(R.string.perm_background_location_fix),
                    onFix = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        } else {
                            context.startActivity(appDetails(context))
                        }
                    },
                )
                LocationAccess.ALWAYS -> Unit
            }
            // Granted and still useless: the switch in the quick settings is off.
            if (!locationOn && access != LocationAccess.NONE) {
                PermissionFixRow(
                    text = stringResource(R.string.perm_location_off),
                    action = stringResource(R.string.perm_location_off_fix),
                    onFix = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                )
            }
        }
    }
}

/**
 * What was granted. Coarse-only counts as [LocationAccess.WHILE_IN_USE] for the map, but never
 * as ALWAYS: geofencing needs the precise permission, so "approximate + all the time" would
 * promise a place reminder the phone cannot keep.
 */
fun Context.locationAccess(): LocationAccess {
    val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return LocationAccess.NONE
    val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    return if (fine && background) LocationAccess.ALWAYS else LocationAccess.WHILE_IN_USE
}

/** The phone's own location switch, which no permission can substitute for. */
fun Context.isLocationEnabled(): Boolean =
    getSystemService(LocationManager::class.java)?.isLocationEnabled ?: false

private fun appDetails(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
