package dev.rwilco.vault

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.rwilco.RwilcoApplication
import java.util.concurrent.TimeUnit

/**
 * Runs the backup off the main thread, from three doors.
 *
 * [runSoon] is the one that makes the promise: a quarter of an hour after something changed,
 * while the phone is still awake from the change, whatever standby bucket the app is in. The
 * periodic request is the net under it — for the history that changes while nobody is
 * editing (a ring, a snooze) and for anything the one-off missed. Only the periodic run
 * retries: a one-off with no network constraint retrying for an hour would be exactly the
 * silence the manual button exists to avoid.
 */
class VaultWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as RwilcoApplication
        val result = app.vaultBackup().run()
        Log.i(TAG, "backup run: $result")
        val periodic = inputData.getBoolean(KEY_PERIODIC, false)
        return when (result) {
            VaultRunResult.RETRY -> if (periodic && runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            VaultRunResult.FAILED -> Result.failure()
            VaultRunResult.DONE, VaultRunResult.BUSY -> Result.success()
        }
    }

    companion object {
        private const val TAG = "RwilcoVault"
        private const val PERIODIC = "rwilco-vault-periodic"
        private const val SOON = "rwilco-vault-soon"
        private const val MAX_RETRIES = 5
        private const val KEY_PERIODIC = "periodic"

        /** How long after a change the upload goes: a burst of edits becomes one commit. */
        const val SOON_DELAY_MINUTES = 15L

        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Idempotent: every two hours while the vault is on. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<VaultWorker>(2, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
                .setConstraints(connected)
                .setInputData(workDataOf(KEY_PERIODIC to true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Off: nothing runs any more. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC)
                cancelUniqueWork(SOON)
            }
        }

        /** Something changed: one upload, soon, however many more changes come before it. */
        fun runSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<VaultWorker>()
                .setConstraints(connected)
                .setInitialDelay(SOON_DELAY_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * The button, and the first upload after enabling. Unconstrained on purpose: a tap with
         * no signal should fail visibly rather than queue into a silence.
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<VaultWorker>().setConstraints(Constraints.NONE).build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
