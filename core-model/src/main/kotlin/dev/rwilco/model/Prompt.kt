package dev.rwilco.model

import java.time.Duration
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
 * **Questions only in the last quarter of the span** ([promptOpensAt], 0.198.0, the owner's
 * ask). "¿Has hecho «Cargar teclado y ratón»?" every weekend with fifty days to go is noise: the
 * deed is not owed yet, and doing it early is still told by tapping "hecho" on the row. So a
 * clock rule or a doorway that asks keeps quiet until a quarter of the span is left, counted
 * from where the count runs ([routineAnchor]: the last "hecho", or the start) — two weeks on
 * eight, two hours on eight. Until 0.197.0 a routine asked from a tenth of the span after a
 * "hecho", and from its first day when it had none.
 *
 * **A place that counts as done keeps quiet for a tenth of the span after a "hecho"**
 * ([promptQuietUntil]): a garage door counting the car as moved twice in an afternoon. The same
 * proportion the safety net uses, and for the same reason: it scales with the thing it is
 * about — two days on three weeks, three quarters of an hour on eight hours. Not the quarter:
 * a deed done early must still count, or the next deadline runs from the older one.
 *
 * **A question that was owed is still owed.** Where the next question is looked for from is
 * never "now": it is the last one put (plus the echo, [PLACE_ECHO]), the quiet, the start, or
 * the last edit — whichever is latest — so a nine o'clock the phone slept through is a moment
 * in the past, armed as such and delivered at once ([nextPrompt], `ReminderScheduler.armAsk`).
 * The fences are re-judged in full when it lands (`ReminderFiring.ask`), so a late question
 * drops itself if its window has closed — and a question dropped at its fence is stamped as
 * tried ([Reminder.askedAt]), or the next look would hand the same past moment back and the
 * alarm would loop.
 */

/** One part in this many of the span: how long a place that counts as done holds back after a "hecho". */
const val PROMPT_QUIET_FRACTION = 10L

/** One part in this many of the span: the last stretch of it in which a routine asks. */
const val PROMPT_OPEN_FRACTION = 4L

/**
 * From when a routine asks: the last [PROMPT_OPEN_FRACTION]th of its span before the deadline,
 * counted from [routineAnchor] (the last "hecho", or the start). Null for anything that is not a
 * routine. See the file's note.
 */
fun Reminder.promptOpensAt(zone: ZoneId, dayStart: LocalTime = DEFAULT_DAY_START): Instant? {
    val deadline = routineDeadline(zone, dayStart) ?: return null
    val span = Duration.between(routineAnchor(), deadline).coerceAtLeast(Duration.ZERO)
    return deadline.minus(span.dividedBy(PROMPT_OPEN_FRACTION))
}

/**
 * Until when a routine keeps quiet after its last "hecho"; null while it has never been done,
 * and for anything that is not a routine.
 */
fun Reminder.promptQuietUntil(zone: ZoneId, dayStart: LocalTime = DEFAULT_DAY_START): Instant? {
    val dealt = lastDealtAt ?: return null
    return routineSpan(zone, dayStart)?.let { dealt.plus(it.dividedBy(PROMPT_QUIET_FRACTION)) }
}

/**
 * Where the next question is looked for from — and deliberately not from [now]: past the one
 * already asked and its echo (two questions minutes apart are one question, whichever door
 * put them), never before the last quarter of the span ([promptOpensAt]) — which is past the
 * quiet that follows a "hecho" and the start of the count —, and never
 * before the routine was last written or brought back from a pause ([Reminder.updatedAt]:
 * a rule added this morning is not owed yesterday's question). A moment earlier than now is a
 * question that was owed and never put — the phone was off — and it is put at once.
 *
 * An [askedAt] ahead of now (a clock that ran ahead and was put right) is ignored rather than
 * obeyed: obeyed, it held every question until the wall clock caught up.
 */
fun Reminder.promptLookFrom(now: Instant, zone: ZoneId, dayStart: LocalTime = DEFAULT_DAY_START): Instant =
    listOfNotNull(
        routineStart(),
        updatedAt,
        askedAt?.takeIf { it <= now }?.plus(PLACE_ECHO),
        promptQuietUntil(zone, dayStart),
        promptOpensAt(zone, dayStart),
    ).max()

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
 * crosses its line. Null when nothing may ask ([promptsAllowed]) or nothing is left to. **May
 * be in the past** (see [promptLookFrom]): that is a question owed, and the alarm set for a
 * moment already gone arrives at once.
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
