package dev.rwilco.ui.format

import android.content.Context
import androidx.compose.runtime.Composable
import dev.rwilco.R
import dev.rwilco.model.Presence
import dev.rwilco.model.Snooze
import dev.rwilco.model.SnoozePlace
import dev.rwilco.model.Trigger

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
    SnoozePlace.LeaveHere -> words.get(R.string.snooze_leave_here)
}

@Composable
fun placeOfferLabel(offer: SnoozePlace): String = placeOfferLabel(rememberWords(), offer)

/** "llegar a Casa" · "salir de aquí": what follows "pospuesto hasta" on a card, a line of history, a snackbar. */
fun snoozePlacePhrase(words: Words, place: Trigger.Location): String =
    words.get(if (place.presence == Presence.INSIDE) R.string.snooze_until_arrive else R.string.snooze_until_leave, place.label)
