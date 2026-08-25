package dev.rwilco.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.rwilco.R
import dev.rwilco.model.Preset
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.presetColor
import dev.rwilco.ui.theme.presetWash

/**
 * What "New" asks once there is a preset to offer: from nothing, or from one of the shapes you
 * keep. It sits in the middle of the screen because it is a question, not a drawer — and with
 * no presets it never appears at all, so nobody meets a choice they cannot answer.
 *
 * Tapping a preset opens the editor already filled in rather than writing the reminder there
 * and then: a preset can hold a date that has since passed, and the form is where that gets
 * seen and fixed.
 */
@Composable
fun NewReminderChooser(
    presets: List<Preset>,
    onBlank: () -> Unit,
    onPreset: (Preset) -> Unit,
    onEditPreset: (Preset) -> Unit,
    onDismiss: () -> Unit,
) {
    var choosingPreset by remember { mutableStateOf(false) }
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = scheme.surfaceContainer,
            border = BorderStroke(Tokens.strokes.edge, scheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(spacing.lg)) {
                Text(
                    text = stringResource(if (choosingPreset) R.string.home_new_from_preset else R.string.home_new_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(spacing.lg))
                if (!choosingPreset) {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.md), modifier = Modifier.fillMaxWidth()) {
                        BigChoice(
                            icon = Icons.AutoMirrored.Outlined.Notes,
                            label = stringResource(R.string.home_new_blank),
                            onClick = onBlank,
                            modifier = Modifier.weight(1f),
                        )
                        BigChoice(
                            icon = Icons.Outlined.Bookmarks,
                            label = stringResource(R.string.home_new_preset),
                            onClick = { choosingPreset = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    // The ones actually used, at the top: a list of shapes is only fast if the
                    // one you always reach for is where your thumb already is.
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        items(presets, key = { it.id }) { preset ->
                            PresetButton(
                                preset = preset,
                                onClick = { onPreset(preset) },
                                onEdit = { onEditPreset(preset) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One of the two big answers. Square-ish and thumb-sized: this is the whole question. */
@Composable
private fun BigChoice(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = {
            haptics.perform(HapticFeedbackType.Confirm)
            onClick()
        },
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(Tokens.strokes.control, scheme.outline),
        modifier = modifier.heightIn(min = 128.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(Tokens.spacing.lg),
        ) {
            Icon(icon, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(Tokens.spacing.sm))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A preset as a button: its colour first, its name second. The colour is not decoration — it is
 * the handle, and after a week it is how the hand finds "la compra" without reading the row.
 */
@Composable
private fun PresetButton(preset: Preset, onClick: () -> Unit, onEdit: () -> Unit) {
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    val color = presetColor(preset.colorIndex)
    Surface(
        onClick = {
            haptics.perform(HapticFeedbackType.Confirm)
            onClick()
        },
        shape = MaterialTheme.shapes.medium,
        color = presetWash(preset.colorIndex),
        border = BorderStroke(Tokens.strokes.control, color.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = Tokens.spacing.md, top = Tokens.spacing.sm, bottom = Tokens.spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(Tokens.sizes.badge)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(Tokens.spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val summary = presetSummary(preset)
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.home_preset_edit),
                    tint = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "casa · 2 disparadores", or nothing when a preset is only a name. */
@Composable
private fun presetSummary(preset: Preset): String? {
    val parts = buildList {
        if (preset.tags.isNotEmpty()) add(preset.tags.joinToString(" · "))
        if (preset.rules.isNotEmpty()) {
            add(pluralStringResource(R.plurals.home_preset_triggers, preset.rules.size, preset.rules.size))
        }
    }
    return parts.joinToString(" · ").ifEmpty { null }
}
