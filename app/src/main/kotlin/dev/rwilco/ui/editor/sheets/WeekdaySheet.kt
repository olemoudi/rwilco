package dev.rwilco.ui.editor.sheets

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import dev.rwilco.ui.theme.Tokens
import java.time.DayOfWeek

/**
 * The days of the week and nothing else: "los viernes".
 *
 * [TimeOfDaySheet] with the hour taken out, which is exactly what it is for. An hour and the
 * days it counts on are two answers to two questions, and having one control for both meant
 * somebody who only wanted to say "los viernes" had to invent an hour to say it with. Written as
 * two triggers joined by "a la vez", the sentence reads back as what was meant and either half
 * can be changed without going looking for the other.
 *
 * No "cualquier día" chip, unlike the sheets that read an empty set as every day: here the days
 * *are* the trigger, so none of them is nothing at all — the confirm button says so by staying
 * out of reach until one is marked.
 */
@Composable
fun WeekdaySheet(
    initial: Trigger.Weekday?,
    combining: Boolean,
    onConfirm: (Trigger) -> Unit,
    onDismiss: () -> Unit,
) {
    var days by rememberSaveable { mutableStateOf(initial?.days?.map { it.name }?.toSet() ?: emptySet()) }
    val selected = days.map(DayOfWeek::valueOf).toSet()

    SheetScaffold(
        title = stringResource(R.string.kind_weekday),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(Trigger.Weekday(selected)) },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
        confirmEnabled = selected.isNotEmpty(),
    ) {
        Text(
            text = stringResource(if (combining) R.string.weekday_hint_together else R.string.weekday_hint_alone),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            PresetChip(stringResource(R.string.trigger_weekdays), selected = selected == WEEKDAYS, onClick = { days = WEEKDAYS.map { it.name }.toSet() })
            PresetChip(stringResource(R.string.trigger_weekends), selected = selected == WEEKEND, onClick = { days = WEEKEND.map { it.name }.toSet() })
        }
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            DayToggles(selected = selected, onToggle = { day -> days = if (day.name in days) days - day.name else days + day.name })
            Text(
                text = stringResource(R.string.weekday_days_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
