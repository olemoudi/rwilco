package dev.rwilco.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * A check after a reboot and after a self-update: a committed install kills the process, and
 * without MY_PACKAGE_REPLACED nothing would look for the next release until the periodic work
 * came round. WorkManager persists across both; rescheduling is free.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        UpdateWorker.schedule(context)
        UpdateWorker.runNow(context)
    }
}
