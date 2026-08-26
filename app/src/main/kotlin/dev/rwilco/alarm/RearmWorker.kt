package dev.rwilco.alarm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.rwilco.RwilcoApplication
import java.util.concurrent.TimeUnit

/**
 * The safety net under the alarms: every few hours, re-arm everything and say what was missed.
 *
 * Alarms do get lost — a force-stop, a battery optimiser, a system update — and the cost of
 * finding out is a reminder that never arrives. WorkManager survives reboots and app updates,
 * so this is the one thing in the app that keeps checking.
 */
class RearmWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as RwilcoApplication
        return runCatching {
            app.firing.rearmAndCatchUp()
            app.geofences.sync()
            app.placeWatcher.sync()
        }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        private const val PERIODIC = "rwilco-rearm-periodic"
        private const val NOW = "rwilco-rearm-now"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RearmWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** After a reboot, a time change, or an app update. */
        fun runNow(context: Context) {
            // Unique: a clock being scrubbed fires TIME_SET a dozen times, and one re-arm answers them all.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RearmWorker>().build())
        }
    }
}
