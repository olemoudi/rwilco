package dev.rwilco.geo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * "Allow all the time", precisely: what makes a place reminder work when the app is not open.
 * Coarse is not enough here — a 200-metre circle judged with a kilometre of doubt is a coin
 * toss — and since Android 10 the background grant is a permission of its own.
 */
fun Context.hasBackgroundLocation(): Boolean {
    val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fine) return false
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
}
