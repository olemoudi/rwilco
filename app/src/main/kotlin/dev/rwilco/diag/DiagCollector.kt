package dev.rwilco.diag

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dev.rwilco.BuildConfig
import dev.rwilco.RwilcoApplication
import dev.rwilco.notify.alarmVolumeDescription
import dev.rwilco.notify.anyAlertChannelMuted
import dev.rwilco.notify.canDrawOverlays
import dev.rwilco.notify.canScheduleExactAlarms
import dev.rwilco.notify.canUseFullScreenIntent
import dev.rwilco.notify.dndDescription
import dev.rwilco.notify.hasUsageAccess
import dev.rwilco.notify.ignoresBatteryOptimisations
import dev.rwilco.notify.isBackgroundRestricted
import dev.rwilco.vault.pendingChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.Locale

/**
 * Everything the report is made of, gathered in one pass off the main thread. Nothing here
 * decides anything: it reads the phone, the settings and the reminders as they stand and hands
 * them to [report].
 */
suspend fun RwilcoApplication.collectDiagnostics(): Diagnostics = withContext(Dispatchers.Default) {
    val vault = vaultStore.read()
    val rows = repository.allRows()
    Diagnostics(
        env = DiagEnv(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            sdk = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            locale = Locale.getDefault().toLanguageTag(),
            zone = clock.zone.id,
            now = clock.instant(),
            processUptime = runCatching {
                Duration.ofMillis(SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
            }.getOrNull(),
        ),
        permissions = permissions(),
        settings = settingsStore.settings.first(),
        reminders = repository.allNow(),
        vault = DiagVault(
            enabled = vault.enabled,
            repo = if (vault.enabled) "${vault.owner}/${vault.repo}" else "-",
            cadence = vault.cadence.name,
            wifiOnly = vault.wifiOnly,
            lastRunAt = vault.lastRunAt,
            lastUploadedAt = vault.lastUploadedAt,
            lastUploadedBytes = vault.lastUploadedBytes,
            outcome = vault.lastOutcome?.name,
            pending = pendingChanges(rows, settingsStore.rawJson().orEmpty(), vault),
        ).takeIf { vault.enabled },
        notes = diagStore.read().notes,
        watch = placeLog.read().notes,
        events = repository.recentHistory(DIAG_EVENTS_PER_REMINDER),
        watchState = placeWatch.read(),
        locationProviders = enabledProviders(),
    )
}

private fun Context.permissions(): DiagPermissions = DiagPermissions(
    notifications = NotificationManagerCompat.from(this).areNotificationsEnabled(),
    anyChannelMuted = anyAlertChannelMuted(),
    fullScreenIntent = canUseFullScreenIntent(),
    exactAlarms = canScheduleExactAlarms(),
    overlay = canDrawOverlays(),
    usageAccess = hasUsageAccess(),
    ignoresBatteryOptimisation = ignoresBatteryOptimisations(),
    backgroundRestricted = isBackgroundRestricted(),
    dnd = dndDescription(),
    alarmVolume = alarmVolumeDescription(),
    location = locationDescription(),
    playServices = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
    }.getOrDefault(false),
)

/**
 * The platform's own providers that are switched on — the door the map's blue dot and the
 * crosshair knock at, which is not the door the place watch uses (Play Services' fused
 * provider). A phone whose watch is finding positions every quarter of an hour and whose map
 * draws no dot is telling you the two are different doors, and this is what says so.
 */
private fun Context.enabledProviders(): List<String> {
    val manager = getSystemService(LocationManager::class.java) ?: return emptyList()
    val candidates = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
    }
    return candidates.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
}

/** What the app may know about where the phone is, and whether the phone will say at all. */
private fun Context.locationDescription(): String {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    val grant = when {
        granted(Manifest.permission.ACCESS_FINE_LOCATION) -> "fine"
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> "coarse"
        else -> "none"
    }
    val background = if (granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) "+background" else ""
    val on = getSystemService(LocationManager::class.java)?.isLocationEnabled ?: false
    return "$grant$background/services=${if (on) "on" else "OFF"}"
}
