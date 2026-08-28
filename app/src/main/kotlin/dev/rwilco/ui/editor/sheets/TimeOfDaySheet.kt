package dev.rwilco.ui.editor.sheets

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
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
import dev.rwilco.model.Trigger
import dev.rwilco.ui.components.DayToggles
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.components.SheetScaffold
import dev.rwilco.ui.components.TimeField
import dev.rwilco.ui.theme.Tokens
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * An hour of the day, and which days it counts on: "a las 09:00, de lunes a viernes".
 *
 * The point [IntervalSheet] is a stretch of, and the sheet is deliberately that one with a field
 * taken out — same days, same chips, same shape of hint — because they are the same question
 * asked about an instant instead of a span.
 *
 * The hint says which of the two things it is about to be, exactly as the window's does. What it
 * is *for* is the second: alone it is a daily alarm and "Vuelve" says that better, but in a set
 * it is the moment everything else has to be true around — "a las 09:00, y a la vez entre el 1 y
 * el 15", "a las 09:00, y a la vez en casa" — and nothing else in the app can be that.
 */
@Composable
fun TimeOfDaySheet(
    initial: Trigger.TimeOfDay?,
    defaultTime: LocalTime,
    combining: Boolean,
    onConfirm: (Trigger) -> Unit,
    onDismiss: () -> Unit,
) {
    var time by rememberTime(initial?.time ?: defaultTime)
    var days by rememberSaveable { mutableStateOf(initial?.days?.map { it.name }?.toSet() ?: emptySet()) }
    val selected = days.map(DayOfWeek::valueOf).toSet()

    SheetScaffold(
        title = stringResource(R.string.kind_time_of_day),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(Trigger.TimeOfDay(time, selected)) },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
    ) {
        Text(
            text = stringResource(if (combining) R.string.time_of_day_hint_together else R.string.time_of_day_hint_alone),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TimeField(
            time = time,
            onChange = { time = it },
            label = stringResource(R.string.sheet_time),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            PresetChip(stringResource(R.string.trigger_any_day), selected = selected.isEmpty(), onClick = { days = emptySet() })
            PresetChip(stringResource(R.string.trigger_weekdays), selected = selected == WEEKDAYS, onClick = { days = WEEKDAYS.map { it.name }.toSet() })
            PresetChip(stringResource(R.string.trigger_weekends), selected = selected == WEEKEND, onClick = { days = WEEKEND.map { it.name }.toSet() })
        }
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            DayToggles(selected = selected, onToggle = { day -> days = if (day.name in days) days - day.name else days + day.name })
            Text(
                text = stringResource(R.string.random_days_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
