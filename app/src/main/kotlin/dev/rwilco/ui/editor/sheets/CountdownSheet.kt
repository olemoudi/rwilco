package dev.rwilco.ui.editor.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.Trigger
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.components.SheetScaffold
import dev.rwilco.ui.components.Stepper
import dev.rwilco.ui.components.rememberNow
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.rememberIs24h
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import java.time.Clock
import java.time.temporal.ChronoUnit

private val PRESET_MINUTES = listOf(5, 15, 30, 60, 120, 240)

/**
 * "In half an hour." Produces a plain date-time trigger: the countdown is how it was picked,
 * not what is stored. The clock is read at confirm time so a slow thumb does not lose seconds.
 */
@Composable
fun CountdownSheet(clock: Clock, onConfirm: (Trigger.AtDateTime) -> Unit, onDismiss: () -> Unit) {
    var minutes by rememberSaveable { mutableIntStateOf(30) }
    val now by rememberNow(60_000, clock)
    val ringsAt = now.atZone(clock.zone).plusMinutes(minutes.toLong()).truncatedTo(ChronoUnit.MINUTES)
    val locale = currentLocale()
    val is24h = rememberIs24h()
    val hours = minutes / 60
    val rest = minutes % 60

    SheetScaffold(
        title = stringResource(R.string.kind_countdown),
        onDismiss = onDismiss,
        onConfirm = {
            val at = clock.instant().atZone(clock.zone).plusMinutes(minutes.toLong()).truncatedTo(ChronoUnit.MINUTES)
            onConfirm(Trigger.AtDateTime(at.toLocalDateTime()))
        },
        confirmLabel = stringResource(R.string.sheet_add),
        confirmEnabled = minutes > 0,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            for (preset in PRESET_MINUTES) {
                val label = if (preset < 60) stringResource(R.string.countdown_minutes, preset) else stringResource(R.string.countdown_hours, preset / 60)
                PresetChip(label = label, selected = minutes == preset, onClick = { minutes = preset })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.countdown_hours_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Stepper(
                    valueLabel = hours.toString(),
                    onDecrement = { minutes = (minutes - 60).coerceAtLeast(rest) },
                    onIncrement = { minutes = (minutes + 60).coerceAtMost(24 * 60 * 7) },
                    decrementEnabled = hours > 0,
                )
            }
            Spacer(Modifier.width(Tokens.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.countdown_minutes_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Stepper(
                    valueLabel = rest.toString(),
                    onDecrement = { minutes = (minutes - 5).coerceAtLeast(0) },
                    onIncrement = { minutes = minutes + 5 },
                    decrementEnabled = rest > 0,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.sheet_rings_at_default, ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = TimeText.time(ringsAt.toLocalTime(), is24h, locale), style = MonoStyles.time)
        }
    }
}
