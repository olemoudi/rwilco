package dev.rwilco.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens a page of the phone's settings — or, when this phone has no such page, the app's own
 * page, which every phone has. Returns whether the page asked for is the one that opened, so
 * the caller can say so.
 *
 * Every "fix" button used to call `startActivity` bare (0.67.0): `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`,
 * `ACTION_USAGE_ACCESS_SETTINGS` and `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS` are absent or
 * blocked on plenty of OEM builds and on Android Go, and the tap on a red row killed the app.
 */
fun Context.openSettingsPage(intent: Intent): Boolean {
    if (runCatching { startActivity(intent) }.isSuccess) return true
    runCatching { startActivity(appDetailsIntent()) }
    return false
}

/** The app's own page in the phone's settings. */
fun Context.appDetailsIntent(): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

/** One notification channel's own page: the switch somebody flipped, not the list it is in. */
fun Context.channelSettingsIntent(channelId: String): Intent =
    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
