package dev.rwilco.ui.editor.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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

internal val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
internal val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
internal val EVERY_DAY = DayOfWeek.entries.toSet()

/** A time and the days it repeats on. */
@Composable
fun RepeatTimeSheet(
    initial: Trigger.AtTime?,
    onConfirm: (Trigger.AtTime) -> Unit,
    onDismiss: () -> Unit,
) {
    var time by rememberTime(initial?.time ?: LocalTime.of(9, 0))
    var days by rememberSaveable { mutableStateOf(initial?.days?.map { it.name }?.toSet() ?: EVERY_DAY.map { it.name }.toSet()) }
    val selected = days.map(DayOfWeek::valueOf).toSet()

    SheetScaffold(
        title = stringResource(R.string.kind_repeat_time),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(Trigger.AtTime(time, selected)) },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
        confirmEnabled = selected.isNotEmpty(),
    ) {
        TimeField(time = time, onChange = { time = it }, label = stringResource(R.string.sheet_time), modifier = Modifier.fillMaxWidth())
        Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            PresetChip(stringResource(R.string.trigger_every_day), selected = selected == EVERY_DAY, onClick = { days = EVERY_DAY.map { it.name }.toSet() })
            PresetChip(stringResource(R.string.trigger_weekdays), selected = selected == WEEKDAYS, onClick = { days = WEEKDAYS.map { it.name }.toSet() })
            PresetChip(stringResource(R.string.trigger_weekends), selected = selected == WEEKEND, onClick = { days = WEEKEND.map { it.name }.toSet() })
        }
        DayToggles(
            selected = selected,
            onToggle = { day -> days = if (day.name in days) days - day.name else days + day.name },
        )
    }
}
