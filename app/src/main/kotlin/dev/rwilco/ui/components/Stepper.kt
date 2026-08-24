package dev.rwilco.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens

/** −/+ around a mono reading; each step ticks. */
@Composable
fun Stepper(
    valueLabel: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
    decrementEnabled: Boolean = true,
    incrementEnabled: Boolean = true,
) {
    val haptics = Tokens.haptics
    val colors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = {
                haptics.perform(HapticFeedbackType.SegmentTick)
                onDecrement()
            },
            enabled = decrementEnabled,
            colors = colors,
            modifier = Modifier.size(Tokens.sizes.touch),
        ) { Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.stepper_less)) }
        Text(
            text = valueLabel,
            style = MonoStyles.time,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        FilledTonalIconButton(
            onClick = {
                haptics.perform(HapticFeedbackType.SegmentTick)
                onIncrement()
            },
            enabled = incrementEnabled,
            colors = colors,
            modifier = Modifier.size(Tokens.sizes.touch),
        ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.stepper_more)) }
    }
}
