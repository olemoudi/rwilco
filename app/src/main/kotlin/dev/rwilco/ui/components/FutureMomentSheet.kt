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
 * A moment ahead, picked off a calendar: one day, one hour, one [Instant] handed back.
 *
 * Two questions in the app ask it. **"Posponer · a una fecha concreta"** is the one snooze offer
 * that is not a length: every other one is a step from now — ten minutes, two hours, the weekend
 * — which is right for the answers people give in the second after a ring and wrong for the one
 * they give a week ahead ("esto, el 3 de noviembre"). And **"empieza en un momento futuro"** on
 * a routine, where the moment is not a ring at all but where the count starts
 * ([Recurrence.Since.startsAt]). Same calendar, same hour field, same refusal of the past — so
 * one sheet, wearing whichever [title] asked for it.
 *
 * It opens on today at the reminders' own hour, and the button waits while the moment chosen is
 * behind us: a snooze into the past is a ring that arrives the instant the sheet closes, and a
 * routine that starts in the past is one that starts now, said the long way round.
 */
@Composable
fun FutureMomentSheet(
    now: ZonedDateTime,
    defaultTime: LocalTime,
    onConfirm: (Instant) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.snooze_until_title),
    /** What the button says while the moment chosen is behind us. */
    pastNote: String = stringResource(R.string.snooze_until_past),
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
        title = title,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(chosen) },
        confirmEnabled = !past,
        dirty = listOf(date, time) != untouched,
    ) {
        MonthCalendar(selected = date, today = today, onSelect = { date = it })
        TimeField(time = time, onChange = { time = it }, label = stringResource(R.string.sheet_time), modifier = Modifier.fillMaxWidth())
        if (past) {
            Text(
                text = pastNote,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
