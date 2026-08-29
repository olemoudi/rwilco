package dev.rwilco.ui.editor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import dev.rwilco.ui.components.MoreChip
import dev.rwilco.ui.components.PickSheet
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.components.TagChip
import dev.rwilco.ui.components.TriggerKeycap
import dev.rwilco.ui.components.VISIBLE_SUGGESTIONS
import dev.rwilco.model.SafetyNetSettings
import dev.rwilco.model.netWait
import dev.rwilco.model.tooFastForNet
import dev.rwilco.ui.format.rememberWords
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.durationText
import dev.rwilco.ui.format.conditionLabel
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.format.rememberIs24h
import dev.rwilco.ui.format.triggerLine
import dev.rwilco.ui.home.labelRes
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.edge
import dev.rwilco.ui.theme.icon
import dev.rwilco.ui.theme.wash
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Lets the instrumented tour find the text field; a BasicTextField has no other handle. */
const val EDITOR_TEXT_TAG = "editorText"

/**
 * What this screen is writing: a reminder, or the shape of one kept under a name. The words
 * become the preset's name, and everything under here — the tags, the when, the what happens —
 * is kept with it.
 */
@Composable
internal fun PresetToggle(asPreset: Boolean, onChange: (Boolean) -> Unit) {
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // The whole row, not the switch alone: a 32dp target beside two lines of text somebody
        // has just read is asking them to aim at the smallest part of what they are looking at.
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = asPreset,
                role = Role.Switch,
                onValueChange = { on ->
                    haptics.perform(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    onChange(on)
                },
            )
            .heightIn(min = Tokens.sizes.touch),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.editor_as_preset), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.editor_as_preset_hint),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Tokens.spacing.md))
        Switch(
            checked = asPreset,
            // The row owns the gesture; the switch is the picture of the state.
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = scheme.surface,
                checkedTrackColor = scheme.onSurface,
            ),
        )
    }
}

/**
 * A preset's default wording, or nothing. Optional on purpose: some shapes always say the same
 * thing ("sacar la basura"), and some are a shape precisely because the words change — those
 * hand the person an empty field and the keyboard, later, when the reminder is made.
 */
@Composable
internal fun PresetTextField(text: String, onChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column {
        SectionTitle(stringResource(R.string.editor_preset_text))
        OutlinedTextField(
            value = text,
            onValueChange = onChange,
            placeholder = { Text(stringResource(R.string.editor_preset_text_hint)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = scheme.outline,
                unfocusedBorderColor = scheme.outlineVariant,
                cursorColor = scheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(if (text.isBlank()) R.string.editor_preset_text_empty else R.string.editor_preset_text_set),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Tokens.spacing.xs),
        )
    }
}

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
    /** Everything ever written, for the list behind the dots; the row shows the best few. */
    allSuggestions: List<String> = suggestions,
    onTextChange: (String) -> Unit,
    error: Boolean,
    placeholderRes: Int = R.string.editor_text_placeholder,
    writeRes: Int = R.string.editor_write,
    onCurate: () -> Unit = {},
    autoFocus: Boolean = false,
    /** Bumped by whoever wants the cursor here now — a refused save, asking for the words. */
    focusKey: Int = 0,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    var listing by rememberSaveable { mutableStateOf(false) }
    val writing = focused || text.isNotEmpty() || autoFocus

    // The one place in this app where the keyboard opens by itself. Everywhere else it would
    // cover the list that saves the typing; arriving from a preset there is nothing else left
    // to answer, so the cursor is already where the hand was going.
    LaunchedEffect(autoFocus, focusKey) {
        if (autoFocus || focusKey > 0) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
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
                Text(stringResource(writeRes), style = MaterialTheme.typography.labelLarge)
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
                            text = stringResource(placeholderRes),
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
                for (suggestion in suggestions.take(VISIBLE_SUGGESTIONS)) {
                    // Tap to use it; hold to mend the list it came from.
                    PresetChip(
                        label = suggestion,
                        onClick = { onTextChange(suggestion) },
                        onHold = onCurate,
                        holdLabel = stringResource(R.string.curate_hold),
                    )
                }
                if (allSuggestions.size > VISIBLE_SUGGESTIONS) MoreChip(onClick = { listing = true })
            }
        }
    }

    if (listing) {
        PickSheet(
            title = stringResource(R.string.editor_reuse),
            items = allSuggestions,
            onPick = { picked ->
                listing = false
                onTextChange(picked)
            },
            onDismiss = { listing = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagsSection(
    existingTags: List<String>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onAdd: (String) -> Unit,
    onCurate: () -> Unit = {},
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var newTag by rememberSaveable { mutableStateOf("") }
    var listing by rememberSaveable { mutableStateOf(false) }
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
            // Whether the focus has ever arrived. onFocusChanged reports the state the moment
            // the field is attached, which is "not focused" — so without this the way out
            // below fires before the way in does, and the field shuts as it opens.
            var everFocused by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            OutlinedTextField(
                value = newTag,
                onValueChange = { newTag = it },
                placeholder = { Text(stringResource(R.string.editor_new_tag_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                // A plus, not a tick: this adds a tag to the list, it does not save anything.
                trailingIcon = {
                    IconButton(onClick = { commit() }) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.editor_new_tag_add))
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
                    .focusRequester(focusRequester)
                    // The way out. Opening this field used to be a one-way door: nothing but
                    // its own + closed it again, so anybody who opened it and went back to the
                    // form left a field open at the top of a screen they had scrolled past.
                    // Losing the focus is somebody having moved on, and what they typed goes in
                    // with them rather than being thrown away.
                    .onFocusChanged { state ->
                        if (state.isFocused) everFocused = true else if (everFocused && adding) commit()
                    },
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
                // The ones already on are never hidden behind the dots — a tag you cannot see
                // is a tag you cannot take off.
                val shown = offered.take(maxOf(VISIBLE_SUGGESTIONS, selected.size))
                for (tag in shown) {
                    TagChip(
                        label = tag,
                        selected = selected.any { it.equals(tag, ignoreCase = true) },
                        onClick = { onToggle(tag) },
                        onHold = onCurate,
                        holdLabel = stringResource(R.string.curate_hold),
                    )
                }
                if (offered.size > shown.size) MoreChip(onClick = { listing = true })
            }
        }
    }

    // Tags stay open: turning three on from the list is one visit, not three.
    if (listing) {
        PickSheet(
            title = stringResource(R.string.editor_reuse_tag),
            items = offered,
            selected = selected.toSet(),
            onPick = onToggle,
            onDismiss = { listing = false },
        )
    }
}

/** Lets the tour ask whether tags used before are actually being offered back. */
const val EDITOR_TAGS_TAG = "editorTags"

@Composable
internal fun TriggersSection(
    rules: List<TriggerRule>,
    ruleMatch: RuleMatch,
    onRuleMatch: (RuleMatch) -> Unit,
    clock: Clock,
    today: LocalDate,
    defaultTime: LocalTime,
    /** What is worth saying about each rule, by index: one string resource, the worst one. */
    ruleWarnings: Map<Int, Int>,
    onAdd: () -> Unit,
    onQuickAdd: (Trigger) -> Unit,
    /** The "when"s used before, best first; empty on a phone with no history yet. */
    suggestions: List<Trigger> = emptyList(),
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
            // Three readings of the same list, in the order they get used: either one, all of
            // them in any order, all of them at once.
            val matches = listOf(RuleMatch.ANY, RuleMatch.ALL, RuleMatch.TOGETHER)
            SegmentedChoice(
                options = matches.map { stringResource(it.labelRes) },
                selectedIndex = matches.indexOf(ruleMatch).coerceAtLeast(0),
                onSelect = { onRuleMatch(matches[it]) },
            )
            Text(
                text = stringResource(ruleMatch.hintRes),
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
            QuickWhenRow(clock = clock, suggestions = suggestions, defaultTime = defaultTime, onPick = onQuickAdd)
            Spacer(Modifier.height(Tokens.spacing.sm))
        }
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            rules.forEachIndexed { index, rule ->
                TriggerEditRow(
                    rule = rule,
                    today = today,
                    defaultTime = defaultTime,
                    warning = ruleWarnings[index],
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

/**
 * The "when"s to hand, one tap each and no sheet.
 *
 * What somebody sets is what somebody sets again — the same half hour, the same nine o'clock,
 * the same "al llegar a casa" — so these are [suggestions]: the shapes used before, most used
 * first, re-hung on today. Until there is a history to draw on they are the three answers
 * everybody starts with: in half an hour, tonight, tomorrow morning. Offered only while nothing
 * is set — once there is a trigger the section is about that one — and always followed by the
 * button that opens the whole choice.
 */
@Composable
private fun QuickWhenRow(clock: Clock, suggestions: List<Trigger>, defaultTime: LocalTime, onPick: (Trigger) -> Unit) {
    val locale = currentLocale()
    val is24h = rememberIs24h()
    val now = clock.instant().atZone(clock.zone)
    val today = now.toLocalDate()
    val tonight = LocalTime.of(20, 0)
    val morning = LocalTime.of(9, 0)
    val offered = remember(suggestions, today) {
        suggestions.ifEmpty {
            listOfNotNull(
                // A length, so it starts when the reminder does rather than at some fixed minute.
                Trigger.Countdown(QUICK_MINUTES),
                Trigger.AtDateTime(LocalDateTime.of(today, tonight)).takeIf { now.toLocalTime().isBefore(tonight) },
                Trigger.AtDateTime(LocalDateTime.of(today.plusDays(1), morning)),
            )
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        for (trigger in offered) {
            PresetChip(
                label = quickLabel(trigger, today, defaultTime, locale, is24h),
                onClick = { onPick(trigger) },
            )
        }
    }
}

/**
 * A whole "when" on one chip. The two-line reading each trigger already has, folded into a line
 * that reads like something you would say: "Mañana 09:00", "Al llegar Casa", "En 30 min".
 */
@Composable
private fun quickLabel(trigger: Trigger, today: LocalDate, defaultTime: LocalTime, locale: Locale, is24h: Boolean): String {
    val line = triggerLine(trigger, today, defaultTime)
    val text = when (trigger) {
        is Trigger.Countdown ->
            if (trigger.startedAt == null) stringResource(R.string.countdown_in, line.primary) else line.primary
        is Trigger.AtDateTime, is Trigger.Location -> line.secondary + " " + line.primary
        else -> line.primary + " · " + line.secondary
    }
    return text.replaceFirstChar { it.titlecase(locale) }
}

private const val QUICK_MINUTES = 30

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriggerEditRow(
    rule: TriggerRule,
    today: LocalDate,
    defaultTime: LocalTime,
    warning: Int?,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onAddCondition: () -> Unit,
    onEditCondition: (Int) -> Unit,
    onRemoveCondition: (Int) -> Unit,
) {
    val trigger = rule.trigger
    val line = triggerLine(trigger, today, defaultTime)
    val family = trigger.family
    // The row wears its family's colour, wash and edge, so a date, a place and a chance are
    // told apart before a word is read — the keycap alone was a stamp on a grey form.
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = family.wash(),
        border = BorderStroke(Tokens.strokes.control, family.edge()),
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
            if (warning != null) FieldWarning(stringResource(warning))
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
                            containerColor = Color.Transparent,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        border = BorderStroke(Tokens.strokes.edge, family.edge()),
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

/**
 * What the safety net comes to for *this* reminder, at the foot of the form.
 *
 * It used to be a switch, and a switch about the wrong thing: which of your reminders is going
 * to be the one that gets away is exactly what nobody can answer in advance. So the net holds
 * for everything and there is nothing left to ask — but there is something worth saying, and
 * this is it. "Un aviso discreto 2 h 24 min después" is a number somebody can agree or disagree
 * with; "una décima parte de la cadencia" is a rule somebody has to work out. Where the rings
 * are too close together for a net to fit between them, it says that instead.
 *
 * At the end, under the last card and not inside one: it is not part of any of the four answers
 * above it, it is what happens if none of them lands.
 */
@Composable
internal fun SafetyNetNote(cadence: java.time.Duration?, settings: SafetyNetSettings) {
    val tooFast = tooFastForNet(cadence, settings)
    Text(
        text = if (tooFast && cadence != null) {
            stringResource(R.string.editor_net_too_fast, durationText(rememberWords(), cadence.toMinutes().toInt()))
        } else {
            stringResource(R.string.editor_net_note, durationText(rememberWords(), netWait(cadence, settings).toMinutes().toInt()))
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.spacing.lg),
    )
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

/**
 * A big toggle: icon and name. On is inverted — ink and paper swapped — and the swap is
 * animated, so a tap is answered by the tile turning over rather than a tick appearing in a
 * corner. Neutral on purpose: actions have no family, and amber is spoken for.
 */
@Composable
private fun ActionTile(action: Action, selected: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    val motion = Tokens.motion
    val fill by animateColorAsState(
        targetValue = if (selected) scheme.onSurface else scheme.surfaceContainerHigh,
        animationSpec = tween(motion.fast),
        label = "actionFill",
    )
    val ink by animateColorAsState(
        targetValue = if (selected) scheme.surface else scheme.onSurfaceVariant,
        animationSpec = tween(motion.fast),
        label = "actionInk",
    )
    Surface(
        onClick = {
            haptics.perform(if (selected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onToggle()
        },
        shape = MaterialTheme.shapes.medium,
        color = fill,
        border = if (selected) null else BorderStroke(Tokens.strokes.control, scheme.outline),
        modifier = modifier
            .heightIn(min = 72.dp)
            .semantics { this.selected = selected },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Tokens.spacing.lg, vertical = Tokens.spacing.md),
        ) {
            Icon(imageVector = action.icon, contentDescription = null, tint = ink)
            Spacer(Modifier.width(Tokens.spacing.md))
            Text(
                text = stringResource(action.labelRes),
                style = MaterialTheme.typography.labelLarge,
                color = ink,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
