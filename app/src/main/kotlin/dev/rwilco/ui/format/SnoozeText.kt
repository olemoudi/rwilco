package dev.rwilco.ui.format

import android.content.Context
import androidx.compose.runtime.Composable
import dev.rwilco.R
import dev.rwilco.alarm.LATER_DETAIL
import dev.rwilco.model.Presence
import dev.rwilco.model.Snooze
import dev.rwilco.model.SnoozePlace
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

fun snoozeLabel(context: Context, snooze: Snooze, customMinutes: Int): String = snoozeLabel(context.words(), snooze, customMinutes)

@Composable
fun snoozeLabel(snooze: Snooze, customMinutes: Int): String = snoozeLabel(rememberWords(), snooze, customMinutes)

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
