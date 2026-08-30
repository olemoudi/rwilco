package dev.rwilco.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextOverflow
import dev.rwilco.ui.theme.Tokens
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

/**
 * A shortcut that fills the form in one tap ("Mañana 09:00", "30 min", "Laborables"). When the
 * form already says what the chip would set, the chip is inverted — ink on paper swapped —
 * so the current answer is the loudest thing in the row.
 */
@Composable
fun PresetChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    /** The second thing this chip can do, on a held finger; null when it does only one. */
    onHold: (() -> Unit)? = null,
    holdIcon: ImageVector = Icons.Outlined.Edit,
    holdLabel: String = "",
    /** A glyph before the label, for a chip that is a different kind of thing from its neighbours. */
    leadingIcon: ImageVector? = null,
    leadingIconDescription: String? = null,
) {
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    val hold = rememberHoldState()
    val tap = {
        haptics.perform(HapticFeedbackType.Confirm)
        onClick()
    }
    AssistChip(
        // The chip keeps its own click; a hold that has just completed stands it down.
        onClick = { if (!hold.held) tap() },
        label = { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = leadingIcon?.let {
            { Icon(it, contentDescription = leadingIconDescription, modifier = Modifier.size(AssistChipDefaults.IconSize)) }
        },
        shape = MaterialTheme.shapes.small,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) scheme.onSurface else scheme.surfaceContainerHigh,
            labelColor = if (selected) scheme.surface else scheme.onSurface,
            leadingIconContentColor = if (selected) scheme.surface else scheme.onSurface,
        ),
        border = if (selected) null else BorderStroke(Tokens.strokes.control, scheme.outline),
        modifier = modifier
            .heightIn(min = Tokens.sizes.touch)
            // The inverted colours are the whole of "on" for eyes; a screen reader gets the word.
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
            .then(if (onHold == null) Modifier else Modifier.holdable(holdIcon, holdLabel, onHold, hold)),
    )
}
