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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
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
 * The row of shapes kept within reach, under the date.
 *
 * These are not the same act as "New": that one asks a question, and these answer it. One tap
 * writes the reminder and it is done — unless the preset left the words open, in which case one
 * tap asks for them and nothing else. The ones actually used come first, and the last thing in
 * the row is always the way to add another.
 */
@Composable
fun PinnedPresetsRow(
    presets: List<Preset>,
    onPick: (Preset) -> Unit,
    onManage: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(presets, key = { it.id }) { preset -> PinnedPresetButton(preset, onPick) }
        item(key = "add") { AddPinnedButton(onManage) }
    }
}

/** Small enough for a row of them, tall enough for a thumb: its colour, then its name. */
@Composable
private fun PinnedPresetButton(preset: Preset, onPick: (Preset) -> Unit) {
    val haptics = Tokens.haptics
    val color = presetColor(preset.colorIndex)
    Surface(
        onClick = {
            haptics.perform(HapticFeedbackType.Confirm)
            onPick(preset)
        },
        shape = MaterialTheme.shapes.small,
        color = presetWash(preset.colorIndex),
        border = BorderStroke(Tokens.strokes.control, color.copy(alpha = 0.55f)),
        modifier = Modifier.heightIn(min = 44.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Tokens.spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(Tokens.spacing.sm))
            Text(
                text = preset.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AddPinnedButton(onManage: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onManage,
        shape = MaterialTheme.shapes.small,
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(Tokens.strokes.control, scheme.outline),
        modifier = Modifier
            .heightIn(min = 44.dp)
            .width(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = stringResource(R.string.home_pin_manage),
                tint = scheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Which shapes get a button. A preset is kept whether or not it is here — this only says which
 * ones are worth a thumb's reach on the main screen.
 */
@Composable
fun PinPresetsPanel(
    presets: List<Preset>,
    onTogglePin: (Preset) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
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
                Text(stringResource(R.string.home_pin_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(spacing.sm))
                Text(
                    text = stringResource(R.string.home_pin_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(spacing.md))
                if (presets.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_pin_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(spacing.md))
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    for (preset in presets) {
                        val color = presetColor(preset.colorIndex)
                        Surface(
                            onClick = { onTogglePin(preset) },
                            shape = MaterialTheme.shapes.small,
                            // Pinned is inverted, like every other "on" in the app.
                            color = if (preset.pinned) scheme.onSurface else presetWash(preset.colorIndex),
                            border = if (preset.pinned) null else BorderStroke(Tokens.strokes.control, color.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Tokens.sizes.touch),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = spacing.md),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(color, CircleShape),
                                )
                                Spacer(Modifier.width(spacing.md))
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (preset.pinned) scheme.surface else scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = onCreate,
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurface),
                        modifier = Modifier.heightIn(min = Tokens.sizes.touch),
                    ) {
                        Icon(Icons.Outlined.Bookmarks, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(spacing.sm))
                        Text(stringResource(R.string.home_pin_create))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurface),
                        modifier = Modifier.heightIn(min = Tokens.sizes.touch),
                    ) { Text(stringResource(R.string.common_done)) }
                }
            }
        }
    }
}

/**
 * The one thing a preset without default wording still needs, asked for on the spot: a field,
 * the keyboard already up, and one button. No form, because there is nothing else to decide.
 */
@Composable
fun PresetWordsDialog(preset: Preset, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var words by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = scheme.surfaceContainer,
            border = BorderStroke(Tokens.strokes.edge, scheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(modifier = Modifier.padding(spacing.lg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(presetColor(preset.colorIndex), CircleShape),
                    )
                    Spacer(Modifier.width(spacing.sm))
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(spacing.md))
                OutlinedTextField(
                    value = words,
                    onValueChange = { words = it },
                    placeholder = { Text(stringResource(R.string.editor_text_placeholder)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (words.isNotBlank()) onConfirm(words.trim()) }),
                    shape = MaterialTheme.shapes.small,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = scheme.outline,
                        unfocusedBorderColor = scheme.outlineVariant,
                        cursorColor = scheme.primary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                Spacer(Modifier.height(spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurfaceVariant),
                        modifier = Modifier.heightIn(min = Tokens.sizes.control),
                    ) { Text(stringResource(R.string.sheet_cancel)) }
                    Button(
                        onClick = { onConfirm(words.trim()) },
                        enabled = words.isNotBlank(),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.onSurface,
                            contentColor = scheme.surface,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Tokens.sizes.control),
                    ) { Text(stringResource(R.string.home_pin_create_now), style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}
