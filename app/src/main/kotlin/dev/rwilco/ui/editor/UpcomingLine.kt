package dev.rwilco.ui.editor

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
import dev.rwilco.model.countsFromRinging
import dev.rwilco.model.firingPlan
import dev.rwilco.model.moment
import dev.rwilco.ui.format.recurrenceLabel
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.Words
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.format.placePhraseOf
import dev.rwilco.ui.format.rememberWords
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
) {
    if (upcoming.isEmpty()) {
        if (cannotRing) {
            Text(
                text = stringResource(R.string.editor_will_never_ring),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
                modifier = modifier,
            )
        }
        return
    }
    val words = rememberWords()
    val sep = words.get(R.string.common_separator)
    val first = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.onSurfaceVariant
    val readings = upcoming.map { momentReading(words, it, today, zone) }
    // A routine's one moment is its span running out — "vence", not "suena" — because the
    // rules on the form are questions and none of them is what this line is about.
    val due = recurrence is Recurrence.Since
    // Asleep at the moment it is for, with something to be silenced: the two halves of the
    // promise this line could not keep. A place has no moment and cannot be asked.
    val plan = firingPlan(actions)
    val hushed = (plan.sound || plan.vibrate) &&
        (upcoming.first().moment?.let { !dayShape.awakeAt(it, zone) } == true)
    val firstLine = stringResource(
        when {
            due && hushed -> R.string.editor_will_be_due_hushed
            due -> R.string.editor_will_be_due
            hushed -> R.string.editor_will_ring_hushed
            else -> R.string.editor_will_ring
        },
        readings.first(),
    )
    // **A span counted from the "hecho" is said as one** (0.68.0). Its next moments are the
    // rules' own — "a las 20:45", every day — because nothing has been dealt with yet, and
    // the line read "luego vie 4 sept · luego sáb 5 sept" under a reminder that says "vuelve
    // cada 4 años": true, and read as the years being missing. So after the first moment it
    // says what actually happens: the rules go on until it is done, and then the span.
    val untilDone = recurrence is Recurrence.After && !recurrence.countsFromRinging && readings.size > 1
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = first, fontWeight = FontWeight.SemiBold)) { append(firstLine) }
            if (untilDone) {
                withStyle(SpanStyle(color = rest)) {
                    append(sep + words.get(R.string.editor_will_ring_then, readings[1]))
                    append(sep + words.get(R.string.editor_will_ring_until_done))
                    append(sep + words.get(R.string.editor_will_ring_then_returns, recurrenceLabel(words, recurrence, today)))
                }
            } else {
                for (reading in readings.drop(1)) {
                    withStyle(SpanStyle(color = rest)) { append(sep + words.get(R.string.editor_will_ring_then, reading)) }
                }
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
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
