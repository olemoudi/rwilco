package dev.rwilco.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * One answer the alert can hold out: one of the app's own ([Snooze]) or one of the person's
 * ([SnoozeSpec], 0.138.0). Everything that shows, hides, counts, sends or takes a snooze deals in
 * these, and in the [key] they travel as — the enum's name, or the spec's own key — so the two
 * kinds are one list everywhere and neither is a special case of the other.
 */
sealed interface SnoozeOffer {
    val key: String

    data class BuiltIn(val snooze: Snooze) : SnoozeOffer {
        override val key: String get() = snooze.name
    }

    data class Custom(val spec: SnoozeSpec) : SnoozeOffer {
        override val key: String get() = spec.key
    }
}

/**
 * The offer [key] describes, or null for one nothing can read. **The settings are not asked**: a
 * key says what it is, so the button of a notification posted yesterday still means what it says
 * after the snooze it named has been deleted.
 */
fun snoozeOfferOf(key: String): SnoozeOffer? =
    Snooze.entries.firstOrNull { it.name == key }?.let(SnoozeOffer::BuiltIn)
        ?: snoozeSpecOf(key)?.let(SnoozeOffer::Custom)

/**
 * Whether the offer can stop being an answer as the day goes on: only a part of today can. What
 * keeps it off a notification, which may sit in the shade long after "esta tarde" has gone.
 */
val SnoozeOffer.expires: Boolean
    get() = this is SnoozeOffer.Custom && spec is SnoozeSpec.On && spec.day == SnoozeDay.Today

/**
 * The key a **notification** button carries, which outlives the settings it was built from.
 *
 * Every offer says what it is except one: [Snooze.CUSTOM] travels as the bare name "CUSTOM" and
 * its length is read when the button is pressed. A card posted while "un posponer a tu medida"
 * said 45 minutes, and pressed after it was moved to 20, put the reminder off 20 — the one button
 * whose label was not what it did. Written out here as the length it showed ([SnoozeSpec.After]),
 * which is the whole point of a key that describes itself. Everything else is already itself.
 */
fun SnoozeOffer.frozen(customMinutes: Int): SnoozeOffer =
    if (this is SnoozeOffer.BuiltIn && snooze == Snooze.CUSTOM) {
        SnoozeOffer.Custom(SnoozeSpec.After(customMinutes.coerceIn(SnoozeLimits.CUSTOM_MINUTES)))
    } else {
        this
    }

/**
 * A number that is this offer's and no other's, and never zero: what tells two snooze buttons
 * apart where only a number can (a `PendingIntent`'s request code — extras are not part of what
 * makes two of those the same, so two buttons on one card would both become the last one built).
 *
 * **Worked out from what the offer is, not from where it sits.** A position would be shorter, and
 * wrong the day the notification's pair is changed while a card is in the shade: the next card
 * for that reminder rebuilds "button one" with another snooze in it, and the old card's first
 * button now does something its label does not say. The app's own keep the number they always
 * had (`ordinal + 1`), so a card posted by the build before this one still means what it says.
 */
val SnoozeOffer.code: Int
    get() = when (this) {
        is SnoozeOffer.BuiltIn -> snooze.ordinal + 1
        is SnoozeOffer.Custom -> when (val spec = spec) {
            is SnoozeSpec.After -> AFTER_CODES + spec.minutes
            is SnoozeSpec.On -> {
                val day = when (val day = spec.day) {
                    SnoozeDay.Today -> 0
                    SnoozeDay.Tomorrow -> 1
                    SnoozeDay.Weekend -> 2
                    is SnoozeDay.Weekday -> 2 + day.day.value
                }
                val hour = when (val hour = spec.hour) {
                    is SnoozeHour.Part -> hour.part.ordinal
                    is SnoozeHour.At -> SnoozePart.entries.size + hour.time.hour * 60 + hour.time.minute
                }
                ON_CODES + day * CODES_A_DAY + hour
            }
        }
    }

/** Above every name the enum could grow. */
private const val AFTER_CODES = 1_000

/** Above the longest length there is ([SnoozeLimits.AFTER_MINUTES]). */
private const val ON_CODES = 100_000

/** Room for the three parts and every minute of a day. */
private const val CODES_A_DAY = 10_000

/** When it comes back; null only for one that [expires] and has. */
fun SnoozeOffer.until(now: Instant, zone: ZoneId, terms: SnoozeTerms): Instant? = when (this) {
    is SnoozeOffer.BuiltIn -> snooze.until(now, zone, terms)
    is SnoozeOffer.Custom -> spec.until(now, zone, terms)
}

/**
 * Which snooze offers are on the alert, and which wait behind "a otro momento" (0.137.0).
 *
 * The alert offered every answer there is, every time — seven lengths, the places, the calendar:
 * up to ten held buttons on the one screen that gets answered half awake, whether or not anybody
 * had ever pressed "la semana que viene". What is [shown] is the person's to say now
 * ([AppSettings.hiddenSnoozes]); the rest is [more], one button away and **ordered by how often
 * it is actually used** ([AppSettings.snoozeUses]), so the answer somebody gives once a month is
 * at the top of the list it is looked for in. Nothing is lost by hiding: every offer is still an
 * answer, only a door further.
 *
 * With nothing hidden and nothing added the board is exactly what the alert was, which is what an
 * update has to be. The place answers are hidden or shown as one ([SNOOZE_PLACES]): they are the
 * phone's offers rather than the person's, and come and go with where the phone is.
 */
data class SnoozeBoard(val shown: List<SnoozeOffer>, val more: List<SnoozeOffer>, val placesShown: Boolean)

/** The key the place answers ("al llegar a casa", "al salir de aquí") are hidden under, as one. */
const val SNOOZE_PLACES = "places"

fun snoozeBoard(settings: AppSettings): SnoozeBoard {
    val (hidden, shown) = settings.snoozeOffers.partition { it.key in settings.hiddenSnoozes }
    return SnoozeBoard(
        shown = shown,
        // Stable, so the ones never used keep the order they always had among themselves.
        more = hidden.sortedByDescending { settings.snoozeUses[it.key] ?: 0 },
        placesShown = SNOOZE_PLACES !in settings.hiddenSnoozes,
    )
}

/**
 * The board as it stands at [now]: a part of today that has already gone is not held out as an
 * answer. Nothing else ever leaves, so the rest keep their places whatever the hour.
 */
fun SnoozeBoard.standingAt(now: Instant, zone: ZoneId, terms: SnoozeTerms): SnoozeBoard =
    if ((shown + more).none { it.expires }) this
    else copy(shown = shown.filter { it.until(now, zone, terms) != null }, more = more.filter { it.until(now, zone, terms) != null })

/**
 * Every offer there is, the app's and the person's, **in one order that does not depend on the
 * hour**: lengths by how long they are, then today, tomorrow, the weekend, the days of the week,
 * next week. A chip that moved with the clock would be looked for where it was yesterday, on the
 * screen that is answered half awake.
 *
 * The app's own keep among themselves the order they always had — which is why [Snooze.CUSTOM]
 * reads as sitting between the ten minutes and the two hours wherever its stepper has been taken:
 * the alert somebody already knows is not rearranged by an update. A tie goes to the app's own,
 * and between two of the person's to the one added first.
 */
val AppSettings.snoozeOffers: List<SnoozeOffer>
    get() {
        val own = customSnoozes.distinct().mapNotNull { key -> snoozeSpecOf(key)?.let(SnoozeOffer::Custom) }
        return (Snooze.entries.map(SnoozeOffer::BuiltIn) + own)
            .sortedWith(compareBy<SnoozeOffer> { placeOf(it).first }.thenBy { placeOf(it).second })
    }

private const val LENGTHS = 0
private const val TODAY = 1
private const val TOMORROW = 2
private const val WEEKEND = 3
private const val WEEKDAYS = 4
private const val NEXT_WEEK = 5

/** Where an offer sits: which group, and where in it. */
private fun AppSettings.placeOf(offer: SnoozeOffer): Pair<Int, Int> = when (offer) {
    is SnoozeOffer.BuiltIn -> when (offer.snooze) {
        Snooze.TEN_MINUTES -> LENGTHS to 10
        Snooze.CUSTOM -> LENGTHS to snoozeCustomMinutes.coerceIn(11, 119)
        Snooze.TWO_HOURS -> LENGTHS to 120
        Snooze.TOMORROW_MORNING -> TOMORROW to dayStart.minuteOfDay
        // "A la misma hora" is no hour of its own: after every one that is.
        Snooze.TOMORROW -> TOMORROW to MINUTES_A_DAY
        // When the weekend starts, which is before any hour inside it.
        Snooze.WEEKEND -> WEEKEND to -1
        Snooze.NEXT_WEEK -> NEXT_WEEK to 0
    }
    is SnoozeOffer.Custom -> when (val spec = offer.spec) {
        is SnoozeSpec.After -> LENGTHS to spec.minutes
        is SnoozeSpec.On -> {
            val minute = spec.hour.timeIn(dayParts).minuteOfDay
            when (val day = spec.day) {
                SnoozeDay.Today -> TODAY to minute
                SnoozeDay.Tomorrow -> TOMORROW to minute
                SnoozeDay.Weekend -> WEEKEND to minute
                is SnoozeDay.Weekday -> WEEKDAYS to day.day.value * MINUTES_A_DAY + minute
            }
        }
    }
}

private val LocalTime.minuteOfDay: Int get() = hour * 60 + minute

/** The offer [key] names **among the ones there are**: the app's, or one the person still keeps. */
private fun AppSettings.offerCalled(key: String): SnoozeOffer? =
    snoozeOfferOf(key)?.takeIf { it is SnoozeOffer.BuiltIn || key in customSnoozes }

/** One use more of the offer called [key]; anything that is not an offer ("a date", "a week") is not counted. */
fun AppSettings.withSnoozeUsed(key: String): AppSettings =
    if (offerCalled(key) == null) this
    else copy(snoozeUses = snoozeUses + (key to (snoozeUses[key] ?: 0) + 1))

/** The switch in Settings: [key] on the alert, or behind the other door. */
fun AppSettings.withSnoozeShown(key: String, shown: Boolean): AppSettings = when {
    key != SNOOZE_PLACES && offerCalled(key) == null -> this
    shown -> copy(hiddenSnoozes = hiddenSnoozes - key)
    else -> copy(hiddenSnoozes = hiddenSnoozes + key)
}

/** How many snoozes of their own somebody can keep: the alert is one screen, and these are on it. */
const val MAX_CUSTOM_SNOOZES = 12

/** Why a snooze somebody has built is not added. The builder says it, in words, before "Añadir". */
enum class CustomSnoozeRefusal { OUT_OF_RANGE, ALREADY_OFFERED, ALREADY_YOURS, TOO_MANY }

/**
 * The app's own offer that already says what [spec] says, if one does: ten minutes, two hours, a
 * day, a week, the person's own length as it is set now, tomorrow morning. Two chips that do the
 * same thing under two names are a question ("which one?") on the screen with the least time for one.
 */
fun AppSettings.builtInSaying(spec: SnoozeSpec): Snooze? = when (spec) {
    is SnoozeSpec.After -> when (spec.minutes) {
        10 -> Snooze.TEN_MINUTES
        120 -> Snooze.TWO_HOURS
        MINUTES_A_DAY -> Snooze.TOMORROW
        7 * MINUTES_A_DAY -> Snooze.NEXT_WEEK
        snoozeCustomMinutes.coerceIn(SnoozeLimits.CUSTOM_MINUTES) -> Snooze.CUSTOM
        else -> null
    }
    // By shape, and by the hour it comes to: "mañana a las 9:00" with a day that starts at nine
    // is the same chip twice under two names.
    is SnoozeSpec.On -> Snooze.TOMORROW_MORNING.takeIf {
        spec.day == SnoozeDay.Tomorrow &&
            (spec.hour == SnoozeHour.Part(SnoozePart.MORNING) || spec.hour == SnoozeHour.At(dayStart))
    }
}

fun AppSettings.customSnoozeRefusal(spec: SnoozeSpec): CustomSnoozeRefusal? = when {
    spec is SnoozeSpec.After && spec.minutes !in SnoozeLimits.AFTER_MINUTES -> CustomSnoozeRefusal.OUT_OF_RANGE
    builtInSaying(spec) != null -> CustomSnoozeRefusal.ALREADY_OFFERED
    spec.key in customSnoozes -> CustomSnoozeRefusal.ALREADY_YOURS
    customSnoozes.size >= MAX_CUSTOM_SNOOZES -> CustomSnoozeRefusal.TOO_MANY
    else -> null
}

/** [spec] kept as one of the person's own; unchanged when it is refused ([customSnoozeRefusal]). */
fun AppSettings.withCustomSnooze(spec: SnoozeSpec): AppSettings =
    if (customSnoozeRefusal(spec) != null) this else copy(customSnoozes = customSnoozes + spec.key)

/**
 * What deleting one of the person's own takes with it, kept so that the undo is a whole one: where
 * it was in their list, whether it was hidden, how often it had been used, and the notification's
 * pair if it was one of the two. Null when [key] is not one of theirs.
 */
data class RemovedCustomSnooze(val key: String, val at: Int, val hidden: Boolean, val uses: Int, val notification: List<String>?)

fun AppSettings.removedCustomSnooze(key: String): RemovedCustomSnooze? {
    val at = customSnoozes.indexOf(key).takeIf { it >= 0 } ?: return null
    return RemovedCustomSnooze(key, at, key in hiddenSnoozes, snoozeUses[key] ?: 0, notificationSnoozes.takeIf { key in it })
}

/**
 * One of the person's own, gone — from everywhere it was named, so that nothing is left hidden,
 * counted or offered on a notification under a key that is no longer anybody's. (A card already
 * in the shade keeps its button, and the button still works: see [snoozeOfferOf].)
 */
fun AppSettings.withoutCustomSnooze(key: String): AppSettings =
    if (key !in customSnoozes) this
    else copy(
        customSnoozes = customSnoozes - key,
        hiddenSnoozes = hiddenSnoozes - key,
        snoozeUses = snoozeUses - key,
        notificationSnoozes = notificationSnoozes - key,
    )

/** The undo of [withoutCustomSnooze]: back where it was, as it was. */
fun AppSettings.withCustomSnoozeBack(removed: RemovedCustomSnooze): AppSettings =
    if (removed.key in customSnoozes || snoozeSpecOf(removed.key) == null) this
    else copy(
        customSnoozes = customSnoozes.toMutableList().apply { add(removed.at.coerceIn(0, size), removed.key) },
        hiddenSnoozes = if (removed.hidden) hiddenSnoozes + removed.key else hiddenSnoozes,
        snoozeUses = if (removed.uses > 0) snoozeUses + (removed.key to removed.uses) else snoozeUses,
        notificationSnoozes = removed.notification ?: notificationSnoozes,
    )

/**
 * The notification's two offers: the stored keys read back, dropping what this build cannot read,
 * what has since been deleted and **anything that [expires]** — a card can sit in the shade for
 * hours, and "esta noche" on it would be a button about a night already gone. Always exactly two,
 * filled from the defaults: a notification with one way to postpone, or none, is not a choice
 * anybody makes on purpose.
 */
val AppSettings.notificationOffers: List<SnoozeOffer>
    get() = (notificationSnoozes.mapNotNull { offerCalled(it)?.takeUnless { offer -> offer.expires } } + DEFAULT_NOTIFICATION_SNOOZES.map(SnoozeOffer::BuiltIn))
        .distinct()
        .take(NOTIFICATION_SNOOZES)

/**
 * The pair after [key] is chosen in Settings: the newest choice replaces the older of the two, and
 * tapping one already there — or one that cannot go on a notification — changes nothing.
 */
fun AppSettings.withNotificationSnooze(key: String): AppSettings {
    val tapped = offerCalled(key)?.takeUnless { it.expires } ?: return this
    val pair = notificationOffers
    if (tapped in pair) return this
    return copy(notificationSnoozes = (pair + tapped).takeLast(NOTIFICATION_SNOOZES).map { it.key })
}

/**
 * What a snooze's moment is worked out from besides the clock: the person's own weekend, the
 * hours the parts of their day stand for, how long their own length is. One value to hand a
 * screen, instead of the settings threaded through every composable that says when an offer
 * would come back.
 */
data class SnoozeTerms(val shape: DayShape, val parts: DayParts, val customMinutes: Int)

val AppSettings.snoozeTerms: SnoozeTerms get() = SnoozeTerms(dayShape, dayParts, snoozeCustomMinutes)

fun Snooze.until(now: Instant, zone: ZoneId, terms: SnoozeTerms): Instant =
    until(now, zone, terms.shape.weekendFrom, terms.shape.weekendFromTime, terms.parts.morning, terms.customMinutes)
