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
 * A stretch of the day: "de 17 a 19".
 *
 * The same two fields as the hours condition, and deliberately so — it is the same window, said
 * as a trigger. What it does depends on the company it keeps: on its own it rings when the
 * window opens, and under "a la vez" it is the state that makes "en la oficina, entre las cinco
 * y las siete" mean what it says. The sheet says which of the two it is about to be.
 */
@Composable
fun IntervalSheet(
    initial: Trigger.Interval?,
    combining: Boolean,
    onConfirm: (Trigger) -> Unit,
    onDismiss: () -> Unit,
) {
    var from by rememberTime(initial?.from ?: LocalTime.of(17, 0))
    var to by rememberTime(initial?.to ?: LocalTime.of(19, 0))
    var days by rememberSaveable { mutableStateOf(initial?.days?.map { it.name }?.toSet() ?: emptySet()) }
    val selected = days.map(DayOfWeek::valueOf).toSet()

    SheetScaffold(
        title = stringResource(R.string.kind_interval),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(Trigger.Interval(from, to, selected)) },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
        confirmEnabled = from != to,
    ) {
        Text(
            text = stringResource(if (combining) R.string.interval_hint_together else R.string.interval_hint_alone),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm), modifier = Modifier.fillMaxWidth()) {
            TimeField(time = from, onChange = { from = it }, label = stringResource(R.string.random_from), modifier = Modifier.weight(1f))
            TimeField(time = to, onChange = { to = it }, label = stringResource(R.string.random_to), modifier = Modifier.weight(1f))
        }
        if (from == to) {
            Text(
                text = stringResource(R.string.condition_window_error),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            PresetChip(stringResource(R.string.trigger_every_day), selected = selected.isEmpty(), onClick = { days = emptySet() })
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
