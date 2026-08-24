package dev.rwilco.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.rememberIs24h
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import java.time.LocalTime

/** A time as a big mono reading you tap to change; the dial opens in a dialog. */
@Composable
fun TimeField(
    time: LocalTime,
    onChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val locale = currentLocale()
    val is24h = rememberIs24h()
    Surface(
        onClick = { picking = true },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.heightIn(min = Tokens.sizes.control),
    ) {
        Column(modifier = Modifier.padding(horizontal = Tokens.spacing.lg, vertical = Tokens.spacing.sm)) {
            if (label != null) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(TimeText.time(time, is24h, locale), style = MonoStyles.time)
        }
    }
    if (picking) {
        TimePickerDialog(
            initial = time,
            onDismiss = { picking = false },
            onConfirm = { picked ->
                picking = false
                onChange(picked)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val is24h = rememberIs24h()
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24h)
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                Text(stringResource(R.string.sheet_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.sheet_cancel)) }
        },
        text = {
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = scheme.surfaceContainerHighest,
                    selectorColor = scheme.primary,
                    clockDialSelectedContentColor = scheme.onPrimary,
                    clockDialUnselectedContentColor = scheme.onSurface,
                    timeSelectorSelectedContainerColor = scheme.primaryContainer,
                    timeSelectorSelectedContentColor = scheme.onPrimaryContainer,
                    timeSelectorUnselectedContainerColor = scheme.surfaceContainerHighest,
                    timeSelectorUnselectedContentColor = scheme.onSurface,
                    periodSelectorSelectedContainerColor = scheme.surfaceContainerHighest,
                    periodSelectorSelectedContentColor = scheme.onSurface,
                ),
            )
        },
        containerColor = scheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
    )
}
