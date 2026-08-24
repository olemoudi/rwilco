package dev.rwilco.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.model.Action
import dev.rwilco.model.Trigger
import dev.rwilco.model.family
import dev.rwilco.model.kind
import dev.rwilco.ui.components.TagChip
import dev.rwilco.ui.components.TriggerKeycap
import dev.rwilco.ui.format.triggerLine
import dev.rwilco.ui.home.labelRes
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.icon
import java.time.LocalDate
import java.time.LocalTime

/** Lets the instrumented tour find the text field; a BasicTextField has no other handle. */
const val EDITOR_TEXT_TAG = "editorText"

/** The reminder's own words, big, with nothing around them but a placeholder. */
@Composable
internal fun TextSection(text: String, onTextChange: (String) -> Unit, autoFocus: Boolean, error: Boolean) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) focusRequester.requestFocus() }
    Column {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .padding(top = Tokens.spacing.md)
                .focusRequester(focusRequester)
                .testTag(EDITOR_TEXT_TAG),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.editor_text_placeholder),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    inner()
                }
            },
        )
        if (error) FieldError(stringResource(R.string.editor_error_text))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagsSection(
    existingTags: List<String>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var newTag by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val haptics = Tokens.haptics
    // Everything on the draft plus everything in use elsewhere, one spelling each.
    val tags = remember(existingTags, selected) {
        (selected + existingTags).distinctBy { it.lowercase() }
    }
    fun commit() {
        val raw = newTag
        newTag = ""
        adding = false
        if (raw.isNotBlank()) {
            haptics.perform(HapticFeedbackType.Confirm)
            onAdd(raw)
        }
    }
    Column {
        SectionTitle(stringResource(R.string.editor_tags_title))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
        ) {
            for (tag in tags) {
                TagChip(label = tag, selected = selected.any { it.equals(tag, ignoreCase = true) }, onClick = { onToggle(tag) })
            }
            if (!adding) {
                OutlinedButton(
                    onClick = { adding = true },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.heightIn(min = 40.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Tokens.spacing.xs))
                    Text(stringResource(R.string.editor_new_tag), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (adding) {
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            OutlinedTextField(
                value = newTag,
                onValueChange = { newTag = it },
                placeholder = { Text(stringResource(R.string.editor_new_tag_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                trailingIcon = {
                    IconButton(onClick = { commit() }) {
                        Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.editor_new_tag_add))
                    }
                },
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Tokens.spacing.sm)
                    .focusRequester(focusRequester),
            )
        }
    }
}

@Composable
internal fun TriggersSection(
    triggers: List<Trigger>,
    today: LocalDate,
    defaultTime: LocalTime,
    inPast: Set<Int>,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    error: Boolean,
) {
    Column {
        SectionTitle(stringResource(R.string.editor_when_title))
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            triggers.forEachIndexed { index, trigger ->
                TriggerEditRow(
                    trigger = trigger,
                    today = today,
                    defaultTime = defaultTime,
                    inPast = index in inPast,
                    onEdit = { onEdit(index) },
                    onRemove = { onRemove(index) },
                )
            }
        }
        if (triggers.isNotEmpty()) Spacer(Modifier.height(Tokens.spacing.sm))
        OutlinedButton(
            onClick = onAdd,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.sizes.control),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(Tokens.spacing.sm))
            Text(stringResource(R.string.editor_add_trigger), style = MaterialTheme.typography.titleMedium)
        }
        if (error) FieldError(stringResource(R.string.editor_error_trigger))
    }
}

@Composable
private fun TriggerEditRow(
    trigger: Trigger,
    today: LocalDate,
    defaultTime: LocalTime,
    inPast: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val line = triggerLine(trigger, today, defaultTime)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = Tokens.spacing.md, top = Tokens.spacing.sm, bottom = Tokens.spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TriggerKeycap(family = trigger.family, icon = trigger.kind.icon, contentDescription = null)
                Spacer(Modifier.width(Tokens.spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = line.primary,
                        style = if (line.primaryMono) MonoStyles.label else MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = line.secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.editor_edit_trigger), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.editor_remove_trigger), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (inPast) FieldWarning(stringResource(R.string.editor_warning_past))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionsSection(selected: Set<Action>, onToggle: (Action) -> Unit, error: Boolean) {
    Column {
        SectionTitle(stringResource(R.string.editor_what_title))
        FlowRow(
            maxItemsInEachRow = 2,
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            for (action in Action.entries) {
                ActionTile(
                    action = action,
                    selected = action in selected,
                    onToggle = { onToggle(action) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (error) FieldError(stringResource(R.string.editor_error_action))
    }
}

/** A big toggle: icon, name, and a tick when on. Neutral colours; the tick is the state. */
@Composable
private fun ActionTile(action: Action, selected: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = {
            haptics.perform(if (selected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onToggle()
        },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) scheme.surfaceContainerHighest else scheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) scheme.outline else scheme.outlineVariant),
        modifier = modifier.heightIn(min = 72.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Tokens.spacing.lg, vertical = Tokens.spacing.md),
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(Tokens.spacing.md))
            Text(
                text = stringResource(action.labelRes),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(18.dp))
            }
        }
    }
}
