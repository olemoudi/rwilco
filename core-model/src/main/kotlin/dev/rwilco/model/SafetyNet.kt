package dev.rwilco.model

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The safety net: one quiet word about a reminder that got away.
 *
 * Everything else in this app is about a moment arriving. This is about the two ways one fails
 * to. **It was let go** — the notification swiped off the lock screen at a traffic light, the
 * alert read while both hands were full — or **it never reached you at all**: the moment came
 * while a fence was shut ("y sólo si estoy en casa", a set under "a la vez" whose halves never
 * held together), and now there is no moment left for it to ring at. Either way the reminder is
 * sitting in "vencidos" saying nothing, for ever, and the app that took the trouble to work all
 * that out says nothing either.
 *
 * One net for both, because they are one question — *avísame si esto se me escapa* — and asking
 * it twice would be asking somebody to know in advance which way it was going to escape.
 *
 * **And it is not asked for at all.** It began as a switch on each reminder, which was a switch
 * about the one thing nobody can predict: which of your reminders is going to be the one that
 * gets away. Anybody who could answer that in advance would not need the net. So it holds for
 * everything, and what is left to decide is only how long it waits — three numbers in Settings,
 * answered once. The editor says what they come to for the reminder being written, which is the
 * only place that answer means anything concrete.
 *
 * It speaks **once** per moment ([Reminder.nudgedAt]) — a net that keeps nagging is an alarm
 * again, which is exactly the thing somebody chose to let go of. And it speaks **at any hour**:
 * a silent notification on a low channel wakes nobody, so holding it back until morning would
 * only make it later without making it quieter.
 *
 * **What it waits for depends on whether the reminder is coming back:**
 *
 * - Nothing left to ring (a date that has been, a countdown that ran out): the whole wait,
 *   [SafetyNetSettings.afterHours] — a day by default. There is no rush; nothing else is going
 *   to happen, and a day is the length of "I meant to do that this morning".
 * - Coming back on its own: a **tenth** of the gap between one ring and the next
 *   ([SafetyNetSettings.fraction]), or the whole wait, whichever is **shorter**. The point is to
 *   catch it before the next one arrives and buries it, and the tenth is what keeps that
 *   proportional — 2 h 24 min on something daily, a quarter of an hour on something six-hourly.
 * - Faster than [SafetyNetSettings.minCadenceMinutes] between rings, an hour by default: the net
 *   cannot be armed at all. A reminder that comes back every twenty minutes needs no net; the
 *   next one *is* the net, and a second notification between two of them is noise.
 *
 * A place is treated as having nothing next, because nothing can say when somebody will be back.
 */
/**
 * How loud the net's word is, and when it is allowed to be loud at all.
 *
 * The net was mute, and its licence to speak **at any hour** was built on that: a silent card on
 * a low channel wakes nobody, so holding it back until morning would have made it later without
 * making it quieter. A word nobody hears is also a word that does not do its job, so it makes a
 * noise now — the ordinary, non-insistent tone, at half the volume an alarm gets ([NET_GAIN]),
 * which is the app saying "this is not the alarm" in the only unit a sound has.
 *
 * And the licence has to be paid for. Half of an alarm at three in the morning is still an alarm
 * at three in the morning, so the noise is kept to the hours this person is up ([DayShape]) and
 * outside them the card is posted exactly as mute as it always was. Nothing is lost by that: the
 * word is in the shade either way, and the whole point of the net is that it is about something
 * which has *already* got away.
 */
fun netSpeaksAloud(at: Instant, zone: ZoneId, shape: DayShape): Boolean = shape.awakeAt(at, zone)

/**
 * Half. Amplitude, which is what a volume control is: the net's word is exactly half of the
 * alarm's, rather than half of how loud it *feels* — a perceptual half would be a quarter of
 * this and inaudible in a room with anything else going on.
 */
const val NET_GAIN: Float = 0.5f

@Serializable
data class SafetyNetSettings(
    /** The longest it ever waits, and the whole wait when nothing is coming back. */
    val afterHours: Int = DEFAULT_AFTER_HOURS,
    /** Rings closer together than this cannot carry a net at all. */
    val minCadenceMinutes: Int = DEFAULT_MIN_CADENCE_MINUTES,
    /** One [fraction]th of the gap between two rings: the other half of "whichever is shorter". */
    val fraction: Int = DEFAULT_FRACTION,
) {
    companion object {
        const val DEFAULT_AFTER_HOURS = 24
        const val DEFAULT_MIN_CADENCE_MINUTES = 60
        const val DEFAULT_FRACTION = 10
    }
}

/** What the steppers in Settings may be dragged between. */
object SafetyNetLimits {
    /**
     * An hour is the shortest wait worth calling a net, and three days is the longest: past
     * that it is not catching anything, it is bringing something up.
     */
    val AFTER_HOURS = 1..72
    /** Below a quarter of an hour the next ring is always sooner than any net. */
    val MIN_CADENCE_MINUTES = 15..720
    /** A half is barely a share; a fiftieth of a day is half an hour. */
    val FRACTION = 2..50
}

/**
 * The gap between one ring and the next when nothing gets in the way, or null when there is no
 * next ring at all.
 *
 * Asked of the *shape* and not of the row: a reminder that rang six hours ago and was ignored
 * has no next moment of its own (an anchored recurrence counts from the "hecho", so it waits),
 * and its rhythm is six hours all the same. So a span answers with two of its own steps, a
 * calendar with two of its own dates, and everything else by asking a copy of this reminder
 * with nothing spent what it would do from here.
 */
fun Reminder.ringCadence(
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    dayStart: LocalTime = DEFAULT_DAY_START,
    shape: DayShape = DayShape.DEFAULT,
): Duration? {
    val recurrence = recurrence
    if (recurrence is Recurrence.After || recurrence is Recurrence.MonthlyWeekday) {
        val first = nextRecurrence(recurrence, now, zone, dayStart) ?: return null
        val second = nextRecurrence(recurrence, first, zone, dayStart) ?: return null
        return Duration.between(first, second)
    }
    if (recurrence is Recurrence.Calendar) {
        val first = recurrence.nextMoment(id, now, zone, shape) ?: return null
        val second = recurrence.nextMoment(id, first, zone, shape) ?: return null
        return Duration.between(first, second)
    }
    // The rules' own rhythm: what this reminder would do from here if nobody had touched it.
    val fresh = copy(
        status = Status.ACTIVE,
        snoozedUntil = null,
        snoozedToPlace = null,
        firedRules = emptySet(),
        lastFiredAt = null,
        lastDealtAt = null,
        dealtThrough = null,
    )
    val first = nextWake(fresh, now, zone, defaultTime, dayStart, shape)?.at ?: return null
    val second = nextWake(fresh, first, zone, defaultTime, dayStart, shape)?.at ?: return null
    return Duration.between(first, second)
}

/**
 * Whether this shape rings too often to be worth a net. [cadence] is [ringCadence]; null (a
 * reminder with no next ring) is never too fast, because nothing is coming to bury it.
 */
fun tooFastForNet(cadence: Duration?, settings: SafetyNetSettings): Boolean =
    cadence != null && cadence < Duration.ofMinutes(settings.minCadenceMinutes.toLong())

/**
 * How long a snooze may wait at a place before the net says it is still waiting: twice the
 * longest ordinary wait (about two days on the defaults). A crossing may rightly take a day —
 * that is what "cuando llegue a…" means — so the word comes only once the silence has outlasted
 * anything a person plausibly meant, which is when a dropped fence and a blind watch start to
 * look exactly like patience.
 */
fun placeSnoozeWait(settings: SafetyNetSettings): Duration = Duration.ofHours(settings.afterHours.toLong() * 2)

/** How long the net waits, given the rhythm it is stretched under. See [SafetyNetSettings]. */
fun netWait(cadence: Duration?, settings: SafetyNetSettings): Duration {
    val longest = Duration.ofHours(settings.afterHours.toLong())
    if (cadence == null) return longest
    val share = cadence.dividedBy(settings.fraction.coerceAtLeast(1).toLong())
    return minOf(longest, share)
}

/** Which way the reminder got away, which is the only thing the word itself has to say. */
enum class NetWord {
    /** It rang and nobody ever answered it. */
    LET_GO,

    /** Its moment came while something was shut, and there is none left for it to ring at. */
    NEVER_RANG,

    /** It was put off until a place, and the crossing has been a long time coming. */
    WAITING,
}

/**
 * Whether this word is about something that **got away** — as opposed to something still on its
 * way.
 *
 * The two that got away are marked as such where somebody reads them (the notification's
 * "ICYMI:"), because a quiet card in the shade otherwise looks exactly like the alarm it is
 * *about*, and the difference between "this is ringing" and "this rang and you missed it" is the
 * whole of what the net has to say. [NetWord.WAITING] is deliberately not one of them: nothing
 * has been missed there — the reminder is still waiting at its place, and telling somebody they
 * missed it would be the net's one job done backwards.
 */
val NetWord.saysItGotAway: Boolean get() = this == NetWord.LET_GO || this == NetWord.NEVER_RANG

/** A word owed: when it is due, the moment it is about, and which of the two it is. */
data class NetDue(val at: Instant, val about: Instant, val word: NetWord)

/**
 * The last moment this reminder named that came and went, or null when it named none.
 *
 * Walked forward from the day it was written rather than searched backwards, because forwards is
 * the only direction any of this arithmetic goes: a copy with nothing spent is asked what it
 * would do, over and over, and the last answer before now is the moment that got away. Only ever
 * asked of a reminder with nothing left ahead of it ([netDue]), which is why the walk is short —
 * anything that repeats has a next moment and never gets here.
 */
fun Reminder.lastMomentGone(
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    dayStart: LocalTime = DEFAULT_DAY_START,
    shape: DayShape = DayShape.DEFAULT,
): Instant? {
    val fresh = copy(
        status = Status.ACTIVE,
        snoozedUntil = null,
        snoozedToPlace = null,
        firedRules = emptySet(),
        lastFiredAt = null,
        lastDealtAt = null,
        dealtThrough = null,
    )
    var cursor = createdAt.minusMillis(1)
    var last: Instant? = null
    repeat(MOMENTS_WALKED) {
        val at = nextWake(fresh, cursor, zone, defaultTime, dayStart, shape)?.at ?: return last
        if (!at.isBefore(now)) return last
        last = at
        cursor = at
    }
    return last
}

/**
 * Enough to walk out of anything that gets here; a shape with more than this has a next moment.
 * A stretch of the calendar fenced daily has a moment per day, so this is a few years of them.
 */
private const val MOMENTS_WALKED = 1024

/**
 * The word this reminder is owed, or null when it is owed none: the net is off, it is paused or
 * done, it has been dealt with or put off to a clock (which are answers), the word has
 * already been said,
 * or it comes back too fast to be worth catching.
 *
 * Only the moment and the reason. Whether it still holds when that moment arrives is asked again
 * then, because everything about it can change in the day it waits.
 */
fun Reminder.netDue(
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    settings: SafetyNetSettings,
    dayStart: LocalTime = DEFAULT_DAY_START,
    shape: DayShape = DayShape.DEFAULT,
): NetDue? {
    if (status != Status.ACTIVE) return null
    val about: Instant
    val word: NetWord
    when {
        // Put off until a place that has not come. Not a moment that got away — the person
        // answered — but a wait with nothing on the clock and, if the fence is dropped and the
        // watch blind, nothing behind it at all: the one silence in the app no other door can
        // end. Long after the longest ordinary wait, one quiet word that it is still waiting.
        snoozedToPlace != null -> {
            about = lastFiredAt ?: return null
            word = NetWord.WAITING
        }
        // It rang and nobody has answered it since.
        awaitingAnswer(now) -> {
            about = lastFiredAt ?: return null
            word = NetWord.LET_GO
        }
        // It never rang, nobody has dealt with it, and there is no moment left for it to ring
        // at: its moment came while something was shut, and there will not be another.
        lastFiredAt == null && lastDealtAt == null &&
            nextFire(this, now, zone, defaultTime, dayStart, shape) == null -> {
            about = lastMomentGone(now, zone, defaultTime, dayStart, shape) ?: return null
            word = NetWord.NEVER_RANG
        }
        else -> return null
    }
    // **A reminder that has been put off never wakes the net, with one named exception.**
    // Put off to a clock is an answer, and a complete one: the snooze rings by itself, so
    // there is nothing here to add and nothing that could go quiet. (What used to break this
    // was not the rule but the editor, which dropped the snooze on every save — a typo fixed
    // on a reminder put off until tomorrow made it read as rung-and-ignored again, and the net
    // duly went off in the morning about an alert that had been answered. See
    // `Draft.toReminder`.)
    //
    // The exception is [NetWord.WAITING] above, and it is the opposite case rather than the
    // same one: a wait at a *place* has no clock behind it at all, so if the fence is dropped
    // and the watch blind there is nothing left to ring, no overdue card to show for it and no
    // other door that could ever say so. That word does not say the reminder was let go — it
    // says it is still waiting — and it comes once, two days late, on the mutest channel there
    // is.
    snoozedUntil?.let { if (it > now) return null }
    // One word per moment. A second one about the same moment is the nagging this is not.
    nudgedAt?.let { if (!it.isBefore(about)) return null }
    // A wait at a place has no rhythm to be measured against: the cadence machinery below is
    // about rings that are coming, and this one is deliberately not.
    if (word == NetWord.WAITING) return NetDue(about.plus(placeSnoozeWait(settings)), about, word)
    val cadence = ringCadence(now, zone, defaultTime, dayStart, shape)
    if (tooFastForNet(cadence, settings)) return null
    return NetDue(about.plus(netWait(cadence, settings)), about, word)
}

/** Just the moment, for the scheduler, which only has an alarm to set. */
fun Reminder.nudgeAt(
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    settings: SafetyNetSettings,
    dayStart: LocalTime = DEFAULT_DAY_START,
    shape: DayShape = DayShape.DEFAULT,
): Instant? = netDue(now, zone, defaultTime, settings, dayStart, shape)?.at
