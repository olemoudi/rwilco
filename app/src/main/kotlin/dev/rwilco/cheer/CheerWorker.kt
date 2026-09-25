package dev.rwilco.cheer

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.rwilco.RwilcoApplication
import dev.rwilco.diag.Diag
import dev.rwilco.model.awakeAt
import dev.rwilco.model.calmForCheer
import dev.rwilco.model.cheerRetryAt
import dev.rwilco.model.dayShape
import dev.rwilco.model.nextCheerAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The silent word every two or three days (0.151.0; the owner's cadence). A chain of one-time
 * jobs, the way the backup books itself: each run books the next at a minute drawn from the
 * waking hours two or three days on (`nextCheerAt`), so it never lands at the same hour twice.
 *
 * A run first asks whether this is a moment for it at all: switched off, it stops; asleep, or
 * with something waiting for an answer or a routine overdue, it tries again in an hour and a half
 * (`cheerRetryAt`) — praise over an unanswered alarm would be the app not listening. Then it asks
 * for something new and true (`Cheering.notice`), and with nothing to say it says nothing and
 * books the next.
 */
class CheerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as RwilcoApplication
        val now = app.clock.instant()
        // The next booking is made whatever happens in the run: one that threw used to end the
        // chain until the app was next opened. A cancellation is the switch going off, and is heard.
        val next = try {
            runOnce(app, now)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "the word could not be worked out", failure)
            now.plus(Duration.ofDays(1))
        }
        if (next != null) schedule(applicationContext, next, now)
        return Result.success()
    }

    /** One run: says the word if it is a moment for one, and answers when the next run is — null when switched off. */
    private suspend fun runOnce(app: RwilcoApplication, now: Instant): Instant? {
        val settings = app.settingsStore.settings.first()
        if (!settings.cheerNotifications) return null
        val zone = app.clock.zone
        val shape = settings.dayShape
        val random = Random(now.toEpochMilli())
        val calm = shape.awakeAt(now, zone) && calmForCheer(app.repository.openNow(), now)
        if (!calm) {
            val retry = cheerRetryAt(now, zone, shape, random)
            Diag.note(TAG_DIAG, "not now (asleep, or something waiting for an answer), again ${Duration.between(now, retry).toMinutes()} min on")
            return retry
        }
        val next = nextCheerAt(now, zone, shape, random)
        if (!CheerNotice.canPost(applicationContext)) {
            Diag.note(TAG_DIAG, "notifications off or the channel silenced: nothing said, nothing counted")
            return next
        }
        val resources = applicationContext.resources
        val pick = app.cheering.notice(CheerText.variants(resources))
        if (pick != null) CheerNotice.post(applicationContext, CheerText.format(resources, pick))
        Diag.note(TAG_DIAG, if (pick != null) "said ${pick.cheer.kind}" else "nothing new to say")
        return next
    }

    companion object {
        private const val TAG = "RwilcoCheer"
        private const val TAG_DIAG = "cheer"
        private const val WORK = "rwilco-cheer"

        /** The run at [at], replacing whatever was booked. */
        fun schedule(context: Context, at: Instant, now: Instant) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request(Duration.between(now, at)))
        }

        /**
         * Switched on (or the app started with it on): books the first word two or three days out,
         * leaving an existing booking alone — a launch must not keep pushing it away — and makes
         * the channel, so it can be silenced before the first word.
         */
        suspend fun start(app: RwilcoApplication, clock: Clock) {
            CheerNotice.ensureChannel(app)
            val now = clock.instant()
            val shape = app.settingsStore.settings.first().dayShape
            val first = nextCheerAt(now, clock.zone, shape, Random(now.toEpochMilli()))
            WorkManager.getInstance(app).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request(Duration.between(now, first)))
        }

        /** Switched off: nothing booked, and the word in the shade, if any, taken down. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
            CheerNotice.cancel(context)
        }

        private fun request(wait: Duration) = OneTimeWorkRequestBuilder<CheerWorker>()
            .setInitialDelay(wait.toMinutes().coerceAtLeast(0), TimeUnit.MINUTES)
            .build()
    }
}
