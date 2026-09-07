package dev.rwilco.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.Recurrence
import dev.rwilco.ui.components.FutureMomentSheet
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.format.recurrenceLabel
import dev.rwilco.ui.format.rememberWords
import dev.rwilco.ui.theme.Tokens
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Where a routine's count starts — the card only a routine has.
 *
 * A routine is a span since the last time it was done, and until it has been done once the span
 * has to run from *something*. That something used to be one thing with no say in it: the day
 * the routine was written. Which is right for "mover el coche" and wrong for everything that
 * begins on a date — "el filtro se cambió ayer", "esto empieza el 1 de octubre" — and there was
 * nowhere to say so, because a routine's "Vuelve" is already spent on how long the span is.
 *
 * Two answers, so two chips: **ahora mismo** ([Recurrence.Since.startsAt] null, which is what
 * every routine already on a phone says) and **un momento futuro**, picked off the same calendar
 * "posponer a una fecha" uses. The line underneath reads the whole arrangement back — "y luego
 * cada 21 días desde la última vez" — because the two halves of that sentence are on two
 * different cards and the sentence is the thing being written.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RoutineStartSection(
    /** The routine's own "Vuelve": [Recurrence.Since], which is what makes this card exist. */
    recurrence: Recurrence.Since,
    now: ZonedDateTime,
    today: LocalDate,
    defaultTime: LocalTime,
    onStart: (Instant?) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val words = rememberWords()
    val spacing = Tokens.spacing
    val startsAt = recurrence.startsAt
    Column {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            PresetChip(
                label = stringResource(R.string.routine_start_now),
                selected = startsAt == null,
                onClick = { onStart(null) },
            )
            // The chip wears the moment once there is one: a chip that still said "en un
            // momento futuro" over a date already chosen is a control that hides its own answer.
            val chosen = startsAt?.atZone(now.zone)
            PresetChip(
                leadingIcon = Icons.Outlined.CalendarMonth,
                label = chosen?.let {
                    dayWord(words, it.toLocalDate(), today) + " " + TimeText.time(it.toLocalTime(), words.is24h, words.locale)
                } ?: stringResource(R.string.routine_start_later),
                selected = startsAt != null,
                onClick = { picking = true },
            )
        }
        // The other half of the sentence, which lives on the card below this one: what is being
        // written is "empieza el 1 de octubre, y luego cada 21 días desde la última vez", and
        // nobody should have to scroll to read their own arrangement.
        Spacer(Modifier.height(spacing.sm))
        Text(
            text = stringResource(
                R.string.routine_start_then,
                // Mid-sentence, so it is not a sentence: the label is written to start one.
                recurrenceLabel(words, recurrence, today).replaceFirstChar { it.lowercase(words.locale) },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (picking) {
        FutureMomentSheet(
            now = now,
            defaultTime = defaultTime,
            title = stringResource(R.string.routine_start_title),
            pastNote = stringResource(R.string.routine_start_past),
            onConfirm = { at -> picking = false; onStart(at) },
            onDismiss = { picking = false },
        )
    }
}
