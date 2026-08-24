package dev.rwilco.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import java.io.File

/**
 * Receives PackageInstaller status callbacks. The system usually asks for confirmation, which
 * arrives here as STATUS_PENDING_USER_ACTION with an intent to launch. Launching directly only
 * works while the app is foregrounded (background activity starts are blocked since Android
 * 10), so a tappable notification is posted first — that is what makes the flow reliable when
 * the check ran in the background.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "install status=$status message=$message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                UpdateCenter.report(UpdateUiState.PendingConfirmation(UpdateCenter.lastTarget()))
                UpdateNotifications.notifyConfirmationNeeded(context, Intent(confirm))
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Self-update: the process is normally restarted before this runs; tidy up if not.
                UpdateNotifications.cancel(context)
                UpdateCenter.report(UpdateUiState.Idle)
                discardApk(context)
            }
            else -> {
                val detail = message?.let { ": $it" } ?: ""
                UpdateCenter.report(UpdateUiState.Failed("install status $status$detail"))
                UpdateNotifications.cancel(context)
                if (keepsApkAfterFailure(status)) {
                    // Somebody said no. The APK is fine, so it stays and the shade does not go
                    // quiet on it: the notification turns into the way back, and the button in
                    // settings installs what is already on disk without touching the network.
                    UpdateNotifications.notifyInstallDeclined(context)
                } else {
                    discardApk(context)
                }
            }
        }
    }

    /** Drops the downloaded APK once the install reached a terminal state. */
    private fun discardApk(context: Context) {
        runCatching { File(context.cacheDir, Updater.APK_FILE).delete() }
    }

    companion object {
        const val ACTION = "dev.rwilco.update.INSTALL_STATUS"
        private const val TAG = "RwilcoUpdater"
    }
}
