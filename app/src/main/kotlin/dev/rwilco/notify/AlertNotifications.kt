package dev.rwilco.notify

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.rwilco.R
import dev.rwilco.alarm.AlertActionReceiver
import dev.rwilco.alarm.ReminderScheduler
import dev.rwilco.ui.alert.AlertActivity
import dev.rwilco.model.AppSettings
import dev.rwilco.model.FiringPlan
import dev.rwilco.ui.format.summaryLine
import dev.rwilco.model.NetWord
import dev.rwilco.model.Reminder
import dev.rwilco.model.AlertSound
import dev.rwilco.model.Snooze
import dev.rwilco.model.VibrationPattern
import dev.rwilco.model.key
import dev.rwilco.model.notificationPattern
import dev.rwilco.ui.theme.AMBER_ARGB
import java.time.LocalTime
import java.time.Instant
import dev.rwilco.model.DEFAULT_SNOOZE_MINUTES
import dev.rwilco.model.NOTIFICATION_SNOOZES
import dev.rwilco.ui.format.snoozeLabel
import dev.rwilco.model.notificationSnoozeOffers

/**
 * How many alerts the bundle actually has: what the system lists, plus or minus the one this
 * call has just posted or cancelled.
 *
 * The system's list is what it has got round to. A cancel is handed to a thread of its own and
 * is not done when the call returns, so asking straight afterwards can still be told about the
 * notification on its way out — and the summary posted for it then stayed in the shade on its
 * own, an empty line reading "1 recordatorio" over nothing, to be swiped away by hand. The id
 * this call is about is therefore counted from what we did, never from what the list says
 * about it.
 */
internal fun bundleChildren(listed: List<Int>, posted: Int?, cancelled: Int?): Int {
    val ids = listed.toMutableSet()
    if (cancelled != null) ids -= cancelled
    if (posted != null) ids += posted
    return ids.size
}

/**
 * The notification a firing leaves behind, and the channels it needs.
 *
 * A channel's sound and vibration are fixed the moment it is created and can never be changed
 * afterwards, so there is one channel per combination the app can ask for rather than one
 * channel edited in place. The `_v1` in the ids is the way out if the sound ever has to change:
 * bump it and the new channels are created alongside.
 *
 * Which is also how the vibration setting reaches a notification: the chosen rhythm is part of
 * the id, so changing it means a different channel rather than an edit Android would ignore.
 * Only the rhythm — a channel's pattern is durations and nothing else, with no way to say how
 * hard, so gentle and strong are the same notification. The strength is honoured on the
 * full-screen alert, which drives the motor itself.
 */
object AlertNotifications {

    private const val GROUP = "alerts"

    /**
     * The bundle every alert joins, and the id of the one line that stands for the bundle.
     *
     * Not the same thing as [GROUP], which is the *channel* group — the heading in the system's
     * own settings. This is the shade's own bundling: five reminders that come due while
     * somebody is out should be one thing to pull down, not five things to scroll past.
     */
    private const val BUNDLE = "dev.rwilco.alerts"
    private const val SUMMARY_ID = 1
    // v2: every alert channel carries alarm audio attributes, silent ones included, and the
    // live alert is CATEGORY_ALARM — which is what lets Do Not Disturb tell an alarm from a chat.
    private const val VERSION = "v2"
    const val CHANNEL_MISSED = "missed_$VERSION"

    /**
     * The safety net's channel: the quietest thing the app has. IMPORTANCE_LOW, so it does not
     * peek over what somebody is doing — it is a word about something that already happened,
     * and if it interrupted anything it would be the alarm it is trying not to be.
     */
    const val CHANNEL_NET = "net_$VERSION"

    fun ensureChannels(context: Context, vibration: VibrationPattern = VibrationPattern(), chosen: AlertSound = AlertSound.System) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // A channel's tone is played by the system, and one of our own copies lives where the
        // system cannot reach. The grant does not survive a reboot, so it is re-done here rather
        // than once when the sound was chosen. See SoundStore.
        Sounds.uri(context, chosen)?.let { SoundStore.grantToSystem(context, it) }
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP, context.getString(R.string.notif_group_alerts)),
        )
        // With notification-policy access granted the channels are made to bypass Do Not
        // Disturb outright; without it the alarm attributes below are what get them through.
        val bypass = manager.isNotificationPolicyAccessGranted
        for (sound in listOf(false, true)) {
            for (vibrate in listOf(false, true)) {
                manager.createNotificationChannel(alertChannel(context, sound, vibrate, vibration, chosen, bypass))
            }
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MISSED, context.getString(R.string.notif_channel_missed), NotificationManager.IMPORTANCE_DEFAULT).apply {
                group = GROUP
                description = context.getString(R.string.notif_channel_missed_description)
                setSound(null, null)
                enableVibration(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_NET, context.getString(R.string.notif_channel_net), NotificationManager.IMPORTANCE_LOW).apply {
                group = GROUP
                description = context.getString(R.string.notif_channel_net_description)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    fun post(
        context: Context,
        reminder: Reminder,
        plan: FiringPlan,
        late: Instant?,
        fullScreen: Boolean = plan.fullScreen,
        vibration: VibrationPattern = VibrationPattern(),
        chosen: AlertSound = AlertSound.System,
        ruleIndex: Int? = null,
        /** The hour a bare date rings at, which is the one thing the reason line cannot read off the rule. */
        defaultTime: LocalTime = AppSettings().defaultTime,
        /** The two snooze offers the buttons carry (three actions is the cap, and "hecho" is one), and how long the custom one is. */
        snoozes: List<Snooze> = AppSettings().notificationSnoozeOffers,
        customMinutes: Int = DEFAULT_SNOOZE_MINUTES,
        /**
         * The safety net's word about a reminder that got away, and which way it got away: the
         * same card, on the quietest channel there is, saying so in its own line. Never a
         * screen, never a sound, never pinned — everything that makes an alarm an alarm is
         * exactly what this is not.
         */
        nudge: NetWord? = null,
        /** The moment that word is about, which its clock counts up from. */
        nudgeAbout: Instant? = null,
    ) {
        // A file that will not open right now plays as the system tone (Sounds.uri), and the
        // channel has to be NAMED for the tone it will actually carry: a channel's sound is
        // fixed the moment it is created, so one made under the file's own key while the file
        // was away would keep the system tone for ever, card back in or not.
        val effective = if (chosen is AlertSound.Custom && !Sounds.readable(context, Uri.parse(chosen.uri))) AlertSound.System else chosen
        ensureChannels(context, vibration, effective)
        // The noise follows where the firing is actually shown, not what was ticked: a
        // full-screen alert rings for itself, but one that ends up as a banner — an app in
        // front, usage access never granted — has no screen to ring, and a silent banner is a
        // reminder somebody sleeps through.
        val soundHere = plan.sound && !fullScreen
        val vibrateHere = plan.vibrate && !fullScreen
        val bypass = context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
        val channel = when {
            nudge != null -> CHANNEL_NET
            late != null -> CHANNEL_MISSED
            else -> channelId(soundHere, vibrateHere, vibration, effective, bypass)
        }
        val open = activityIntent(context, reminder.id, ruleIndex)
        // **Why it rang**, in the words the form used when it was written — not the reminder's
        // own text again, which the title already carries and which said nothing twice. The
        // sentence is the editor's own, minus the words themselves (`reminderSummary`).
        val reason = reminder.summaryLine(context, defaultTime)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason.ifBlank { reminder.text }))
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            // The amber, which is the app's one word for "this is what fires next" — said here
            // on the glyph and on the line that carries the app's name, which is all of a
            // notification the system lets an app colour.
            .setColor(AMBER_ARGB)
            .setGroup(BUNDLE)
            // The children make the noise and the summary never does. Without this the bundle
            // announces itself as well, which is the same alarm twice.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            // When it rang, said outright. On a missed one it counts up instead — "this should
            // have reached you an hour and ten minutes ago" is the whole point of the missed
            // notification, and a bare timestamp makes somebody work it out.
            .setShowWhen(true)
            // A net's word counts up from the ring it is about — "hace un día" is the whole of
            // what it has to say, and a bare timestamp makes somebody work it out.
            .setWhen((late ?: nudgeAbout ?: Instant.now()).toEpochMilli())
            .setUsesChronometer(late != null || nudge != null)
            // An alarm to the system, not a reminder: Do Not Disturb lets alarms through by
            // default and holds reminders back by default, and this is the one that must arrive.
            .setCategory(if (late != null || nudge != null) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_ALARM)
            .setPriority(
                when {
                    nudge != null -> NotificationCompat.PRIORITY_LOW
                    late != null -> NotificationCompat.PRIORITY_DEFAULT
                    else -> NotificationCompat.PRIORITY_HIGH
                },
            )
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, context.getString(R.string.alert_done), actionIntent(context, reminder.id, AlertActionReceiver.ACTION_DONE, null))
        // Three is what a notification shows, so two snoozes — which two is a setting; the rest
        // of the offers are on the alert screen, which the banner opens.
        for (snooze in snoozes.take(NOTIFICATION_SNOOZES)) {
            builder.addAction(
                0,
                snoozeLabel(context, snooze, customMinutes),
                actionIntent(context, reminder.id, AlertActionReceiver.ACTION_SNOOZE, snooze),
            )
        }
        // Collapsed, a notification shows one line under the title, and the reason is what that
        // line is for. The tags are the reminder's own filing and go beside the app's name, where
        // a label belongs — and give it up to a word the net or a missed ring has to say, which
        // is about this arrival rather than about the reminder.
        if (reason.isNotBlank()) builder.setContentText(reason)
        if (reminder.tags.isNotEmpty()) builder.setSubText(reminder.tags.joinToString(" · "))
        when {
            nudge == NetWord.LET_GO -> builder.setSubText(context.getString(R.string.notif_net_subtext))
            // The other way one gets away, and a different thing to be told: this one never
            // reached you at all, because its moment came while something was shut.
            nudge == NetWord.NEVER_RANG -> builder.setSubText(context.getString(R.string.notif_net_subtext_never))
            late != null -> builder.setSubText(context.getString(R.string.alert_missed_subtext))
        }
        // A full-screen alert is a request, not a promise: the system may refuse it (since
        // Android 14 it is for calls and alarms unless the person says otherwise), and then this
        // is simply a heads-up notification with the same buttons. [fullScreen] is the caller's
        // own decision on top of that — see AlertPresenter: an app open in front of somebody
        // gets the banner and nothing else.
        if (fullScreen && late == null && nudge == null) builder.setFullScreenIntent(open, true)
        // "Hasta que reciba caso" means what it says: this one cannot be flicked away in the
        // half-asleep swipe that clears the shade. Everything else stays swipeable, because
        // most reminders are read and let go and pinning those would be nagging.
        if (plan.insistent && late == null && nudge == null) builder.setOngoing(true)
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(reminder.id), builder.build())
            syncSummary(context, posted = notificationId(reminder.id))
        }
    }

    fun cancel(context: Context, reminderId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationId(reminderId))
            syncSummary(context, cancelled = notificationId(reminderId))
        }
    }

    /**
     * The one line that stands for the bundle, kept saying the truth about what is under it.
     *
     * **It is taken down only when there is nothing left**, and that is not a style choice:
     * cancelling a group's summary takes the group's surviving children with it. Pulling it at
     * "fewer than two", which is the reading that sounds right, cleared the shade of the alert
     * somebody still had to deal with — found by [NotificationBundleTest], which now holds the
     * door shut.
     *
     * A single child under a summary is not a problem to solve either: Android draws that group
     * as the child alone. So the rule is the simple one — say how many there are while there are
     * any — and the count comes from what is actually posted, since a notification can go by
     * being swiped as well as by [cancel], corrected for the one this call has just changed
     * ([bundleChildren]).
     */
    private fun syncSummary(context: Context, posted: Int? = null, cancelled: Int? = null) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val listed = manager.activeNotifications.filter { it.id != SUMMARY_ID && it.notification.group == BUNDLE }.map { it.id }
        val children = bundleChildren(listed, posted, cancelled)
        if (children == 0) {
            NotificationManagerCompat.from(context).cancel(SUMMARY_ID)
            return
        }
        val summary = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(AMBER_ARGB)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notif_summary, children, children))
            .setGroup(BUNDLE)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        NotificationManagerCompat.from(context).notify(SUMMARY_ID, summary)
    }

    fun notificationId(reminderId: String): Int = reminderId.hashCode()

    private fun channelId(sound: Boolean, vibrate: Boolean, vibration: VibrationPattern, chosen: AlertSound, bypass: Boolean): String {
        // Each part only belongs in the id of a channel it can actually change: a silent channel
        // would otherwise get one id per tone nobody is going to hear, and a still one per
        // rhythm nobody is going to feel. Bypassing Do Not Disturb is fixed at creation like
        // the rest, so it is part of the id too: granting the access makes new channels.
        val tone = if (sound) "_${chosen.key}" else ""
        val rhythm = if (vibrate) "_${vibration.rhythm.name.first().lowercase()}" else ""
        val dnd = if (bypass) "_dnd" else ""
        return "alert_${VERSION}_s${if (sound) 1 else 0}_v${if (vibrate) 1 else 0}$tone$rhythm$dnd"
    }

    /** The one id prefix every alert channel shares, for the Settings card's mute check. */
    const val ALERT_CHANNEL_PREFIX = "alert_"

    private fun alertChannel(context: Context, sound: Boolean, vibrate: Boolean, vibration: VibrationPattern, chosen: AlertSound, bypass: Boolean): NotificationChannel {
        val nameRes = when {
            sound && vibrate -> R.string.notif_channel_sound_vibrate
            sound -> R.string.notif_channel_sound
            vibrate -> R.string.notif_channel_vibrate
            else -> R.string.notif_channel_quiet
        }
        // Alarm usage on purpose, on every one of them: somebody who ticked "Sonido" means to
        // hear it, and a notification tone at notification volume is exactly what a phone
        // face-down on a table swallows. The silent channels carry the same attributes with no
        // tone — that is what makes their buzz an alarm's buzz to Do Not Disturb and to the
        // ringer switch, rather than a notification's, which both of them drop first.
        val alarm = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        return NotificationChannel(channelId(sound, vibrate, vibration, chosen, bypass), context.getString(nameRes), NotificationManager.IMPORTANCE_HIGH).apply {
            group = GROUP
            setSound(if (sound) Sounds.uri(context, chosen) else null, alarm)
            if (bypass) setBypassDnd(true)
            enableVibration(vibrate)
            if (vibrate) vibrationPattern = notificationPattern(vibration).toLongArray()
        }
    }


    /** The rule rides as an extra: not part of the identity, refreshed by FLAG_UPDATE_CURRENT. */
    private fun activityIntent(context: Context, reminderId: String, ruleIndex: Int?): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, AlertActivity::class.java)
            .setData(ReminderScheduler.reminderUri(reminderId))
            .apply { if (ruleIndex != null) putExtra(ReminderScheduler.EXTRA_RULE, ruleIndex) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(context: Context, reminderId: String, action: String, snooze: Snooze?): PendingIntent {
        val intent = Intent(context, AlertActionReceiver::class.java)
            .setAction(action)
            // The action is part of what tells two PendingIntents apart; the data is what tells
            // two reminders apart. Both, or "Hecho" on one reminder finishes another.
            .setData(ReminderScheduler.reminderUri(reminderId))
        if (snooze != null) intent.putExtra(AlertActionReceiver.EXTRA_SNOOZE, snooze.name)
        return PendingIntent.getBroadcast(
            context,
            // Which snooze it is lives in an extra, and extras are NOT part of what makes two
            // PendingIntents the same — two snooze buttons on one notification would be one
            // PendingIntent, and FLAG_UPDATE_CURRENT would quietly make both of them the last
            // one built. The request code is part of the identity, so it carries the difference.
            snooze?.let { it.ordinal + 1 } ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
