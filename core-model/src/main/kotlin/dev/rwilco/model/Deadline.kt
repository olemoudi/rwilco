@file:UseSerializers(LocalTimeSerializer::class)

package dev.rwilco.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * How long a set of rules has to complete before the reminder gives up on it — "el plazo".
 *
 * A set under "todos" remembers what has already happened for ever, and one under "a la vez"
 * waits for the next opening for ever; neither has a way of saying "and if it has not all
 * happened by then, forget it". This is that way, and it is the one thing in the app that
 * finishes a reminder without anybody tapping "hecho". Two shapes, and the discriminators are
 * frozen like a trigger's:
 *
 * - [Window]: hours of the day on the round's day. Only what happens inside them counts, and the
 *   close is the deadline. Under "todos" and "a la vez" alike.
 * - [Timer]: a length counted from the first rule of the round that is a *moment*. Under "todos"
 *   only — "a la vez" has no first trigger, it has an instant at which everything is true.
 *
 * What a deadline does when it passes is [Reminder.lapsed]; when it is taken to apply at all is
 * [Reminder.hasDeadline].
 */
@Serializable
sealed interface Deadline {

    /**
     * Between two times of day on the round's day. A window that ends before it starts crosses
     * midnight, exactly as [Condition.TimeWindow] does, and its day is the day it opened on.
     */
    @Serializable
    @SerialName("window")
    data class Window(val from: LocalTime, val to: LocalTime) : Deadline {
        val crossesMidnight: Boolean get() = to <= from

        /** The window as the fence it is, dated when [date] is given. */
        fun asCondition(date: LocalDate? = null): Condition.TimeWindow = Condition.TimeWindow(from, to, date = date)

        /** The close of this window on the day it opened [openDay]. */
        fun closingOn(openDay: LocalDate, zone: ZoneId): Instant =
            (if (crossesMidnight) openDay.plusDays(1) else openDay).atTime(to).atZone(zone).toInstant()

        /**
         * The first close strictly after [at] — which, for a moment inside the window, is the
         * close of the very occurrence it sits in, and for any other moment the next one.
         */
        fun closingAfter(at: Instant, zone: ZoneId): Instant {
            val day = at.atZone(zone).toLocalDate()
            return (-1L..1L).map { closingOn(day.plusDays(it), zone) }.filter { it > at }.min()
        }

        /** The day a window closing at [closing] opened on: the fence's date. */
        fun openDayOf(closing: Instant, zone: ZoneId): LocalDate {
            val day = closing.atZone(zone).toLocalDate()
            return if (crossesMidnight) day.minusDays(1) else day
        }
    }

    /** So many minutes from the first moment of the round. */
    @Serializable
    @SerialName("timer")
    data class Timer(val minutes: Int) : Deadline
}

/**
 * Whether the deadline on this reminder means anything right now.
 *
 * One rule is one rule and "cualquiera" rings with the first thing that happens, so there is
 * nothing to give up on; a timer under "a la vez" has no first trigger to count from; and once
 * "justo el plazo" has taken the rules out of the loop ([Reminder.spanHasTakenOver]) there is
 * no set left to complete. The value stays on disk through all of that, like [Reminder.ruleMatch]
 * does with one rule, and comes back the moment it means something again.
 */
val Reminder.hasDeadline: Boolean
    get() = deadlineApplies(deadline, rules, ruleMatch) && !spanHasTakenOver

/**
 * The half of [hasDeadline] a draft can answer — whether these rules under this reading have a
 * deadline to speak of — for the editor's sentence and the card, which have no round to ask.
 */
fun deadlineApplies(deadline: Deadline?, rules: List<TriggerRule>, match: RuleMatch): Boolean {
    if (deadline == null || rules.size < 2 || match == RuleMatch.ANY) return false
    return deadline !is Deadline.Timer || match == RuleMatch.ALL
}

/**
 * When a window deadline closes for the round that starts at [from].
 *
 * The round's day is the day of the set's earliest moment counted from [from], walked with the
 * window's hours as a fence on every rule — so a day with no hour opens at the window's opening
 * rather than at breakfast, exactly as a rule's own "y sólo si" hours move it ([openingOf]) —
 * and the deadline is that day's close. A date trigger's date is that day when there is one; a
 * weekly rule written on a Monday gives Friday's close and not Monday's; a daily hour already
 * gone today gives tomorrow's. Every rule is asked, ticked or not, because the day belongs to
 * the round and not to what is left of it. With no moment at all (two places) it is the first
 * close after [from].
 */
fun windowExpiryOf(
    rules: List<TriggerRule>,
    window: Deadline.Window,
    from: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    shape: DayShape = DayShape.DEFAULT,
    reminderId: String = "",
): Instant {
    val fence = window.asCondition()
    val earliest = rules.mapNotNull { rule ->
        val fenced = rule.copy(conditions = rule.conditions + fence)
        when (val next = nextFireOfRule(fenced, reminderId, from, zone, defaultTime, shape)) {
            is NextFire.Scheduled -> next.at
            is NextFire.Sometime -> next.at
            is NextFire.WhenAt, null -> null
        }
    }.minOrNull()
    return window.closingAfter(earliest ?: from, zone)
}

/** [windowExpiryOf] for this reminder's own window, or null when it has none that applies. */
fun Reminder.windowExpiry(
    from: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    shape: DayShape = DayShape.DEFAULT,
): Instant? {
    val window = deadline as? Deadline.Window ?: return null
    if (!hasDeadline) return null
    return windowExpiryOf(rules, window, from, zone, defaultTime, shape, id)
}

/**
 * The window as the fence every rule of the round carries: its hours, on the round's day.
 *
 * Read off [Reminder.expiresAt] rather than stored twice: the close names the day. Without the
 * date it would be every day's hours, and "el 12 a las 19:00, y en casa, de 18 a 22" would tick
 * the place on the 10th and give up on the 10th at ten. Null while no round is under way.
 */
fun Reminder.deadlineFence(zone: ZoneId): Condition.TimeWindow? {
    val window = deadline as? Deadline.Window ?: return null
    val closing = expiresAt ?: return null
    if (!hasDeadline) return null
    return window.asCondition(date = window.openDayOf(closing, zone))
}

/** Whether the deadline has passed on a round that has not rung. */
fun Reminder.expiryDue(now: Instant): Boolean {
    val at = expiresAt ?: return false
    return hasDeadline && at <= now
}

/**
 * Whether something the person did stands in front of the deadline, so that it no longer
 * applies: a ring waiting for an answer, or a snooze not yet rung — "not now, then" said about
 * the reminder is the whole of the answer, and the snooze's own ring rings whatever the set was
 * still waiting for. The deadline is dropped rather than applied, never the other way round.
 */
fun Reminder.deadlineOutranked(now: Instant): Boolean =
    awaitingAnswer(now) || snoozedUntil != null || snoozedToPlace != null

/**
 * When a timer that rule [ruleIndex] happening at [at] starts would run out, or null when it
 * starts nothing: no timer, one already running, or a rule that is a *state*.
 *
 * A state — being at home, it being Friday — is ticked off the moment the phone is seen inside
 * it, which can be days before anything else in the set and is nothing anybody would call "the
 * first trigger". So only a moment starts the clock: an hour, a date, a countdown running out,
 * a doorway crossed.
 */
fun Reminder.timerExpiry(ruleIndex: Int, at: Instant): Instant? {
    val timer = deadline as? Deadline.Timer ?: return null
    if (!hasDeadline || expiresAt != null) return null
    val rule = rules.getOrNull(ruleIndex) ?: return null
    if (!rule.trigger.isMoment) return null
    return at.plusSeconds(timer.minutes * 60L)
}
