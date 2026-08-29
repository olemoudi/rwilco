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
 * Alarms do get lost — a battery optimiser, a system update, a Play Services that dropped the
 * fences — and the cost of finding out is a reminder that never arrives. WorkManager survives
 * reboots and app updates, so this is the one thing in the app that keeps checking. Not a
 * force-stop, though: a force-stopped app runs no work and receives no broadcast until somebody
 * opens it, and nothing here can reach past that.
 */
class RearmWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as RwilcoApplication
        return runCatching {
            app.firing.rearmAndCatchUp()
            app.geofences.sync()
            app.placeWatcher.sync()
            // The history is kept for three months and swept here, beside the re-arm, because
            // this is the one thing in the app that keeps running whether or not anybody opens
            // it — and a list nobody opens is exactly the one that grows.
            app.repository.sweepOldDone()
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
