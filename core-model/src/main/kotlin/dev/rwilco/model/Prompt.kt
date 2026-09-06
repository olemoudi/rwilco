package dev.rwilco.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/*
 * The questions a routine puts: "¿lo has hecho?"
 *
 * A routine's rules never ring (see `Routines.kt`). A clock rule among them is a moment to
 * *ask* at — "a las nueve, pregúntame si me he tomado la pastilla" — and a place is a doorway to
 * ask at, or one that can vouch for the deed and counts as having done it
 * ([TriggerRule.resets]). The asking is an alarm of its own, beside the deadline's, the net's
 * and the lapse's (`ReminderScheduler.armAsk`), and [Reminder.askedAt] is what makes a question
 * once per moment. The place half is the watch's (`PlaceGate.watchedCircles`, `Crossing.ASKS`,
 * `Crossing.RESETS`).
 *
 * **Quiet for a tenth of the span after a "hecho".** "¿Has movido el coche?" an hour after
 * saying so is noise, and so is a garage door counting the car as moved twice in an afternoon.
 * The same proportion the safety net uses, and for the same reason: it scales with the thing
 * it is about — two days on three weeks, three quarters of an hour on eight hours.
 */

/** One part in this many of the span: how long a routine holds its questions after a "hecho". */
const val PROMPT_QUIET_FRACTION = 10L

/** Until when a routine keeps quiet after its last "hecho"; null for anything that is not one. */
fun Reminder.promptQuietUntil(zone: ZoneId, dayStart: LocalTime = DEFAULT_DAY_START): Instant? =
    routineSpan(zone, dayStart)?.let { routineAnchor().plus(it.dividedBy(PROMPT_QUIET_FRACTION)) }

/**
 * Where the next question is looked for from: now, past the one already asked, and past the
 * quiet that follows a "hecho".
 */
fun Reminder.promptLookFrom(now: Instant, zone: ZoneId, dayStart: LocalTime = DEFAULT_DAY_START): Instant =
    listOfNotNull(now, askedAt?.plusMillis(1), promptQuietUntil(zone, dayStart)).max()

/**
 * Whether a question may be put right now. Not while the deadline has rung and is waiting for
 * an answer — the alarm is already asking, louder — and not while it is put off: "not now"
 * was said about this very thing.
 */
fun Reminder.promptsAllowed(now: Instant): Boolean =
    status == Status.ACTIVE && isRoutine && !awaitingAnswer(now) &&
        (snoozedUntil?.let { it <= now } ?: true) && snoozedToPlace == null

/** A place that counts as having done it, rather than asking. Only a place can vouch for a deed. */
val TriggerRule.resetsRoutine: Boolean get() = resets && trigger is Trigger.Location

/** The other reading of a rule under a routine: it asks. Every clock rule does; a place unless it resets. */
val TriggerRule.asks: Boolean get() = !resetsRoutine

/**
 * The next moment a clock rule asks at, and which rule it is — what the asking alarm is set
 * for. A place contributes nothing here: its questions come from the watch, when the phone
 * crosses its line. Null when nothing may ask ([promptsAllowed]) or nothing is left to.
 */
fun Reminder.nextPrompt(
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    dayStart: LocalTime = DEFAULT_DAY_START,
    shape: DayShape = DayShape.DEFAULT,
): Wake? {
    if (!promptsAllowed(now)) return null
    val from = promptLookFrom(now, zone, dayStart)
    return rules.withIndex().mapNotNull { (index, rule) ->
        if (!rule.asks || rule.trigger is Trigger.Location) return@mapNotNull null
        val at = nextFireOfRule(rule, id, from, zone, defaultTime, shape)?.moment ?: return@mapNotNull null
        Wake(at, index)
    }.minByOrNull { it.at }
}
