package dev.rwilco.ui.editor.sheets

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
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.rememberIs24h
import java.time.LocalDate
import java.time.LocalTime

/** Just a day; it rings at the default time, which the sheet says so nobody is surprised. */
@Composable
fun DateOnlySheet(
    initial: Trigger.OnDate?,
    today: LocalDate,
    defaultTime: LocalTime,
    onConfirm: (Trigger.OnDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var date by rememberDate(initial?.date ?: today.plusDays(1))
    SheetScaffold(
        title = stringResource(R.string.kind_date),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(Trigger.OnDate(date)) },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
    ) {
        MonthCalendar(selected = date, today = today, onSelect = { date = it }, minDate = today)
        Text(
            text = stringResource(R.string.sheet_rings_at_default, TimeText.time(defaultTime, rememberIs24h(), currentLocale())),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
