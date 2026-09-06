package dev.rwilco.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.ui.components.calendar.MonthCalendar
import dev.rwilco.ui.editor.sheets.rememberDate
import dev.rwilco.ui.editor.sheets.rememberTime
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * "Posponer · a una fecha concreta": the one snooze offer that is not a length.
 *
 * Every other offer is a step from now — ten minutes, two hours, the weekend — which is right
 * for the answers people give in the second after a ring and wrong for the one they give a week
 * ahead: "esto, el 3 de noviembre". So this is the same calendar the date tile uses and the
 * same hour field, and what it hands back is a moment rather than a shape.
 *
 * It opens on today at the reminders' own hour, and the button waits while the moment chosen is
 * behind us: a snooze into the past is a ring that arrives the instant the sheet closes.
 */
@Composable
fun SnoozeUntilSheet(
    now: ZonedDateTime,
    defaultTime: LocalTime,
    onConfirm: (Instant) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = now.toLocalDate()
    // Opens on the first day that hour is still ahead on: at nine in the morning "posponer
    // hasta" means today, and at nine at night it means tomorrow — a sheet that opens on a
    // moment already past opens with its button greyed out and an error under it.
    val opensOn = if (today.atTime(defaultTime).atZone(now.zone).toInstant() > now.toInstant()) today else today.plusDays(1)
    var date by rememberDate(opensOn)
    var time by rememberTime(defaultTime)
    val untouched = remember { listOf(date, time) }
    val chosen = date.atTime(time).atZone(now.zone).toInstant()
    val past = !chosen.isAfter(now.toInstant())
    SheetScaffold(
        title = stringResource(R.string.snooze_until_title),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(chosen) },
        confirmEnabled = !past,
        dirty = listOf(date, time) != untouched,
    ) {
        MonthCalendar(selected = date, today = today, onSelect = { date = it })
        TimeField(time = time, onChange = { time = it }, label = stringResource(R.string.sheet_time), modifier = Modifier.fillMaxWidth())
        if (past) {
            Text(
                text = stringResource(R.string.snooze_until_past),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
