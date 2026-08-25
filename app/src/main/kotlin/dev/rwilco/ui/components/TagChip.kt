package dev.rwilco.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import dev.rwilco.ui.theme.Tokens

/**
 * A tag: neutral by design (family colours keep their meaning, amber keeps its own), outlined
 * when off and inverted when on — the ink and the paper swap, the way the Save button already
 * does. Three shades of the same grey never said "on" from arm's length; this does.
 * Tall enough for a thumb.
 */
@Composable
fun TagChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** The second thing this chip can do, on a held finger; null when it does only one. */
    onHold: (() -> Unit)? = null,
    holdIcon: ImageVector = Icons.Outlined.Edit,
    holdLabel: String = "",
) {
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    val hold = rememberHoldState()
    val tap = {
        haptics.perform(if (selected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
        onClick()
    }
    FilterChip(
        selected = selected,
        // The chip keeps its own click; a hold that has just completed stands it down.
        onClick = { if (!hold.held) tap() },
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = scheme.surfaceContainerLow,
            labelColor = scheme.onSurfaceVariant,
            selectedContainerColor = scheme.onSurface,
            selectedLabelColor = scheme.surface,
        ),
        border = if (selected) null else BorderStroke(Tokens.strokes.control, scheme.outline),
        modifier = modifier
            .heightIn(min = 40.dp)
            .then(if (onHold == null) Modifier else Modifier.holdable(holdIcon, holdLabel, onHold, hold)),
    )
}

/** A tag on a card: read-only and smaller. */
@Composable
fun TagLabel(label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
