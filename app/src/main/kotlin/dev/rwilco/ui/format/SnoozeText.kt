package dev.rwilco.ui.format

import android.content.Context
import androidx.compose.runtime.Composable
import dev.rwilco.R
import dev.rwilco.model.LATER_DETAIL
import dev.rwilco.model.MIDDAY
import dev.rwilco.model.Presence
import dev.rwilco.model.Snooze
import dev.rwilco.model.SnoozeDay
import dev.rwilco.model.SnoozeHour
import dev.rwilco.model.SnoozeOffer
import dev.rwilco.model.SnoozePart
import dev.rwilco.model.SnoozePlace
import dev.rwilco.model.SnoozeSpec
import dev.rwilco.model.Trigger
import dev.rwilco.model.snoozeDetailOf
import java.time.Instant

/**
 * What each snooze offer is called. The words are the person's, not the duration's — "mañana
 * por la mañana", not "+9 h" — except the custom one, which is nothing but its length.
 */
fun snoozeLabel(words: Words, snooze: Snooze, customMinutes: Int): String = when (snooze) {
    Snooze.TEN_MINUTES -> words.get(R.string.snooze_ten_minutes)
    Snooze.CUSTOM -> durationText(words, customMinutes)
    Snooze.TWO_HOURS -> words.get(R.string.snooze_two_hours)
    Snooze.TOMORROW_MORNING -> words.get(R.string.snooze_tomorrow_morning)
    Snooze.TOMORROW -> words.get(R.string.snooze_tomorrow)
    Snooze.WEEKEND -> words.get(R.string.snooze_weekend)
    Snooze.NEXT_WEEK -> words.get(R.string.snooze_next_week)
}

/**
 * One of the person's own (0.138.0), said the way they would say it: "Esta noche", "Mañana por la
 * tarde", "El finde por la noche", "El lunes a las 08:00" — and a length as nothing but its
 * length, like the one the app always had. Worked out from the snooze itself, so there is no
 * name to type and none to go stale when the hour behind "por la tarde" is moved in Settings.
 */
fun snoozeLabel(words: Words, spec: SnoozeSpec): String = when (spec) {
    is SnoozeSpec.After -> durationText(words, spec.minutes)
    is SnoozeSpec.On -> when (val hour = spec.hour) {
        is SnoozeHour.Part -> {
            val day = spec.day
            val phrase = when (day) {
                SnoozeDay.Today -> when (hour.part) {
                    SnoozePart.MORNING -> R.string.snooze_own_this_morning
                    SnoozePart.AFTERNOON -> R.string.snooze_own_this_afternoon
                    SnoozePart.EVENING -> R.string.snooze_own_this_evening
                }
                SnoozeDay.Tomorrow -> when (hour.part) {
                    SnoozePart.MORNING -> R.string.snooze_tomorrow_morning
                    SnoozePart.AFTERNOON -> R.string.snooze_own_tomorrow_afternoon
                    SnoozePart.EVENING -> R.string.snooze_own_tomorrow_evening
                }
                SnoozeDay.Weekend -> when (hour.part) {
                    SnoozePart.MORNING -> R.string.snooze_own_weekend_morning
                    SnoozePart.AFTERNOON -> R.string.snooze_own_weekend_afternoon
                    SnoozePart.EVENING -> R.string.snooze_own_weekend_evening
                }
                is SnoozeDay.Weekday -> when (hour.part) {
                    SnoozePart.MORNING -> R.string.snooze_own_weekday_morning
                    SnoozePart.AFTERNOON -> R.string.snooze_own_weekday_afternoon
                    SnoozePart.EVENING -> R.string.snooze_own_weekday_evening
                }
            }
            if (day is SnoozeDay.Weekday) words.get(phrase, TimeText.weekday(day.day, words.locale)) else words.get(phrase)
        }
        // "A mediodía" is an hour and not a part (see [MIDDAY]), so it is said here by name.
        is SnoozeHour.At -> if (hour.time == MIDDAY) {
            when (val day = spec.day) {
                SnoozeDay.Today -> words.get(R.string.snooze_own_today_midday)
                SnoozeDay.Tomorrow -> words.get(R.string.snooze_own_tomorrow_midday)
                SnoozeDay.Weekend -> words.get(R.string.snooze_own_weekend_midday)
                is SnoozeDay.Weekday -> words.get(R.string.snooze_own_weekday_midday, TimeText.weekday(day.day, words.locale))
            }
        } else {
            val time = TimeText.time(hour.time, words.is24h, words.locale)
            when (val day = spec.day) {
                SnoozeDay.Today -> words.get(R.string.snooze_own_today_at, time)
                SnoozeDay.Tomorrow -> words.get(R.string.snooze_own_tomorrow_at, time)
                SnoozeDay.Weekend -> words.get(R.string.snooze_own_weekend_at, time)
                is SnoozeDay.Weekday -> words.get(R.string.snooze_own_weekday_at, TimeText.weekday(day.day, words.locale), time)
            }
        }
    }
}

/**
 * What a row of "a otro momento…" calls an offer (0.160.0): a length reads as the sentence
 * somebody would say — "Dentro de 2 días", "Dentro de 1 h" — where a button keeps the bare length
 * that fits on one. A list is read, not aimed at, and "14 d" beside "Mañana por la tarde" reads
 * as a code. Everything that is not a length is called what its button is.
 */
fun snoozeRowLabel(words: Words, offer: SnoozeOffer, customMinutes: Int): String {
    val minutes = when (offer) {
        is SnoozeOffer.BuiltIn -> when (offer.snooze) {
            Snooze.TEN_MINUTES -> 10
            Snooze.CUSTOM -> customMinutes
            Snooze.TWO_HOURS -> 120
            else -> null
        }
        is SnoozeOffer.Custom -> (offer.spec as? SnoozeSpec.After)?.minutes
    } ?: return snoozeLabel(words, offer, customMinutes)
    val days = minutes / MINUTES_PER_DAY
    val length = if (days > 0 && minutes % MINUTES_PER_DAY == 0) words.plural(R.plurals.snooze_more_days, days) else durationText(words, minutes)
    return words.get(R.string.snooze_more_in, length)
}

private const val MINUTES_PER_DAY = 24 * 60

@Composable
fun snoozeRowLabel(offer: SnoozeOffer, customMinutes: Int): String = snoozeRowLabel(rememberWords(), offer, customMinutes)

/** What an offer's button says, whoever's it is. */
fun snoozeLabel(words: Words, offer: SnoozeOffer, customMinutes: Int): String = when (offer) {
    is SnoozeOffer.BuiltIn -> snoozeLabel(words, offer.snooze, customMinutes)
    is SnoozeOffer.Custom -> snoozeLabel(words, offer.spec)
}

fun snoozeLabel(context: Context, offer: SnoozeOffer, customMinutes: Int): String = snoozeLabel(context.words(), offer, customMinutes)

@Composable
fun snoozeLabel(offer: SnoozeOffer, customMinutes: Int): String = snoozeLabel(rememberWords(), offer, customMinutes)

/** "Al llegar a Casa" · "Al salir de aquí": the two place offers, as buttons say them. */
fun placeOfferLabel(words: Words, offer: SnoozePlace): String = when (offer) {
    is SnoozePlace.Arrive -> words.get(R.string.snooze_arrive_at, offer.place.label)
    // **With the distance in it** (0.79.0): "al salir de aquí" draws a circle around where you
    // stand and nobody could see how big. A person hears "when I leave here" and pictures the
    // doorstep; the ring that never came was the app keeping a promise nobody could read. The
    // number is the offer's own now (0.80.0, [hereRadiusM] of the last position), so it says
    // what it would actually draw rather than a constant that stopped being one.
    is SnoozePlace.LeaveHere -> words.get(R.string.snooze_leave_here, offer.radiusM)
}

@Composable
fun placeOfferLabel(offer: SnoozePlace): String = placeOfferLabel(rememberWords(), offer)

/**
 * Which word a line of history filed as a snooze gets: the answer somebody gave, not the
 * machinery under it.
 *
 * "Todavía no" to a routine's question is written down as a snooze with [LATER_DETAIL] for its
 * detail — there is nothing else to write it as, and an answer nobody wrote down was the same
 * nothing as never having seen the card. But it postponed nothing, and the history said it had.
 */
enum class SnoozeWord { NOT_YET, UNTIL_PLACE, UNTIL_MOMENT, PLAIN }

fun snoozeWordOf(detail: String?): SnoozeWord = when {
    detail == null -> SnoozeWord.PLAIN
    detail == LATER_DETAIL -> SnoozeWord.NOT_YET
    snoozeDetailOf(detail) != null -> SnoozeWord.UNTIL_PLACE
    runCatching { Instant.parse(detail) }.isSuccess -> SnoozeWord.UNTIL_MOMENT
    else -> SnoozeWord.PLAIN
}

/** "llegar a Casa" · "salir de aquí": what follows "pospuesto hasta" on a card, a line of history, a snackbar. */
fun snoozePlacePhrase(words: Words, place: Trigger.Location): String =
    words.get(if (place.presence == Presence.INSIDE) R.string.snooze_until_arrive else R.string.snooze_until_leave, place.label)
