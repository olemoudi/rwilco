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
import dev.rwilco.model.MAX_COUNTDOWN_MINUTES
import dev.rwilco.model.MIN_COUNTDOWN_MINUTES
import dev.rwilco.model.Trigger
import dev.rwilco.model.countdownOf
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
 * "In half an hour." What is stored is the half hour; the clock starts when the reminder is
 * written. The line at the bottom is a preview of where that lands if it were saved now.
 */
@Composable
fun CountdownSheet(
    clock: Clock,
    initial: Trigger.Countdown?,
    onConfirm: (Trigger.Countdown) -> Unit,
    onDismiss: () -> Unit,
) {
    var minutes by rememberSaveable { mutableIntStateOf(initial?.minutes ?: 30) }
    val now by rememberNow(60_000, clock)
    val ringsAt = now.atZone(clock.zone).plusMinutes(minutes.toLong()).truncatedTo(ChronoUnit.MINUTES)
    val locale = currentLocale()
    val is24h = rememberIs24h()
    val hours = minutes / 60
    val rest = minutes % 60

    SheetScaffold(
        title = stringResource(R.string.kind_countdown),
        onDismiss = onDismiss,
        // A length, not a moment: the clock starts when the reminder is saved, which is what
        // lets a preset hold "in half an hour" and mean it every time — and a length left alone
        // keeps the clock it already had, so looking at a running timer does not restart it.
        onConfirm = { onConfirm(countdownOf(minutes, initial)) },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
        confirmEnabled = minutes in MIN_COUNTDOWN_MINUTES..MAX_COUNTDOWN_MINUTES,
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
                    onIncrement = { minutes = (minutes + 60).coerceAtMost(MAX_COUNTDOWN_MINUTES) },
                    decrementEnabled = hours > 0,
                    incrementEnabled = minutes + 60 <= MAX_COUNTDOWN_MINUTES,
                )
            }
            Spacer(Modifier.width(Tokens.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.countdown_minutes_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // One minute a step. It moved five at a time to make the long way round
                // quicker, and bought that by making three minutes, or one, impossible to ask
                // for: from the five-minute chip the only places to go were nought and ten.
                // The long way round is what the chips above are for.
                Stepper(
                    valueLabel = rest.toString(),
                    onDecrement = { minutes = (minutes - 1).coerceAtLeast(0) },
                    onIncrement = { minutes = (minutes + 1).coerceAtMost(MAX_COUNTDOWN_MINUTES) },
                    decrementEnabled = rest > 0,
                    incrementEnabled = minutes < MAX_COUNTDOWN_MINUTES,
                )
            }
        }
        if (minutes < MIN_COUNTDOWN_MINUTES) {
            Text(
                text = stringResource(R.string.countdown_zero_error),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
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
