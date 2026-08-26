package dev.rwilco.vault

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.backupDelay
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * Runs the backup off the main thread, the way anacron runs a job: not "every four hours on the
 * hour", but "four hours after the last one that worked, and if it did not work, keep trying".
 *
 * So there is no periodic request. Each run schedules the next from the state as it stands when
 * it finishes ([VaultState.lastRunAt] — a copy made, or a look that found nothing to copy), and
 * a run that could not reach GitHub returns `retry`, which WorkManager keeps bringing back with
 * a growing wait until it goes through. Three days of failing and a copy on the fourth put the
 * next weekly copy on the eleventh day: the clock starts at the copy, not at the calendar.
 *
 * The forced run — the button, the Home badge, the first copy after turning it on — is its own
 * unique work with no constraints and no retries: a tap made with no signal should fail visibly
 * rather than queue into a silence.
 */
class VaultWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as RwilcoApplication
        val forced = inputData.getBoolean(KEY_FORCED, false)
        val result = app.vaultBackup().run()
        Log.i(TAG, "backup run: $result${if (forced) " (forced)" else ""}")
        // A run that has to come back keeps its own place in the queue; anything else has
        // finished this round, so the next one is booked from what it just wrote down.
        if (!(result == VaultRunResult.RETRY && !forced)) schedule(applicationContext, app.vaultStore.read(), replace = true)
        return when (result) {
            // Unbounded on purpose: "it failed" is not "give up", it is "come back later". The
            // failures nobody can retry their way out of — a refused token, a repository that
            // is gone, somebody else's copy — are FAILED, and say so in a notification.
            VaultRunResult.RETRY -> if (forced) Result.failure() else Result.retry()
            VaultRunResult.FAILED -> Result.failure()
            VaultRunResult.DONE, VaultRunResult.BUSY -> Result.success()
        }
    }

    companion object {
        private const val TAG = "RwilcoVault"
        private const val SCHEDULED = "rwilco-vault"
        private const val FORCED = "rwilco-vault-now"
        private const val KEY_FORCED = "forced"

        /**
         * Books the next copy for when it is due. [replace] moves a booking that already exists
         * — after a run, or when the cadence changes; without it an existing one is left alone,
         * which is what a boot or a launch wants (a retry in flight must keep its place).
         */
        fun schedule(context: Context, state: VaultState, replace: Boolean = false, clock: Clock = Clock.systemUTC()) {
            if (!state.enabled) {
                cancel(context)
                return
            }
            val wait = backupDelay(state.lastRunAt, state.cadence, clock.instant())
            val request = OneTimeWorkRequestBuilder<VaultWorker>()
                .setConstraints(constraints(state.wifiOnly))
                .setInitialDelay(wait.toMinutes().coerceAtLeast(0), TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                SCHEDULED,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
            Log.i(TAG, "next copy in ${wait.toMinutes()} min (${state.cadence})")
        }

        /** Off: nothing runs any more. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(SCHEDULED)
                cancelUniqueWork(FORCED)
            }
        }

        /** Now, because somebody asked: unconstrained, and it fails rather than waits. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<VaultWorker>()
                .setConstraints(Constraints.NONE)
                .setInputData(workDataOf(KEY_FORCED to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(FORCED, ExistingWorkPolicy.REPLACE, request)
        }

        private fun constraints(wifiOnly: Boolean): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        private const val BACKOFF_MINUTES = 5L
    }
}
