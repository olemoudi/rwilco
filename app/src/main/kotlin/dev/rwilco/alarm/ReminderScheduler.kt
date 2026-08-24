package dev.rwilco.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.net.toUri
import dev.rwilco.MainActivity
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.NextFire
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.missedFire
import dev.rwilco.model.nextFire
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.util.Collections

/**
 * Keeps one alarm armed per reminder: the next moment it has to ring.
 *
 * One alarm each, rather than a single alarm for whichever is soonest, because they are then
 * independent — a reminder that fails to re-arm cannot take the rest of the list down with it —
 * and because cancelling one is just cancelling one.
 *
 * The armed moment is written back to the row ([Reminder.armedFor]). That is what makes a
 * firing the phone slept through detectable at all: an armed moment in the past with no ring
 * to match it.
 */
class ReminderScheduler(
    private val context: Context,
    private val repository: ReminderRepository,
    private val settingsStore: SettingsStore,
    private val clock: Clock,
) {

    private val alarms = context.getSystemService(AlarmManager::class.java)

    /** Ids this process has armed, so a reminder that leaves the list gets its alarm cancelled. */
    private val armed = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Re-arms everything and returns the reminders whose moment came and went unheard, for the
     * caller to deal with (only boot and the safety-net worker do; a plain edit does not).
     */
    suspend fun rearmAll(): List<Reminder> {
        val defaultTime = settingsStore.settings.first().defaultTime
        val now = clock.instant()
        val zone = clock.zone
        val open = runCatching { repository.openNow() }.getOrElse {
            Log.e(TAG, "could not read the reminders to arm", it)
            return emptyList()
        }
        val missed = ArrayList<Reminder>()
        val seen = HashSet<String>(open.size)
        for (reminder in open) {
            seen += reminder.id
            if (missedFire(reminder, now) != null) missed += reminder
            val at = nextMoment(reminder, now, zone, defaultTime)
            if (at == null) {
                cancel(reminder.id)
                if (reminder.armedFor != null) repository.setArmedFor(reminder.id, null)
            } else {
                arm(reminder.id, at)
                if (reminder.armedFor != at) repository.setArmedFor(reminder.id, at)
            }
        }
        // Whatever was armed and is no longer open (done, deleted) loses its alarm. A process
        // restart empties this set, so a stale alarm can still fire once — the receiver finds
        // nothing to ring about and lets it go.
        for (id in armed.toList() - seen) cancel(id)
        Log.i(TAG, "armed ${seen.size} reminders, ${missed.size} missed")
        return missed
    }

    fun cancel(id: String) {
        runCatching { alarms.cancel(alarmIntent(id)) }
        armed -= id
    }

    /** The moment the alarm clock is for; a place is the geofence's business, not this one's. */
    private fun nextMoment(reminder: Reminder, now: Instant, zone: java.time.ZoneId, defaultTime: java.time.LocalTime): Instant? =
        when (val next = nextFire(reminder, now, zone, defaultTime)) {
            is NextFire.Scheduled -> next.at
            is NextFire.Sometime -> next.at
            is NextFire.WhenAt, null -> null
        }

    private fun arm(id: String, at: Instant) {
        val operation = alarmIntent(id)
        runCatching {
            if (canScheduleExact()) {
                // setAlarmClock, not setExactAndAllowWhileIdle: it is the only kind of alarm Doze
                // never defers and the rate limiter never holds back, and the system's "next
                // alarm" then tells the truth about what this phone is going to do next.
                alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), showIntent()), operation)
            } else {
                // Exact alarms refused: late is better than never, and the Settings card says so.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
            }
            armed += id
        }.onFailure { Log.e(TAG, "could not arm $id", it) }
    }

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    /** Where the system's alarm-clock surface sends you when you tap it. */
    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * One PendingIntent per reminder, told apart by the data URI — extras are not part of what
     * makes two intents the same, so without it every reminder would share (and overwrite) one
     * alarm.
     */
    private fun alarmIntent(id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AlarmReceiver::class.java).setData(reminderUri(id)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "RwilcoAlarms"

        fun reminderUri(id: String) = "rwilco://reminder/$id".toUri()

        fun reminderIdOf(intent: Intent): String? = intent.data?.lastPathSegment

        /** What the scheduling of a list depends on; anything else changing must not re-arm it. */
        fun schedulingKey(reminder: Reminder): SchedulingKey =
            SchedulingKey(reminder.id, reminder.status, reminder.rules, reminder.snoozedUntil)
    }

    data class SchedulingKey(
        val id: String,
        val status: Status,
        val rules: List<TriggerRule>,
        val snoozedUntil: Instant?,
    )
}
