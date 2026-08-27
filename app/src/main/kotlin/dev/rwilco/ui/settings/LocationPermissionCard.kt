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
import dev.rwilco.model.PlaceWatchState
import dev.rwilco.model.TriggerFamily
import dev.rwilco.ui.components.PermissionFixRow
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.rememberNow
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.theme.LocalDarkTheme
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.familyColor
import java.time.Clock
import java.time.Duration

/** How much of the phone's location Rwilco can see, in the order the system grants it. */
enum class LocationAccess {
    NONE,

    /** Coarse only. Enough to drop a pin, never to judge a two-hundred-metre circle. */
    APPROXIMATE,

    WHILE_IN_USE,
    ALWAYS,
}

/**
 * Whether a place reminder can be kept at all: what was granted, and whether the phone's own
 * location switch is even on. Held out of the card so the group that folds it away can say, on
 * the closed row, that a place reminder is waiting on something.
 */
data class PlaceReadiness(val access: LocationAccess, val locationOn: Boolean) {
    /** Everything a geofence needs. Anything less and a place trigger is a promise unkept. */
    val ready: Boolean get() = access == LocationAccess.ALWAYS && locationOn
}

/** Re-read on every resume: the person may have gone to system settings and come back. */
@Composable
fun rememberPlaceReadiness(): PlaceReadiness {
    val context = LocalContext.current
    var readiness by remember { mutableStateOf(PlaceReadiness(context.locationAccess(), context.isLocationEnabled())) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                readiness = PlaceReadiness(context.locationAccess(), context.isLocationEnabled())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return readiness
}

/**
 * Where a place reminder stands or falls.
 *
 * Shown whether or not a place reminder exists yet, because "all the time" is the one permission
 * that has to be in place *before* it is needed: the trigger is written once and then waited on
 * for weeks, and a refusal at that point is silent. What watches in the background is the
 * geofencing in `GeofenceManager` — the phone's own location stack — and, next to it, the
 * app's own `PlaceWatcher`, whose last look and next look this card reports so the owner can
 * see it working without a walk.
 */
@Composable
fun LocationPermissionCard(readiness: PlaceReadiness, needsPlaces: Boolean, watch: PlaceWatchState? = null) {
    val context = LocalContext.current
    val access = readiness.access
    val locationOn = readiness.locationOn

    val askForeground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // A flat refusal cannot be asked again from here: the system stops showing the dialog.
        val granted = context.locationAccess()
        if (granted == LocationAccess.NONE || granted == LocationAccess.APPROXIMATE) context.startActivity(appDetails(context))
    }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Since Android 11 "all the time" only exists on the app's own settings page; the
        // request above is refused without ever showing a dialog, so this is the way through.
        if (context.locationAccess() != LocationAccess.ALWAYS) context.startActivity(appDetails(context))
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
                            LocationAccess.APPROXIMATE -> R.string.perm_location_approximate
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
            // With places to watch and the grant to do it, what the app's own watch is up to:
            // the one line that proves, on the phone, that it is looking at all.
            if (needsPlaces && access == LocationAccess.ALWAYS) {
                Spacer(Modifier.height(Tokens.spacing.sm))
                Text(
                    text = stringResource(R.string.place_watch_how),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Tokens.spacing.xs))
                Text(
                    text = placeWatchLine(watch),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
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
                // Approximate is a yes that does not help: the card has to say "precise", or
                // the person is sent round the background grant for a problem it cannot fix.
                LocationAccess.APPROXIMATE -> PermissionFixRow(
                    text = stringResource(R.string.perm_location_precise_missing),
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

/** "Last look 3 min ago · 1.2 km from Casa · next look in 12 min", or that it has not looked yet. */
@Composable
private fun placeWatchLine(watch: PlaceWatchState?): String {
    val fix = watch?.lastFix
    val gap = watch?.lastGapM
    val label = watch?.nearestLabel
    val next = watch?.nextCheckAt
    if (fix == null || gap == null || label == null || next == null) return stringResource(R.string.place_watch_waiting)
    val now by rememberNow(60_000, Clock.systemUTC())
    val locale = currentLocale()
    val distance = if (gap < 1000) {
        stringResource(R.string.place_metres, gap.toInt())
    } else {
        stringResource(R.string.place_kilometres, String.format(locale, "%.1f", gap / 1000))
    }
    // A next look behind us is one the alarm has not delivered yet (Doze holds them), and
    // saying "in" about it would be the card lying about the one thing it is there to show.
    val nextIn = if (next > now) R.string.countdown_in else R.string.countdown_ago
    return stringResource(
        R.string.place_watch_status,
        stringResource(R.string.countdown_ago, spanText(Duration.between(fix.at, now))),
        distance,
        label,
        stringResource(nextIn, spanText(Duration.between(now, next))),
    )
}

/** A span in the coarsest unit that says something: "3 d", "2 h 14 min", "5 min". Never under a minute. */
@Composable
private fun spanText(span: Duration): String {
    val total = span.abs()
    val days = total.toDays()
    val hours = total.toHours() % 24
    val minutes = (total.toMinutes() % 60).coerceAtLeast(if (days == 0L && hours == 0L) 1L else 0L)
    return when {
        days > 0 -> stringResource(R.string.countdown_days, days.toInt())
        hours > 0 -> stringResource(R.string.countdown_hours, hours.toInt()) + " " + stringResource(R.string.countdown_minutes, minutes.toInt())
        else -> stringResource(R.string.countdown_minutes, minutes.toInt())
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
    if (!fine) return LocationAccess.APPROXIMATE
    val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    return if (background) LocationAccess.ALWAYS else LocationAccess.WHILE_IN_USE
}

/** The phone's own location switch, which no permission can substitute for. */
fun Context.isLocationEnabled(): Boolean =
    getSystemService(LocationManager::class.java)?.isLocationEnabled ?: false

private fun appDetails(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
