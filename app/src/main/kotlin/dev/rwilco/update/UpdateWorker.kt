package dev.rwilco.update

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.rwilco.RwilcoApplication
import dev.rwilco.diag.Diag
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Runs the update check off the main thread, retrying transient failures with backoff. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val stagedOnly = inputData.getBoolean(KEY_STAGED_ONLY, false)
        // "Only on wifi" is asked here rather than left to the constraint alone, because the
        // one-off checks — a launch, a boot — are enqueued before anybody knows what network
        // the phone will be on when they run. A tap on "Buscar ahora" says so and goes anyway.
        if (!stagedOnly && !inputData.getBoolean(KEY_MANUAL, false) && onSomebodyElsesData()) {
            Log.i(TAG, "skipping the update check: mobile data, and updates are set to wifi only")
            Diag.note("update", "check skipped: metered network, wifi only")
            return Result.success()
        }
        val updater = Updater(applicationContext)
        val outcome = if (stagedOnly) updater.installStaged() else updater.checkAndUpdate()
        Diag.note("update", "outcome=$outcome${if (stagedOnly) " (staged)" else ""}")
        return when (outcome) {
            UpdateCheckOutcome.TRANSIENT_FAILURE ->
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }

    /** Metered, and the person asked for wifi only. Either half false and the check goes ahead. */
    private suspend fun onSomebodyElsesData(): Boolean {
        val app = applicationContext as? RwilcoApplication ?: return false
        val wifiOnly = runCatching { app.settingsStore.settings.first().updatesWifiOnly }.getOrDefault(false)
        if (!wifiOnly) return false
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.isActiveNetworkMetered
    }

    companion object {
        private const val TAG = "RwilcoUpdater"
        private const val PERIODIC = "rwilco-update-periodic"
        private const val MAX_RETRIES = 5
        private const val KEY_STAGED_ONLY = "staged_only"
        private const val KEY_MANUAL = "manual"

        /** Minimum spacing between focus-triggered checks, so regaining focus repeatedly doesn't hammer GitHub. */
        private const val FOCUS_GUARD_MILLIS = 15 * 60 * 1000L
        private val lastEnqueueMs = AtomicLong(0)
        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        private val unmetered = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

        /** Idempotent periodic check (~ every 12h). */
        fun schedule(context: Context, wifiOnly: Boolean = false) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(if (wifiOnly) unmetered else connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** One-off immediate check (app launch / boot). Bypasses the guard. */
        fun runNow(context: Context) {
            lastEnqueueMs.set(SystemClock.elapsedRealtime())
            enqueue(context, connected, Data.EMPTY)
        }

        /**
         * The manual "check now" from settings. Unconstrained on purpose: a tap made with no
         * signal should fail visibly within a second, not be queued into a silence
         * indistinguishable from a button that does nothing. It ignores the wifi rule for the
         * same reason — the person is standing there asking.
         */
        fun checkNow(context: Context) {
            lastEnqueueMs.set(SystemClock.elapsedRealtime())
            enqueue(context, Constraints.NONE, workDataOf(KEY_MANUAL to true))
        }

        /** Installs the APK already in the cache. Needs no network, so it asks for none. */
        fun installStagedNow(context: Context) {
            enqueue(context, Constraints.NONE, workDataOf(KEY_STAGED_ONLY to true))
        }

        /** Focus-triggered check: runs at most once per guard window. */
        fun runIfStale(context: Context) {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val last = lastEnqueueMs.get()
                if (last != 0L && now - last < FOCUS_GUARD_MILLIS) return
                if (lastEnqueueMs.compareAndSet(last, now)) break
            }
            runNow(context)
        }

        private fun enqueue(context: Context, constraints: Constraints, input: Data) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(constraints)
                .setInputData(input)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
