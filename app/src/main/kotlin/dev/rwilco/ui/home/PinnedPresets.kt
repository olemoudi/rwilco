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
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.Role
import dev.rwilco.model.Action
import dev.rwilco.model.toggling
import dev.rwilco.ui.components.scrollFade
import dev.rwilco.ui.theme.icon
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable

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
        items(presets, key = { it.id }, contentType = { "preset" }) { preset -> PinnedPresetButton(preset, onPick) }
        item(key = "add", contentType = "add") { AddPinnedButton(onManage) }
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
        modifier = Modifier.heightIn(min = Tokens.sizes.touch),
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
            .heightIn(min = Tokens.sizes.touch)
            .width(Tokens.sizes.touch),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = stringResource(R.string.home_pin_manage),
                tint = scheme.onSurface,
                modifier = Modifier.size(Tokens.sizes.glyphMedium),
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
    /** The pencil on a row: this list is the one place every preset is, so it edits too (0.69.0). */
    onEdit: (Preset) -> Unit = {},
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
                .heightIn(max = Tokens.sizes.dialogMax),
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
                // The same edge that lies on the preset chooser: a capped list whose rows end
                // flush with the bottom reads as a list that finishes there.
                val scroll = rememberScrollState()
                Column(
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .scrollFade(scroll, scheme.surfaceContainer)
                        .verticalScroll(scroll),
                ) {
                    for (preset in presets) {
                        val color = presetColor(preset.colorIndex)
                        Surface(
                            onClick = { onTogglePin(preset) },
                            shape = MaterialTheme.shapes.small,
                            // Pinned is inverted, like every other "on" in the app — and said
                            // (0.68.0): a checkbox to a screen reader, and a check glyph for
                            // anyone the inversion alone does not reach.
                            color = if (preset.pinned) scheme.onSurface else presetWash(preset.colorIndex),
                            border = if (preset.pinned) null else BorderStroke(Tokens.strokes.control, color.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Tokens.sizes.touch)
                                .semantics {
                                    contentDescription = preset.name
                                    role = Role.Checkbox
                                    toggleableState = if (preset.pinned) ToggleableState.On else ToggleableState.Off
                                },
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
                                    modifier = Modifier.weight(1f),
                                )
                                if (preset.pinned) {
                                    Spacer(Modifier.width(spacing.sm))
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = null,
                                        tint = scheme.surface,
                                        modifier = Modifier.size(Tokens.sizes.glyph),
                                    )
                                }
                                IconButton(onClick = { onEdit(preset) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = stringResource(R.string.home_preset_edit),
                                        tint = if (preset.pinned) scheme.surface else scheme.onSurfaceVariant,
                                    )
                                }
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
                        Icon(Icons.Outlined.Bookmarks, contentDescription = null, modifier = Modifier.size(Tokens.sizes.glyphSmall))
                        Spacer(Modifier.width(spacing.sm))
                        Text(stringResource(R.string.home_pin_create))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurface),
                        modifier = Modifier.heightIn(min = Tokens.sizes.touch),
                    ) { Text(stringResource(R.string.common_close)) }
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
fun PresetWordsDialog(preset: Preset, onConfirm: (String, Set<Action>) -> Unit, onDismiss: () -> Unit) {
    // Saveable, both: the dialog itself survives a rotation, and came back empty around it.
    var words by rememberSaveable { mutableStateOf("") }
    // What the shape brings, until this reminder says otherwise. The preset is not touched:
    // "this one, also on the screen" is a thing to say once without editing the shape for good.
    // Kept by name, which is what a Bundle can hold.
    var actionNames by rememberSaveable(preset.id) { mutableStateOf(preset.actions.joinToString(",") { it.name }) }
    val actions = actionNames.split(',').mapNotNullTo(LinkedHashSet()) { name -> Action.entries.firstOrNull { it.name == name } }
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
                    keyboardActions = KeyboardActions(onDone = { if (words.isNotBlank()) onConfirm(words.trim(), actions) }),
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
                ActionPips(selected = actions, onToggle = { actionNames = actions.toggling(it).joinToString(",") { a -> a.name } })
                Spacer(Modifier.height(spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurfaceVariant),
                        modifier = Modifier.heightIn(min = Tokens.sizes.control),
                    ) { Text(stringResource(R.string.sheet_cancel)) }
                    Button(
                        onClick = { onConfirm(words.trim(), actions) },
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

/**
 * What it will do when it rings, as five glyphs under the words.
 *
 * Icons and nothing else: the dialog is one field and one button, and five named tiles would
 * turn a two-second answer into a form. The shape's own answer is already on when it opens, so
 * the row is read rather than filled in — and a tap is there for the day this one reminder
 * needs the screen, or does not need the noise. Each carries its name for a screen reader,
 * which is the reading nobody can get from a glyph.
 */
@Composable
private fun ActionPips(selected: Set<Action>, onToggle: (Action) -> Unit) {
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        for (action in Action.entries) {
            val on = action in selected
            val fill by animateColorAsState(
                targetValue = if (on) scheme.onSurface else scheme.surfaceContainerHigh,
                animationSpec = tween(Tokens.motion.fast),
                label = "pipFill",
            )
            val ink by animateColorAsState(
                targetValue = if (on) scheme.surface else scheme.onSurfaceVariant,
                animationSpec = tween(Tokens.motion.fast),
                label = "pipInk",
            )
            val label = stringResource(action.labelRes)
            Surface(
                onClick = {
                    haptics.perform(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    onToggle(action)
                },
                shape = MaterialTheme.shapes.medium,
                color = fill,
                border = if (on) null else BorderStroke(Tokens.strokes.control, scheme.outline),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Tokens.sizes.touch)
                    .semantics {
                        contentDescription = label
                        role = Role.Checkbox
                        toggleableState = if (on) ToggleableState.On else ToggleableState.Off
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.size(Tokens.sizes.badge),
                    )
                }
            }
        }
    }
}
