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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.model.Action
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.family
import dev.rwilco.model.kind
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.components.TagChip
import dev.rwilco.ui.components.TriggerKeycap
import dev.rwilco.ui.format.conditionLabel
import dev.rwilco.ui.format.triggerLine
import dev.rwilco.ui.home.labelRes
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.icon
import java.time.LocalDate
import java.time.LocalTime

/** Lets the instrumented tour find the text field; a BasicTextField has no other handle. */
const val EDITOR_TEXT_TAG = "editorText"

/**
 * The reminder's own words — offered before they are asked for.
 *
 * Everyday reminders repeat, so the keyboard is usually the slow way in: what comes up first is
 * a button for people who do want to type, and under it what they have written before, best
 * first. Nothing is auto-focused; a keyboard that opens by itself hides exactly the list that
 * would have saved the typing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TextSection(
    text: String,
    suggestions: List<String>,
    onTextChange: (String) -> Unit,
    error: Boolean,
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val writing = focused || text.isNotEmpty()
    Column {
        if (!writing) {
            // A way in, not the main event: the suggestions under it are the fast path, and a
            // full-width 56dp slab claims otherwise.
            OutlinedButton(
                onClick = { focusRequester.requestFocus() },
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(Tokens.strokes.control, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                contentPadding = PaddingValues(horizontal = Tokens.spacing.lg),
                modifier = Modifier.heightIn(min = Tokens.sizes.touch),
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Tokens.spacing.sm))
                Text(stringResource(R.string.editor_write), style = MaterialTheme.typography.labelLarge)
            }
        }
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .fillMaxWidth()
                // Not writing, the field is only here to be focused by the button above: it
                // takes no room, so the suggestions sit right under it instead of behind a gap
                // the size of a line of text nobody can see.
                .then(if (writing) Modifier.heightIn(min = 96.dp).padding(top = Tokens.spacing.md) else Modifier.height(0.dp))
                .onFocusChanged { focused = it.isFocused }
                .focusRequester(focusRequester)
                .testTag(EDITOR_TEXT_TAG),
            decorationBox = { inner ->
                Box {
                    // Only while writing: with the button up there, a second "what do you want to
                    // remember?" is one prompt too many.
                    if (writing && text.isEmpty()) {
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
        if (text.isEmpty() && suggestions.isNotEmpty()) {
            Spacer(Modifier.height(Tokens.spacing.lg))
            SectionTitle(stringResource(R.string.editor_reuse))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
            ) {
                for (suggestion in suggestions) {
                    PresetChip(label = suggestion, onClick = { onTextChange(suggestion) })
                }
            }
        }
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

    // Everything on this reminder plus every tag used before, one spelling each, the ones
    // already picked first. While a new one is being typed the list narrows to what matches,
    // so "co" surfaces "compra" instead of asking for it to be spelled out again.
    val offered = remember(existingTags, selected, newTag) {
        val all = (selected + existingTags).distinctBy { it.lowercase() }
        val query = newTag.trim().lowercase()
        if (query.isEmpty()) {
            all
        } else {
            all.filter { tag -> tag.lowercase().contains(query) || selected.any { it.equals(tag, ignoreCase = true) } }
        }
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
        // The way to a new tag sits on top, like the way to a new reminder text; what is under
        // it is the answer most of the time.
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
                    .focusRequester(focusRequester),
            )
        } else {
            OutlinedButton(
                onClick = { adding = true },
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(Tokens.strokes.control, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                contentPadding = PaddingValues(horizontal = Tokens.spacing.lg),
                modifier = Modifier.heightIn(min = Tokens.sizes.touch),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Tokens.spacing.sm))
                Text(stringResource(R.string.editor_new_tag), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (offered.isNotEmpty()) {
            Spacer(Modifier.height(Tokens.spacing.md))
            SectionTitle(stringResource(R.string.editor_reuse_tag))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
                modifier = Modifier.testTag(EDITOR_TAGS_TAG),
            ) {
                for (tag in offered) {
                    TagChip(
                        label = tag,
                        selected = selected.any { it.equals(tag, ignoreCase = true) },
                        onClick = { onToggle(tag) },
                    )
                }
            }
        }
    }
}

/** Lets the tour ask whether tags used before are actually being offered back. */
const val EDITOR_TAGS_TAG = "editorTags"

@Composable
internal fun TriggersSection(
    rules: List<TriggerRule>,
    ruleMatch: RuleMatch,
    onRuleMatch: (RuleMatch) -> Unit,
    today: LocalDate,
    defaultTime: LocalTime,
    inPast: Set<Int>,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onAddCondition: (Int) -> Unit,
    onEditCondition: (Int, Int) -> Unit,
    onRemoveCondition: (Int, Int) -> Unit,
) {
    Column {
        // The choice only exists once there is something to combine, and it comes before the
        // list because it changes what the list means.
        if (rules.size > 1) {
            SegmentedChoice(
                options = listOf(stringResource(R.string.editor_match_any), stringResource(R.string.editor_match_all)),
                selectedIndex = if (ruleMatch == RuleMatch.ANY) 0 else 1,
                onSelect = { onRuleMatch(if (it == 0) RuleMatch.ANY else RuleMatch.ALL) },
            )
            Text(
                text = stringResource(
                    if (ruleMatch == RuleMatch.ANY) R.string.editor_match_any_hint else R.string.editor_match_all_hint,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Tokens.spacing.xs, bottom = Tokens.spacing.sm),
            )
        }
        if (rules.isEmpty()) {
            Text(
                text = stringResource(R.string.editor_when_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Tokens.spacing.sm),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            rules.forEachIndexed { index, rule ->
                TriggerEditRow(
                    rule = rule,
                    today = today,
                    defaultTime = defaultTime,
                    inPast = index in inPast,
                    onEdit = { onEdit(index) },
                    onRemove = { onRemove(index) },
                    onAddCondition = { onAddCondition(index) },
                    onEditCondition = { conditionIndex -> onEditCondition(index, conditionIndex) },
                    onRemoveCondition = { conditionIndex -> onRemoveCondition(index, conditionIndex) },
                )
            }
        }
        if (rules.isNotEmpty()) Spacer(Modifier.height(Tokens.spacing.sm))
        OutlinedButton(
            onClick = onAdd,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(Tokens.strokes.control, MaterialTheme.colorScheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.sizes.control),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(Tokens.spacing.sm))
            Text(stringResource(R.string.editor_add_trigger), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriggerEditRow(
    rule: TriggerRule,
    today: LocalDate,
    defaultTime: LocalTime,
    inPast: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onAddCondition: () -> Unit,
    onEditCondition: (Int) -> Unit,
    onRemoveCondition: (Int) -> Unit,
) {
    val trigger = rule.trigger
    val line = triggerLine(trigger, today, defaultTime)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(Tokens.strokes.control, MaterialTheme.colorScheme.outline),
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
            // The conditions sit under the trigger they restrict, because that is what they are:
            // not another way to ring, but a fence around this one.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Tokens.spacing.xs),
                modifier = Modifier.padding(top = Tokens.spacing.xs, end = Tokens.spacing.md),
            ) {
                rule.conditions.forEachIndexed { index, condition ->
                    InputChip(
                        selected = false,
                        onClick = { onEditCondition(index) },
                        colors = InputChipDefaults.inputChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        label = { Text(conditionLabel(condition), style = MaterialTheme.typography.labelMedium) },
                        leadingIcon = { Icon(Icons.Outlined.FilterAlt, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.editor_remove_condition),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onRemoveCondition(index) },
                            )
                        },
                        shape = MaterialTheme.shapes.small,
                    )
                }
                TextButton(
                    onClick = onAddCondition,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    contentPadding = PaddingValues(horizontal = Tokens.spacing.sm),
                ) {
                    Text(
                        text = stringResource(if (rule.conditions.isEmpty()) R.string.editor_add_condition else R.string.editor_add_another_condition),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionsSection(selected: Set<Action>, onToggle: (Action) -> Unit) {
    Column {
        if (selected.isEmpty()) {
            Text(
                text = stringResource(R.string.editor_what_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Tokens.spacing.sm),
            )
        }
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
        color = if (selected) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh,
        border = BorderStroke(
            if (selected) Tokens.strokes.strong else Tokens.strokes.control,
            if (selected) scheme.onSurfaceVariant else scheme.outline,
        ),
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
