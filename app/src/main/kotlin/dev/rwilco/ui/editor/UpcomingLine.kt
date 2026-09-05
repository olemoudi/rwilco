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
import dev.rwilco.model.NextFire
import dev.rwilco.model.Recurrence
import dev.rwilco.model.countsFromRinging
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
 */
@Composable
fun UpcomingLine(
    upcoming: List<NextFire>,
    today: LocalDate,
    zone: ZoneId,
    modifier: Modifier = Modifier,
    /** The draft's "Vuelve", for the one shape whose next moments are not the whole story. */
    recurrence: Recurrence = Recurrence.None,
) {
    if (upcoming.isEmpty()) return
    val words = rememberWords()
    val first = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.onSurfaceVariant
    val readings = upcoming.map { momentReading(words, it, today, zone) }
    val firstLine = stringResource(R.string.editor_will_ring, readings.first())
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
                    append(" · " + words.get(R.string.editor_will_ring_then, readings[1]))
                    append(" · " + words.get(R.string.editor_will_ring_until_done))
                    append(" · " + words.get(R.string.editor_will_ring_then_returns, recurrenceLabel(words, recurrence, today)))
                }
            } else {
                for (reading in readings.drop(1)) {
                    withStyle(SpanStyle(color = rest)) { append(" · " + words.get(R.string.editor_will_ring_then, reading)) }
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
