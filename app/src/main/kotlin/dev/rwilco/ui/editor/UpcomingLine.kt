package dev.rwilco.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import dev.rwilco.R
import dev.rwilco.model.Action
import dev.rwilco.model.DayShape
import dev.rwilco.model.NextFire
import dev.rwilco.model.Recurrence
import dev.rwilco.model.awakeAt
import dev.rwilco.model.firingPlan
import dev.rwilco.model.moment
import dev.rwilco.ui.format.recurrenceLabel
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.Words
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.format.placePhraseOf
import dev.rwilco.ui.format.rememberWords
import dev.rwilco.ui.format.snoozePlacePhrase
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Suena mañana 09:00 · luego vie 09:00 · luego sáb 09:00": what the draft as it stands will
 * actually do, worked out by the same [dev.rwilco.model.upcomingMoments] walk the firing takes.
 *
 * The sentence over the button says what was *asked for*; this says what will *happen*, which
 * for a rule with fences and a recurrence behind it is the only way to check the arrangement
 * without saving it and waiting. The first moment is in amber, because that is exactly what
 * amber means here — the next thing to ring — and the rest are the plain ink of a list.
 *
 * **Two things it used to leave unsaid, both of them the ones that matter.**
 *
 * With no moments at all it drew nothing, so the save bar under an arrangement that can never
 * ring was identical to the one under a note nobody had answered the "when" for — and the
 * warning that says so is a small line under a rule that may be three cards up. It says it
 * here now ([cannotRing]), in the error ink, right over the button.
 *
 * And "Suena" was a promise the hours could not keep: everything landing while somebody is
 * asleep arrives silent, whatever its tiles say (`hushedByTheHour`), so an alarm asked for at
 * four in the morning came as a card and nothing else. Said on the first moment only — it is
 * the one that reads as the promise — and only when the draft asked for a noise in the first
 * place, since "en silencio" is not news about a reminder that was never going to make one.
 *
 * **And a third (0.132.0): what the row has been through.** The moments used to be worked out
 * from a bare draft, so the form of a reminder that was resting promised "Suena mañana 09:00",
 * and one put off until Friday promised its rule's own hour. [standing] is what the row says
 * that a draft cannot: a pause is said instead of any moment — in the plain ink, because
 * nothing here is next — a snooze that stands is the first moment, in its own words, with a
 * line under it saying which edit would take it away; and a snooze this very edit drops is
 * said before the button is pressed rather than found out on Home afterwards.
 */
@Composable
fun UpcomingLine(
    upcoming: List<NextFire>,
    today: LocalDate,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    /** The draft's "Vuelve", for the one shape whose next moments are not the whole story. */
    recurrence: Recurrence = Recurrence.None,
    /** The draft can produce no moment at all: [dev.rwilco.model.cannotRing]. */
    cannotRing: Boolean = false,
    /** The person's own hours, for the one thing that decides whether the first moment is heard. */
    dayShape: DayShape = DayShape.DEFAULT,
    /** What the draft asked to happen, so silence is only mentioned where it takes something away. */
    actions: Set<Action> = emptySet(),
    /** A pause, or a snooze this edit keeps or drops: see [Standing]. */
    standing: Standing = Standing.Plain,
) {
    if (standing == Standing.Paused) {
        Text(
            text = stringResource(R.string.editor_standing_paused),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val words = rememberWords()
    val rest = MaterialTheme.colorScheme.onSurfaceVariant
    // What the edit does to a snooze, under the line: kept until the "when" is touched, or
    // already on its way out with this save — said even when nothing else is coming, which is
    // when losing it matters most.
    val note = when (standing) {
        Standing.SnoozeKept -> stringResource(R.string.editor_standing_keeps)
        is Standing.SnoozeDropped -> stringResource(
            R.string.editor_standing_drops,
            standing.place?.let { snoozePlacePhrase(words, it) }
                ?: standing.until?.let { momentReading(words, NextFire.Scheduled(it, null), today, zone) }.orEmpty(),
        )
        else -> null
    }
    if (upcoming.isEmpty()) {
        if (!cannotRing && note == null) return
        Column(modifier = modifier) {
            if (cannotRing) {
                Text(
                    text = stringResource(R.string.editor_will_never_ring),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (note != null) Text(text = note, style = MaterialTheme.typography.labelMedium, color = rest)
        }
        return
    }
    val sep = words.get(R.string.common_separator)
    val first = MaterialTheme.colorScheme.primary
    val readings = upcoming.map { momentReading(words, it, today, zone) }
    // A routine's one moment is its span running out — "vence", not "suena" — because the
    // rules on the form are questions and none of them is what this line is about.
    val due = recurrence is Recurrence.Since
    // Asleep at the moment it is for, with something to be silenced: the two halves of the
    // promise this line could not keep. A place has no moment and cannot be asked.
    val plan = firingPlan(actions)
    val hushed = (plan.sound || plan.vibrate) &&
        (upcoming.first().moment?.let { !dayShape.awakeAt(it, zone) } == true)
    // A snooze that stands is the next thing this rings at, and it is said as what it is: the
    // moment is somebody's answer to a ring, not the rules' own.
    val next = upcoming.first()
    val putOff = (next is NextFire.Scheduled && next.snoozed) || (next is NextFire.WhenAt && next.snoozed)
    val firstLine = when {
        putOff && next is NextFire.WhenAt -> stringResource(R.string.home_snoozed_until, snoozePlacePhrase(words, next.trigger))
        putOff -> stringResource(R.string.home_snoozed_until, readings.first())
        else -> stringResource(
            when {
                due && hushed -> R.string.editor_will_be_due_hushed
                due -> R.string.editor_will_be_due
                hushed -> R.string.editor_will_ring_hushed
                else -> R.string.editor_will_ring
            },
            readings.first(),
        )
    }
    // What the moments are, once said, needs one clause after them for two of the three shapes
    // a "Vuelve" can have: see [UpcomingTail], which is where the reasoning for each lives.
    val tail = upcomingTail(recurrence, readings.size)
    Column(modifier = modifier) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = first, fontWeight = FontWeight.SemiBold)) { append(firstLine) }
                if (tail == UpcomingTail.UNTIL_DONE) {
                    withStyle(SpanStyle(color = rest)) {
                        append(sep + words.get(R.string.editor_will_ring_then, readings[1]))
                        append(sep + words.get(R.string.editor_will_ring_until_done))
                        append(sep + words.get(R.string.editor_will_ring_then_returns, recurrenceLabel(words, recurrence, today)))
                    }
                } else {
                    for (reading in readings.drop(1)) {
                        withStyle(SpanStyle(color = rest)) { append(sep + words.get(R.string.editor_will_ring_then, reading)) }
                    }
                    // And with no "Vuelve", both halves of what those later ones are, in the
                    // shape the span above uses: they go on until it is done **once**, and then
                    // they stop for good. Either half alone is the misreading — the list on its
                    // own reads as a rhythm, and "hasta que lo hagas" alone leaves open what
                    // happens after.
                    if (tail == UpcomingTail.UNLESS_DONE) {
                        withStyle(SpanStyle(color = rest)) {
                            append(sep + words.get(R.string.editor_will_ring_until_done_once))
                            append(sep + words.get(R.string.editor_will_ring_never_again))
                        }
                    }
                }
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (note != null) Text(text = note, style = MaterialTheme.typography.labelMedium, color = rest)
    }
}

private fun momentReading(words: Words, next: NextFire, today: LocalDate, zone: ZoneId): String = when (next) {
    is NextFire.Scheduled -> {
        val here = next.at.atZone(zone)
        dayWord(words, here.toLocalDate(), today) + " " + TimeText.time(here.toLocalTime(), words.is24h, words.locale)
    }
    // The window, never the draw: a random reminder that announces its time is not random.
    is NextFire.Sometime -> {
        val from = next.windowStart.atZone(zone)
        val to = next.windowEnd.atZone(zone)
        words.get(
            R.string.editor_will_ring_sometime,
            dayWord(words, from.toLocalDate(), today),
            TimeText.window(from.toLocalTime(), to.toLocalTime(), words.is24h, words.locale),
        )
    }
    // A place, as the first and only thing: "Suena al llegar a Casa".
    is NextFire.WhenAt -> placePhraseOf(words, next.trigger)
}
