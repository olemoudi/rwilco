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
 * A moment, picked off a calendar: one day, one hour, one [Instant] handed back.
 *
 * Two questions in the app ask it. **"Posponer · a una fecha concreta"** is the one snooze offer
 * that is not a length: every other one is a step from now — ten minutes, two hours, the weekend
 * — which is right for the answers people give in the second after a ring and wrong for the one
 * they give a week ahead ("esto, el 3 de noviembre"). It refuses the past: a snooze into the
 * past is a ring that arrives the instant the sheet closes. And **where a routine's count runs
 * from** ([Recurrence.Since.startsAt]) — "la última vez fue el lunes", "empieza el 1 de
 * octubre" — which is an anchor and not a ring, and so takes either side of now ([allowPast]).
 * Same calendar, same hour field; one sheet, wearing whichever [title] asked for it.
 *
 * It opens on today at the reminders' own hour — on the first day that hour is still ahead on,
 * when the past is refused: at nine at night "posponer hasta" means tomorrow, and a sheet that
 * opens on a moment already past opens with its button greyed out and an error under it.
 */
@Composable
fun MomentSheet(
    now: ZonedDateTime,
    defaultTime: LocalTime,
    onConfirm: (Instant) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.snooze_until_title),
    /** What the button says while the moment chosen is behind us. */
    pastNote: String = stringResource(R.string.snooze_until_past),
    /** Whether a moment behind us is an answer: an anchor's is, a snooze's is not. */
    allowPast: Boolean = false,
) {
    val today = now.toLocalDate()
    val opensOn = if (allowPast || today.atTime(defaultTime).atZone(now.zone).toInstant() > now.toInstant()) today else today.plusDays(1)
    var date by rememberDate(opensOn)
    var time by rememberTime(defaultTime)
    val untouched = remember { listOf(date, time) }
    val chosen = date.atTime(time).atZone(now.zone).toInstant()
    val past = !allowPast && !chosen.isAfter(now.toInstant())
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
