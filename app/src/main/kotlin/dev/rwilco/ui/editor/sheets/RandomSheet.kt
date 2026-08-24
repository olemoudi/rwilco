package dev.rwilco.ui.editor.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.MAX_RANDOM_TIMES
import dev.rwilco.model.MIN_RANDOM_TIMES
import dev.rwilco.model.Period
import dev.rwilco.model.RandomDraw
import dev.rwilco.model.Trigger
import dev.rwilco.ui.components.DayToggles
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.components.SheetScaffold
import dev.rwilco.ui.components.Stepper
import dev.rwilco.ui.components.TimeField
import dev.rwilco.ui.theme.Tokens
import java.time.DayOfWeek
import java.time.LocalTime

/** How many surprises, how often, and the hours they may land in. */
@Composable
fun RandomSheet(
    initial: Trigger.Random?,
    onConfirm: (Trigger.Random) -> Unit,
    onDismiss: () -> Unit,
) {
    var times by rememberSaveable { mutableIntStateOf(initial?.timesPer ?: 1) }
    var period by rememberSaveable { mutableStateOf((initial?.period ?: Period.DAY).name) }
    var from by rememberTime(initial?.from ?: LocalTime.of(10, 0))
    var to by rememberTime(initial?.to ?: LocalTime.of(20, 0))
    var days by rememberSaveable { mutableStateOf(initial?.days?.map { it.name }?.toSet() ?: emptySet()) }
    val selectedDays = days.map(DayOfWeek::valueOf).toSet()
    val candidate = Trigger.Random(times, Period.valueOf(period), from, to, selectedDays)
    val windowOk = RandomDraw.windowMinutes(candidate) >= times

    SheetScaffold(
        title = stringResource(R.string.kind_random),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(candidate) },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
        confirmEnabled = windowOk,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.random_times_label),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Stepper(
                valueLabel = times.toString(),
                onDecrement = { times = (times - 1).coerceAtLeast(MIN_RANDOM_TIMES) },
                onIncrement = { times = (times + 1).coerceAtMost(MAX_RANDOM_TIMES) },
                decrementEnabled = times > MIN_RANDOM_TIMES,
                incrementEnabled = times < MAX_RANDOM_TIMES,
            )
        }
        SegmentedChoice(
            options = listOf(stringResource(R.string.random_per_day), stringResource(R.string.random_per_week)),
            selectedIndex = if (period == Period.DAY.name) 0 else 1,
            onSelect = { period = if (it == 0) Period.DAY.name else Period.WEEK.name },
        )
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            Text(stringResource(R.string.random_window_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
                TimeField(time = from, onChange = { from = it }, label = stringResource(R.string.random_from), modifier = Modifier.weight(1f))
                TimeField(time = to, onChange = { to = it }, label = stringResource(R.string.random_to), modifier = Modifier.weight(1f))
            }
            if (!windowOk) {
                Text(stringResource(R.string.random_window_error), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            Text(stringResource(R.string.random_days_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DayToggles(selected = selectedDays, onToggle = { day -> days = if (day.name in days) days - day.name else days + day.name })
            Text(
                text = stringResource(R.string.random_days_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
