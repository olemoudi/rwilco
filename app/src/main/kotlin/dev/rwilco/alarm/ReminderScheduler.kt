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
import dev.rwilco.diag.Diag
import dev.rwilco.model.dayShape
import dev.rwilco.model.Recurrence
import dev.rwilco.model.AppSettings
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Status
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.Wake
import dev.rwilco.model.missedFire
import dev.rwilco.model.nudgeAt
import dev.rwilco.model.nextWake
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** The same, for the safety net's own alarm — a second one, on its own PendingIntent. */
    private val nudging = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * One pass at a time. A pass is a read of every row, a decision each, and a write of the
     * armed moment plus the alarm itself — and passes come from six doors (the launch, the
     * collector on every save, the settings, the editor, the safety net, a grant changing).
     * Two side by side could read the row before an edit and after it, and the one that read
     * it before could write last: the row and the alarm then named a moment somebody had just
     * edited away, and the moment they meant waited for the next pass. Never called back from
     * anything that holds another lock, so there is no order to get wrong.
     */
    private val lock = Mutex()

    /**
     * Re-arms everything and returns the reminders whose moment came and went unheard, for the
     * caller to deal with (only boot and the safety-net worker do; a plain edit does not).
     */
    suspend fun rearmAll(): List<Reminder> = lock.withLock {
        // The defaults are a fine way to arm; an exception here was the one thing that could
        // stop every reminder being armed at once (see RwilcoApplication.appScope).
        val settings = runCatching { settingsStore.settings.first() }.getOrElse {
            Log.e(TAG, "settings would not read; arming with the defaults", it)
            AppSettings()
        }
        val defaultTime = settings.defaultTime
        val dayStart = settings.dayStart
        val now = clock.instant()
        val zone = clock.zone
        val open = runCatching { repository.openNow() }.getOrElse {
            Log.e(TAG, "could not read the reminders to arm", it)
            return@withLock emptyList()
        }
        val missed = ArrayList<Reminder>()
        val seen = HashSet<String>(open.size)
        for (reminder in open) {
            seen += reminder.id
            if (missedFire(reminder, now) != null) missed += reminder
            // The safety net keeps an alarm of its own, and deliberately does not touch
            // [Reminder.armedFor]: that column means "a firing is owed at this moment", and a
            // net's moment recorded there would have the catch-up RING the reminder rather than
            // whisper about it (missedFire), and spend the moment while it was at it.
            armNudge(reminder, reminder.nudgeAt(now, zone, defaultTime, settings.safetyNet, dayStart, settings.dayShape))
            val wake = nextWake(reminder, now, zone, defaultTime, dayStart, settings.dayShape)
            if (wake == null) {
                cancel(reminder.id)
                if (reminder.armedFor != null) repository.setArmedFor(reminder.id, null, null)
            } else {
                // The row first, the alarm second: an alarm for a moment already past arrives at
                // once, and a firing that read the row before this write found "nothing armed"
                // and dropped the ring.
                if (reminder.armedFor != wake.at || reminder.armedRule != wake.ruleIndex) {
                    runCatching { repository.setArmedFor(reminder.id, wake.at, wake.ruleIndex) }
                        .onFailure { Log.e(TAG, "could not write the armed moment of ${reminder.id}", it) }
                }
                arm(reminder.id, wake)
            }
        }
        // Whatever was armed and is no longer open (done, deleted) loses its alarm. A process
        // restart empties this set, so a stale alarm can still be delivered once; what stops it
        // ringing is the armed-moment check in ReminderFiring.fire, not this list.
        for (id in armed.toList() - seen) cancel(id)
        for (id in nudging.toList() - seen) cancelNudge(id)
        Log.i(TAG, "armed ${seen.size} reminders, ${missed.size} missed")
        Diag.note("arm", "armed=${seen.size} missed=${missed.size} exact=${if (canScheduleExact()) "y" else "n"}")
        for (reminder in missed) Diag.note("arm", "r=${reminder.id.take(8)} missed its moment ${reminder.armedFor} (rule ${reminder.armedRule})")
        missed
    }

    fun cancel(id: String) {
        runCatching { alarms.cancel(alarmIntent(id)) }
        armed -= id
        cancelNudge(id)
    }

    private fun cancelNudge(id: String) {
        runCatching { alarms.cancel(nudgeIntent(id)) }
        nudging -= id
    }

    /**
     * The safety net's own alarm, [at] or nothing.
     *
     * **Inexact on purpose** (`setAndAllowWhileIdle`): a word said a quarter of an hour late is
     * the same word, and the exact kind is `setAlarmClock`, which puts an alarm icon in the
     * status bar and a time in the system's "next alarm" — announcing, in the loudest surface
     * the phone has, the quietest thing this app does.
     */
    private fun armNudge(reminder: Reminder, at: Instant?) {
        if (at == null) {
            cancelNudge(reminder.id)
            return
        }
        runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), nudgeIntent(reminder.id))
            nudging += reminder.id
        }.onFailure { Log.e(TAG, "could not arm the safety net of ${reminder.id}", it) }
    }

    private fun arm(id: String, wake: Wake) {
        val at = wake.at
        val operation = alarmIntent(id, wake.ruleIndex)
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
     *
     * Which rule the moment belongs to therefore travels as an extra, not in the URI: that keeps
     * the identity stable (so [cancel] still matches what [arm] set) while FLAG_UPDATE_CURRENT
     * refreshes the extra on every re-arm.
     */
    private fun alarmIntent(id: String, ruleIndex: Int? = null): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setData(reminderUri(id))
        if (ruleIndex != null) intent.putExtra(EXTRA_RULE, ruleIndex)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The net's own, told apart by its own URI so the two can be armed at once: a reminder that
     * rang and was ignored is often waiting for both — the next ring, and the word about the
     * one that went unanswered.
     */
    private fun nudgeIntent(id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AlarmReceiver::class.java).setData(nudgeUri(id)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "RwilcoAlarms"

        /** Which rule the alarm was armed for; absent means "the moment is the ring itself". */
        const val EXTRA_RULE = "rule"

        fun reminderUri(id: String) = "rwilco://reminder/$id".toUri()

        /** The safety net's own alarm, so it never replaces the one that actually rings. */
        fun nudgeUri(id: String) = "rwilco://nudge/$id".toUri()

        fun reminderIdOf(intent: Intent): String? = intent.data?.lastPathSegment

        /** Whether this alarm is the net's quiet word rather than the reminder's own moment. */
        fun isNudge(intent: Intent): Boolean = intent.data?.host == "nudge"

        fun ruleIndexOf(intent: Intent): Int? = intent.getIntExtra(EXTRA_RULE, -1).takeIf { it >= 0 }

        /** What the scheduling of a list depends on; anything else changing must not re-arm it. */
        fun schedulingKey(reminder: Reminder): SchedulingKey = SchedulingKey(
            reminder.id,
            reminder.status,
            reminder.rules,
            reminder.ruleMatch,
            reminder.firedRules,
            reminder.snoozedUntil,
            reminder.recurrence,
            reminder.lastDealtAt,
            reminder.safetyNet,
        )
    }

    data class SchedulingKey(
        val id: String,
        val status: Status,
        val rules: List<TriggerRule>,
        val ruleMatch: RuleMatch,
        /** Ticking a rule off moves the armed moment on to the next one, so it belongs here. */
        val firedRules: Set<Int>,
        val snoozedUntil: Instant?,
        /**
         * A reminder with no trigger at all rings by its recurrence and by nothing else, so
         * asking for one has to re-arm — and it is the only edit that changes nothing else.
         */
        val recurrence: Recurrence,
        /**
         * And the moment that recurrence counts from. Dealing with a firing re-arms on its own
         * way out, but undoing one does not: it puts the whole row back as it was, and on a
         * reminder that stayed ACTIVE either side of the "hecho" nothing else in this key
         * moves — so the alarm would still be set for the round that was just taken back.
         */
        val lastDealtAt: Instant?,
        /**
         * Whether the safety net is asked for. Only the switch, and deliberately not the two
         * moments it is worked out from ([Reminder.lastFiredAt], [Reminder.nudgedAt]): those are
         * written by a firing, and everything that writes one — the ring, the "hecho", the
         * snooze, the net's own word — calls the scheduler on its way out already. Putting them
         * here would put every ring through a second full pass to arrive at the same answer,
         * which is the rule this key exists to keep.
         */
        val safetyNet: Boolean,
    )
}
