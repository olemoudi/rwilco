package dev.rwilco.ui.editor.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.Trigger
import dev.rwilco.ui.components.SheetScaffold
import dev.rwilco.ui.components.calendar.MonthCalendar
import dev.rwilco.ui.theme.Tokens
import java.time.LocalDate

/**
 * Two days of the calendar and nothing else: "entre el 1 y el 15".
 *
 * No hour, and no field for one — that is the point of the tile rather than an omission. What it
 * is *for* is the state: "al llegar a casa, y a la vez entre el 1 y el 15" is the sentence a
 * single date could never write, and the hint says which of the two things it is about to be,
 * exactly as the window tile's does.
 *
 * Two calendars, one under the other, rather than one calendar in two modes. A single grid that
 * means "start" until you tap and then "end" is a control whose state you cannot see, and the
 * day you tapped first is the one you cannot get back without starting again. The end's grid
 * cannot go before the start's, so the range is well formed by construction and the save button
 * never has to say no.
 */
@Composable
fun DateRangeSheet(
    initial: Trigger.DateRange?,
    today: LocalDate,
    combining: Boolean,
    onConfirm: (Trigger) -> Unit,
    onDismiss: () -> Unit,
) {
    var from by rememberDate(initial?.from ?: today)
    var to by rememberDate(initial?.to ?: today.plusDays(7))

    SheetScaffold(
        title = stringResource(R.string.kind_date_range),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(Trigger.DateRange(from, to)) },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
    ) {
        Text(
            text = stringResource(if (combining) R.string.date_range_hint_together else R.string.date_range_hint_alone),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            Label(stringResource(R.string.date_range_from))
            MonthCalendar(
                selected = from,
                today = today,
                onSelect = {
                    from = it
                    // The end follows the start rather than fighting it: dragging the start past
                    // the end would otherwise leave a range with nothing in it and a grid whose
                    // selection had silently vanished off the top of it.
                    if (to < it) to = it
                },
                minDate = null,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            Label(stringResource(R.string.date_range_to))
            MonthCalendar(selected = to, today = today, onSelect = { to = it }, minDate = from)
        }
    }
}
