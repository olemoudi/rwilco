package dev.rwilco.model

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * What happens around a reminder actually ringing. Pure, so the scheduler, the alarm receiver
 * and the notification buttons all decide the same way and a JVM test can hold them to it.
 */

/**
 * What a reminder becomes once the person has dealt with a firing.
 *
 * Done means done, unless the reminder was asked to keep going ([Reminder.repeats]). This used
 * to read "anything that CAN come round again stays active", which sounds reasonable and is
 * wrong: a place can always come round again, so "al llegar a casa, saca la basura" rang again
 * the next time somebody walked through their own front door, hours after they had dealt with
 * it. Whether something should repeat is not something a trigger's shape can answer — only the
 * person can, and until they do the answer is no.
 */
fun statusAfterDismissal(
    reminder: Reminder,
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    shape: DayShape = DayShape.DEFAULT,
): Status {
    if (!reminder.recurrence.repeats) return Status.DONE
    // A span counted from an event always has a next one, so there is nothing to check: it
    // stays. A calendar is asked like the triggers are, because a calendar can run out
    // ([RepeatEnd]) and a series that has rung its last time is finished.
    if (reminder.recurrence.isAnchored && !reminder.recurrence.isCalendar) return Status.ACTIVE
    // A calendar with no date left finishes the rules with it. It has no rest to hand them
    // (restUntil is null once the series is over), so asked below they would speak again on
    // their own, unfenced — "al llegar a casa, y vuelve cada lunes hasta junio" ringing on
    // every arrival after June. The same walk recurrenceWarning does, from the same place.
    if (reminder.recurrence.isCalendar && reminder.calendarMoment(reminder.searchFrom(now), zone, shape) == null) return Status.DONE
    // Dealt with means the round is over: what had already happened under ALL stops counting,
    // and the question is whether the reminder can come round again from scratch.
    val cleared = reminder.copy(status = Status.ACTIVE, snoozedUntil = null, snoozedToPlace = null, firedRules = emptySet())
    return if (nextFire(cleared, now, zone, defaultTime, shape = shape) == null) Status.DONE else Status.ACTIVE
}

/**
 * The moment a "hecho" spends, or null when it spends none.
 *
 * **A "hecho" deals with whatever is owed.** Usually that is the firing waiting for an answer,
 * and then it spends nothing extra: the ring is what is being answered, and taking tomorrow's
 * moment with it would skip a day nobody asked to skip.
 *
 * When nothing is waiting, the thing being dealt with is the moment that was coming. A daily at
 * two o'clock, ticked off this morning, means tomorrow's two o'clock is done and the day after
 * is what is next — and ticking it off again sends it on another day, which is exactly what
 * doing it twice means. The moment goes into [Reminder.dealtThrough], where `searchFrom` and
 * `recurrenceAnchor` read it.
 *
 * A place has no moment to spend and answers null, which is right: nothing about arriving
 * somewhere can be done in advance.
 *
 * **And only the round that is coming.** With a recurrence on the reminder a "hecho" is one
 * step of it, so the moment it may spend is one inside the next step — counted from now, or
 * from what has already been done ahead, which is what makes a second "hecho" do the next
 * round. "Al llegar a casa, o el 31 de diciembre" with "cada día", swiped done in March, used to
 * spend the 31st of December: the anchor then counted a day from *that*, and the place was not
 * watched, armed or rung for nine months.
 */
fun Reminder.momentDealtWith(
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    dayStart: LocalTime = DEFAULT_DAY_START,
    shape: DayShape = DayShape.DEFAULT,
): Instant? {
    if (awaitingAnswer(now)) return null
    val moment = nextFire(this, now, zone, defaultTime, dayStart, shape)?.moment ?: return null
    val base = maxOf(now, dealtThrough ?: now)
    val step = calendarMoment(base, zone, shape) ?: nextRecurrence(recurrence, base, zone, dayStart) ?: return moment
    val within = if (recurrence.countsInDays) moment.atZone(zone).toLocalDate() <= step.atZone(zone).toLocalDate() else moment <= step
    return moment.takeIf { within }
}

/**
 * Whether a place read as a *state* has already had its say in this round.
 *
 * "Mientras esté en casa" is true for as long as somebody is at home, so the watch reports it
 * true whenever it is asked afresh, and without this a reminder left unanswered would ring all
 * evening. Dealing with it starts the next round — and a recurrence's rest holds the rules quiet
 * until it is up — which is the same shape every other trigger has.
 *
 * A *crossing* is the exception it exists to be: "al llegar a casa" that rang and was ignored
 * rings again when somebody leaves and comes back, because a second doorway is a second thing
 * that happened. So it is never spent here, only by being dealt with.
 *
 * And the ring has to have been *this rule's* ([Reminder.lastFiredRule]): under "cualquiera"
 * a clock sibling ringing at nine and going unanswered is not the place having had its say. A
 * row that does not know which rule rang (written before the column) is read as it always
 * was — one round of the old silence, and then the column is populated.
 */
fun Reminder.presenceAlreadyRang(place: Trigger.Location, ruleIndex: Int): Boolean {
    if (place.onCrossing) return false
    val fired = lastFiredAt ?: return false
    if (fired <= (lastDealtAt ?: Instant.MIN)) return false
    return lastFiredRule == null || lastFiredRule == ruleIndex
}

/** Inside this of the last ring, a second sighting of the same place is the same arrival. */
val PLACE_ECHO: Duration = Duration.ofMinutes(5)

/**
 * Whether a place firing is an echo of a ring just given rather than a new arrival.
 *
 * Two eyes watch every place — the phone's geofence and the app's own watch — and one arrival
 * can reach the firing twice within a few minutes; the second sighting must not ring. But an
 * echo is only an echo of *this* circle's ring: a sibling rule's nine o'clock ring must not
 * silence a genuine arrival three minutes later ("al llegar a casa, o a las 21:00" — the same
 * class of bug the per-circle `strict` in the watch was fixed for). [lastFiredRule] null still
 * reads as an echo, because a ring with no rule behind it is the reminder itself having just
 * rung — a snooze's own crossing, a recurrence — and the same pin registered as both a snooze
 * circle and a rule circle delivers both crossings from one doorway.
 */
fun isPlaceEcho(lastFiredAt: Instant?, lastFiredRule: Int?, ruleIndex: Int?, now: Instant, echo: Duration = PLACE_ECHO): Boolean {
    if (lastFiredAt == null) return false
    if (Duration.between(lastFiredAt, now) >= echo) return false
    return lastFiredRule == null || lastFiredRule == ruleIndex
}

/**
 * The moment an alarm was set for and never rang — the phone was off, or the app was killed
 * before the receiver ran — or null when nothing was missed.
 *
 * Deliberately "armed and not fired" rather than "in the past": a reminder that rang and was
 * ignored is already visible as overdue, and telling somebody about it twice is noise.
 *
 * And "not answered" rather than "not fired": a "hecho" or a "posponer" given after the moment
 * — from the card, before the alarm got through — is an answer to it, and a moment somebody has
 * answered is not owed a ring. That is what lets a re-arm pass *hold* a missed moment (leave
 * the row as it is, for the delivery in flight or the next catch-up to ring) without holding
 * one the person has already dealt with.
 */
fun missedFire(reminder: Reminder, now: Instant): Instant? {
    if (reminder.status != Status.ACTIVE) return null
    val armed = reminder.armedFor ?: return null
    if (armed > now) return null
    val fired = reminder.lastFiredAt
    if (fired != null && fired >= armed) return null
    val dealt = reminder.lastDealtAt
    if (dealt != null && dealt >= armed) return null
    val snoozed = reminder.snoozedUntil
    if (snoozed != null && snoozed > now) return null
    if (reminder.snoozedToPlace != null) return null
    return armed
}

/** How the person is told about a firing, given what they asked for. */
data class FiringPlan(
    val fullScreen: Boolean,
    val notification: Boolean,
    val sound: Boolean,
    val vibrate: Boolean,
    /** The sound comes back every few minutes until somebody deals with the reminder. */
    val insistent: Boolean = false,
) {
    /**
     * A full-screen alert rings for itself (a looping tone while the screen is up), so the
     * notification that carries it must stay silent or the two overlap.
     */
    val notificationSound: Boolean get() = sound && !fullScreen
    val notificationVibrate: Boolean get() = vibrate && !fullScreen
}

/**
 * Whether the screen's own ring goes round and round, or says the tone once and stops.
 *
 * The two sound tiles are one choice — once, or again until somebody answers — and on a
 * full-screen alert they used to be the same thing: the screen looped whatever it was given,
 * because looping is what a takeover needs to be worth taking the screen for. But "sonido" is
 * a promise about how many times you are going to hear it, and a screen that says it over and
 * over for a minute has broken that promise louder than any other part of the app could.
 *
 * A screen can carry several reminders at once, so it loops when *any* of them asked to be
 * insisted at — the same way it takes the insistent tone if any of them wants it.
 */
fun loopsOnScreen(plans: List<FiringPlan>): Boolean = plans.any { it.insistent }

/**
 * Whether the alert screen has a noise to answer before it will take "hecho".
 *
 * Half awake, "make it stop" and "I have done that" are the same reflex and only one of them is
 * true — so a screen that is making a noise asks to be silenced first. But that argument is
 * about a noise that is *still going when the thumb arrives*, and only two of the three make
 * one: the buzz, which is built as a whole minute of waveform ([waveformFor]), and the
 * insistent tone, which loops for as long ([loopsOnScreen]). Plain "sonido" says the tone once
 * and stops after a second or two — and the screen went on wearing the red button over the
 * fifty-eight seconds of silence that followed, holding "hecho" out of reach to protect
 * somebody from a noise that had already ended.
 *
 * Asked of the whole screen, like everything else here: one reminder that keeps making a noise
 * is a noise to answer, whoever else is on it.
 */
fun asksToBeSilenced(plans: List<FiringPlan>): Boolean = plans.any { it.vibrate || it.insistent }

fun firingPlan(actions: Set<Action>): FiringPlan = FiringPlan(
    fullScreen = Action.FULL_SCREEN in actions,
    // A full-screen alert always leaves a notification behind: it is what the person finds if
    // the system refused the takeover, or if they left the screen without deciding. And a
    // sound or a buzz needs one to be carried by — a notification's channel IS how the phone
    // makes them — so asking for either is asking for the notification, whether or not its
    // tile is ticked. The only firing with nothing to do is one asked for nothing.
    notification = actions.isNotEmpty(),
    sound = actions.any { it in SOUND_ACTIONS },
    vibrate = Action.VIBRATE in actions,
    insistent = Action.SOUND_UNTIL_ANSWERED in actions,
)

/**
 * Under ALL, the one-shot moments that came and went while the phone was off, after the one
 * that was armed: what a catch-up still owes once it has rung that one.
 *
 * Only the armed moment is detectable as missed ([missedFire]), and under ALL only the
 * earliest pending moment is ever armed — the next is armed once the first has been written
 * down. So a phone off from before the first until after the second wakes owing both, notes
 * the first, and would then wait for a second that has already been and gone: the set never
 * completes. These are the moments after [missed] that a one-shot rule (a date, a date and
 * time, a countdown) produced before [now] with its own hours holding — the ones that would
 * have been armed in turn had the phone been on. A repeating or random rule owes nothing:
 * its next moment is still ahead, and the set completes then, late rather than never.
 */
fun owedUnderAll(
    reminder: Reminder,
    missed: Instant,
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    shape: DayShape = DayShape.DEFAULT,
): List<Wake> {
    if (reminder.ruleMatch != RuleMatch.ALL || !reminder.rulesCombine) return emptyList()
    return reminder.pendingRules()
        .mapNotNull { index ->
            val rule = reminder.rules[index]
            if (!rule.trigger.isOneShot) return@mapNotNull null
            val at = (nextFireOfRule(rule, reminder.id, missed, zone, defaultTime, shape) as? NextFire.Scheduled)?.at ?: return@mapNotNull null
            if (at > now) null else Wake(at, index)
        }
        .sortedBy { it.at }
}

/**
 * What a rule's moment does to the reminder as a whole.
 *
 * Under ANY every moment rings. Under ALL a moment is first of all a fact to write down, and
 * only the one that completes the set rings — which is why this is a decision and not an
 * `if` in the receiver: the alarm, the geofence and the catch-up after a reboot all arrive
 * here by different doors and must answer the same way.
 */
sealed interface FiringOutcome {
    /** Tell the person. */
    data object Ring : FiringOutcome

    /** Not yet: this one happened, [fired] is what has happened so far, and the rest is waited on. */
    data class Wait(val fired: Set<Int>) : FiringOutcome
}

fun outcomeOfFiring(reminder: Reminder, ruleIndex: Int?): FiringOutcome {
    // A snooze's moment (no rule behind it) is the ring itself, as is anything under ANY.
    //
    // And under TOGETHER: nothing accumulates there. By the time a firing reaches here its
    // rule has already been judged against every other one folded in as a state
    // ([Reminder.ruleInSet]), so all of them being true is what got it this far, and there
    // is nothing left to wait for.
    if (ruleIndex == null || reminder.ruleMatch != RuleMatch.ALL || !reminder.rulesCombine) return FiringOutcome.Ring
    if (ruleIndex !in reminder.rules.indices) return FiringOutcome.Ring
    val fired = reminder.firedRules.filter { it in reminder.rules.indices }.toSet() + ruleIndex
    return if (fired.size == reminder.rules.size) FiringOutcome.Ring else FiringOutcome.Wait(fired)
}

/**
 * The moment a ring is recorded against, which is what makes that moment spent.
 *
 * Not the millisecond the alarm arrived: an alarm is allowed to be a breath early, and a moment
 * whose own instant is still a second away would be armed all over again the next time the
 * scheduler looks. So a moment that was armed counts as rung when its alarm shows up, and a
 * catch-up ([late]) counts as rung *now* — otherwise a daily reminder the phone slept through
 * for three days would ring three times on the way back up.
 *
 * A catch-up must not reach for the armed moment either. By the time it runs, the re-arm that
 * found the missed moment has already written the NEXT one into the row — tomorrow's nine
 * o'clock — and taking the later of the two would record the ring against a moment that has not
 * happened. Tomorrow would then pass in silence, and so would the next moment of every rule the
 * reminder has. What the catch-up knows is [late] and now, and now is the later of those.
 *
 * [eventDriven] is the other exception, and the reason this is a function rather than a `max`.
 * A place happens when it happens; the armed moment belongs to whichever OTHER rule is still
 * waiting, and under ANY that can be days off — "al llegar a casa, o mañana a las nueve".
 * Reaching for it would mark tomorrow's nine o'clock spent the moment somebody walked through
 * their own front door, and it would never ring.
 */
fun momentRungFor(now: Instant, armedFor: Instant?, late: Instant?, eventDriven: Boolean): Instant =
    if (late != null) maxOf(now, late)
    else listOfNotNull(now, armedFor.takeUnless { eventDriven }).max()

/** How long "a little later" is when nobody has said ([AppSettings.snoozeCustomMinutes]). */
const val DEFAULT_SNOOZE_MINUTES = 30

/** What the custom snooze may be set to, in minutes, and the step it moves by. */
object SnoozeLimits {
    val CUSTOM_MINUTES = 5..720
    const val STEP = 5
}

/**
 * The snooze offers on the alert screen and in the notification.
 *
 * They are the answers a person actually gives an alarm: not yet, a little later, later today,
 * tomorrow morning, tomorrow, at the weekend, next week. The wall-clock ones keep the time
 * rather than adding hours, because "mañana a la misma hora" is what somebody means; "mañana
 * por la mañana" lands on the hour the day starts at, because a 23:40 alarm put off to
 * "tomorrow" was coming back at 23:40. [CUSTOM] is the one length that is the person's own.
 *
 * Declaration order is the order the alert screen offers them in. It travels as a *name*
 * everywhere it is stored or sent — an intent extra, and the two offers in the settings
 * ([AppSettings.notificationSnoozes]) — and never as the enum itself, so a name this build has
 * no member for is dropped ([notificationSnoozeOffers]) rather than failing the read. Decoding
 * the settings is all-or-nothing: an exception there does not lose a snooze offer, it loses the
 * theme, the sound, the presets and the saved places with it.
 */
enum class Snooze {
    TEN_MINUTES,
    CUSTOM,
    TWO_HOURS,
    TOMORROW_MORNING,
    TOMORROW,
    WEEKEND,
    NEXT_WEEK,
    ;

    /**
     * When it comes back. [weekendDay]/[weekendTime] are a setting (Friday at 20:30 by default)
     * because "el finde" starts at different hours for different people; [dayStart] is where
     * "tomorrow morning" lands, and [customMinutes] is how long [CUSTOM] is.
     */
    fun until(
        now: Instant,
        zone: ZoneId,
        weekendDay: DayOfWeek,
        weekendTime: LocalTime,
        dayStart: LocalTime = DEFAULT_DAY_START,
        customMinutes: Int = DEFAULT_SNOOZE_MINUTES,
    ): Instant {
        val here = now.atZone(zone)
        return when (this) {
            TEN_MINUTES -> now.plusSeconds(10 * 60)
            CUSTOM -> now.plusSeconds(customMinutes.coerceIn(SnoozeLimits.CUSTOM_MINUTES) * 60L)
            TWO_HOURS -> now.plusSeconds(2 * 60 * 60)
            TOMORROW_MORNING -> here.toLocalDate().plusDays(1).atTime(dayStart).atZone(zone).toInstant()
            // Same wall-clock time, so a clock change in between does not move it an hour.
            TOMORROW -> here.plusDays(1).toInstant()
            NEXT_WEEK -> here.plusWeeks(1).toInstant()
            WEEKEND -> {
                val candidate = here.toLocalDate().with(TemporalAdjusters.nextOrSame(weekendDay))
                    .atTime(weekendTime).atZone(zone)
                // Already past this week's: the weekend being talked about is the next one.
                if (candidate.toInstant() > now) candidate.toInstant()
                else here.toLocalDate().with(TemporalAdjusters.next(weekendDay)).atTime(weekendTime).atZone(zone).toInstant()
            }
        }
    }
}

/** How many snooze offers a notification has room for: three actions, and "hecho" is one. */
const val NOTIFICATION_SNOOZES = 2

/** What the notification carries until somebody says otherwise. */
val DEFAULT_NOTIFICATION_SNOOZES: List<Snooze> = listOf(Snooze.TEN_MINUTES, Snooze.TWO_HOURS)

/**
 * The stored names read back as offers, dropping any this build has no member for — and falling
 * back to the defaults if that leaves nothing, because a notification with no way to postpone is
 * worse than one offering the wrong two.
 */
val AppSettings.notificationSnoozeOffers: List<Snooze>
    get() = notificationSnoozes.mapNotNull { name -> Snooze.entries.firstOrNull { it.name == name } }
        .ifEmpty { DEFAULT_NOTIFICATION_SNOOZES }

/**
 * The notification's two offers after [tapped] is chosen in the settings: always exactly two,
 * the newest choice replacing the older of the pair, and tapping one already there changing
 * nothing — a notification with one offer, or none, is not a choice anybody makes on purpose.
 */
fun pickNotificationSnoozes(current: List<Snooze>, tapped: Snooze): List<Snooze> {
    val pair = current.distinct().take(NOTIFICATION_SNOOZES)
    if (tapped in pair) return pair
    return (pair + tapped).takeLast(NOTIFICATION_SNOOZES)
}

/**
 * Rang, and nobody has dealt with it since: what keeps a reminder on the alert screen, and what
 * takes it down when the answer comes from somewhere else (the notification's own "Hecho"). A
 * snooze is an answer; so is a pause. Read off the row, so every door agrees.
 */
fun Reminder.awaitingAnswer(now: Instant): Boolean {
    if (status != Status.ACTIVE) return false
    val rang = lastFiredAt ?: return false
    val dealt = lastDealtAt
    if (dealt != null && !dealt.isBefore(rang)) return false
    if (snoozedToPlace != null) return false
    val snoozed = snoozedUntil
    return snoozed == null || snoozed <= now
}

/** A firing this far behind its moment is a note about the past, not the moment itself. */
val LATE_IS_MISSED: Duration = Duration.ofMinutes(15)

/**
 * How a catch-up is shown. A moment the phone slept through by a couple of minutes — a reboot,
 * a process that took a while to come back — is still that moment and rings as one; only a
 * moment missed by a good while arrives as the quiet "did not ring on time" note, because a
 * timer that goes off at half past for a quarter past is no timer at all. Null means "live".
 */
fun lateForPresentation(late: Instant?, now: Instant): Instant? =
    late?.takeIf { Duration.between(it, now) >= LATE_IS_MISSED }
