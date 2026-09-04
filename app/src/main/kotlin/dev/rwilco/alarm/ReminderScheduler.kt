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
import dev.rwilco.model.DayShape
import dev.rwilco.model.Deadline
import dev.rwilco.model.SafetyNetSettings
import dev.rwilco.model.Trigger
import dev.rwilco.model.dayShape
import dev.rwilco.model.Recurrence
import dev.rwilco.model.AppSettings
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Status
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.Wake
import dev.rwilco.model.hasDeadline
import dev.rwilco.model.missedFire
import dev.rwilco.model.nudgeAt
import dev.rwilco.model.nextWake
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
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

    /** The ids whose net alarm this process holds; for a test to see the net survive a pass. */
    internal val nudgingNow: Set<String> get() = nudging.toSet()

    /** And for the set's deadline — a third PendingIntent, for the same reason the net has one. */
    private val lapsing = Collections.synchronizedSet(mutableSetOf<String>())

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
     *
     * **A missed moment is held, not moved on.** [nextWake] only ever answers with a moment still
     * ahead, so a pass that wrote it back over a moment that had come and not yet rung was
     * spending that moment: two reminders due at nine, the first one's ring re-arming the
     * second while its broadcast was on its way, and the second arriving to a row that said
     * "nothing armed" and being dropped as a stray — a day skipped in silence, or a one-shot
     * that never rang. Passes come from six doors and a delivery is in flight for seconds, so
     * the race was ordinary. The row and its alarm are left exactly as they are: the delivery
     * rings it, or the next catch-up does ([missedFire]). What spends a moment is the ring, a
     * judgement that dropped it (`ReminderFiring.fire`), a "hecho", a "posponer" or an edit —
     * never a pass that merely looked.
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
            // The safety net keeps an alarm of its own, and deliberately does not touch
            // [Reminder.armedFor]: that column means "a firing is owed at this moment", and a
            // net's moment recorded there would have the catch-up RING the reminder rather than
            // whisper about it (missedFire), and spend the moment while it was at it.
            armNudge(reminder, reminder.nudgeAt(now, zone, defaultTime, settings.safetyNet, dayStart, settings.dayShape))
            armLapse(reminder)
            if (missedFire(reminder, now) != null) {
                // Owed and unanswered: held as it stands, alarm included (see the class doc).
                missed += reminder
                continue
            }
            val wake = nextWake(reminder, now, zone, defaultTime, dayStart, settings.dayShape)
            if (wake == null) {
                // The ring alone. A reminder with nothing left to ring is exactly the one the
                // net has a word for — it rang and was let go, or its moment came while a fence
                // was shut — and cancelling both here threw that word away the line after it
                // was armed. The net's alarm answers to nudgeAt and to nothing else.
                cancelRing(reminder.id)
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
        for (id in armed.toList() - seen) cancelRing(id)
        for (id in nudging.toList() - seen) cancelNudge(id)
        for (id in lapsing.toList() - seen) cancelLapse(id)
        Log.i(TAG, "armed ${seen.size} reminders, ${missed.size} missed")
        Diag.note("arm", "armed=${seen.size} missed=${missed.size} exact=${if (canScheduleExact()) "y" else "n"}")
        for (reminder in missed) Diag.note("arm", "r=${reminder.id.take(8)} missed its moment ${reminder.armedFor} (rule ${reminder.armedRule})")
        missed
    }

    private fun cancelRing(id: String) {
        runCatching { alarms.cancel(alarmIntent(id)) }
        armed -= id
    }

    private fun cancelNudge(id: String) {
        runCatching { alarms.cancel(nudgeIntent(id)) }
        nudging -= id
    }

    private fun cancelLapse(id: String) {
        runCatching { alarms.cancel(lapseIntent(id)) }
        lapsing -= id
    }

    /**
     * The deadline's own alarm, at the moment the round runs out, or nothing.
     *
     * Inexact like the net's, and for the same reason: the quietest thing the app does has no
     * business in the system's "next alarm". A few minutes late costs nothing — a window's fence
     * refuses a late event on its own — and one already past is delivered at once, which is how
     * a deadline the phone slept through is applied on the next pass. Not for a round that has
     * rung: the ring clears the moment, so there is simply nothing here to arm.
     */
    private fun armLapse(reminder: Reminder) {
        val at = reminder.expiresAt?.takeIf { reminder.status == Status.ACTIVE && reminder.hasDeadline }
        if (at == null) {
            cancelLapse(reminder.id)
            return
        }
        runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), lapseIntent(reminder.id))
            lapsing += reminder.id
        }.onFailure { Log.e(TAG, "could not arm the deadline of ${reminder.id}", it) }
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
                //
                // And when it refuses — the grant taken away between the check above and this
                // call is a real window on Android 12/13 — the inexact kind goes in instead of
                // nothing: armedFor is already written, and an alarm that arrives late is a
                // missed moment the catch-up can see, while one never set is silence until
                // another door happens to open.
                runCatching {
                    alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), showIntent()), operation)
                }.getOrElse {
                    Log.w(TAG, "exact refused for $id; arming inexactly", it)
                    Diag.note("arm", "r=${id.take(8)} exact refused (${it::class.simpleName}); armed inexactly")
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
                }
            } else {
                // Exact alarms refused: late is better than never, and the Settings card says so.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation)
            }
            armed += id
        }.onFailure {
            Log.e(TAG, "could not arm $id", it)
            Diag.note("arm", "r=${id.take(8)} could NOT be armed (${it::class.simpleName})")
        }
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

    /** The deadline's own, told apart the same way. */
    private fun lapseIntent(id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AlarmReceiver::class.java).setData(lapseUri(id)),
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

        /** The set's deadline running out, under its own URI for the same reason the net has one. */
        fun lapseUri(id: String) = "rwilco://lapse/$id".toUri()

        fun isLapse(intent: Intent): Boolean = intent.data?.host == "lapse"

        fun ruleIndexOf(intent: Intent): Int? = intent.getIntExtra(EXTRA_RULE, -1).takeIf { it >= 0 }

        /**
         * Show this reminder on the alert screen even though it is not awaiting an answer.
         *
         * The screen's rule is that a reminder stays on it only while it is owed an answer
         * ([Reminder.awaitingAnswer]) — that is what takes it down when "hecho" comes from the
         * shade. The safety net's notes are about reminders that are not owed one: one that
         * never rang at all, and one waiting at a place. Opening the screen for those without
         * this would be a tap that flashed and did nothing, so the note says out loud that it
         * knows, and the screen holds the reminder until it is answered here.
         */
        const val EXTRA_ANYWAY = "anyway"

        fun anywayIn(intent: Intent): Boolean = intent.getBooleanExtra(EXTRA_ANYWAY, false)

        /**
         * This start is a card being tapped, not a moment arriving: show the reminder, make no
         * noise.
         *
         * The alert screen builds its own plan from the row, so without this a card read half an
         * hour later started the whole alarm again — and the sound had already been made once
         * when it rang, by that screen or by the notification's own channel. A second one is the
         * app shouting at somebody who is already looking at it.
         *
         * It rides on the card's content intent and nothing else. The full-screen intent IS the
         * moment arriving — the system bringing the screen up on a phone that is asleep or
         * locked — so it carries none of this and rings, which is why the two are separate
         * PendingIntents now (see AlertNotifications.activityIntent).
         */
        const val EXTRA_TAPPED = "tapped"

        fun tappedIn(intent: Intent): Boolean = intent.getBooleanExtra(EXTRA_TAPPED, false)

        /**
         * What the scheduling of a list depends on in the settings; anything else changing must
         * not re-arm it. The net's numbers are here because the nudge is an alarm ([armNudge]):
         * "avísame 36 h después" moved to 12 used to leave every net armed on the old numbers
         * until something else re-armed.
         */
        fun settingsKey(settings: AppSettings): SettingsKey =
            SettingsKey(settings.defaultTime, settings.dayStart, settings.dayShape, settings.safetyNet)

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
            reminder.snoozedToPlace,
            reminder.deadline,
            reminder.expiresAt,
        )
    }

    /** See [settingsKey]. */
    data class SettingsKey(
        val defaultTime: LocalTime,
        val dayStart: LocalTime,
        val dayShape: DayShape,
        val safetyNet: SafetyNetSettings,
    )

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
         * The place a snooze waits at. Arming nothing on the clock is only half of it: the
         * fences and the watch re-read the open list on this key, and that circle has to reach
         * them the moment it is written.
         */
        val snoozedToPlace: Trigger.Location? = null,
        /** The set's deadline and the moment the round under way runs out: the third alarm. */
        val deadline: Deadline? = null,
        val expiresAt: Instant? = null,
    )
}
