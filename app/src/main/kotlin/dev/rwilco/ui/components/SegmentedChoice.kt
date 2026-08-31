package dev.rwilco.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import dev.rwilco.ui.theme.Tokens

/** One of a few: theme mode, arriving/leaving, per day/per week. */
@Composable
fun SegmentedChoice(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = Tokens.haptics
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth().heightIn(min = Tokens.sizes.touch)) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = {
                    haptics.perform(HapticFeedbackType.SegmentTick)
                    onSelect(index)
                },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                // The chosen segment is inverted, like every other "on" in the app.
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.onSurface,
                    activeContentColor = MaterialTheme.colorScheme.surface,
                    activeBorderColor = MaterialTheme.colorScheme.onSurface,
                    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    inactiveBorderColor = MaterialTheme.colorScheme.outline,
                ),
                label = { Text(label, style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}
