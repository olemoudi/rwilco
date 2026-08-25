package dev.rwilco.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rwilco.R
import dev.rwilco.model.Reminder
import dev.rwilco.model.TriggerKind
import dev.rwilco.model.ValidationError
import dev.rwilco.model.ValidationWarning
import dev.rwilco.model.warnings
import dev.rwilco.ui.alert.AlertContent
import dev.rwilco.ui.alert.AlertScreen
import dev.rwilco.ui.components.DiscardDialog
import dev.rwilco.ui.editor.sheets.ConditionSheet
import dev.rwilco.ui.editor.sheets.CountdownSheet
import dev.rwilco.ui.editor.sheets.DateOnlySheet
import dev.rwilco.ui.editor.sheets.DateTimeSheet
import dev.rwilco.ui.editor.sheets.LocationSheet
import dev.rwilco.ui.editor.sheets.RandomSheet
import dev.rwilco.ui.editor.sheets.RepeatTimeSheet
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.theme.Tokens

@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onClose: () -> Unit,
    onDeleted: (Reminder) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val haptics = Tokens.haptics

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                EditorEvent.Saved -> onClose()
                is EditorEvent.Deleted -> {
                    onDeleted(event.reminder)
                    onClose()
                }
                EditorEvent.Close -> onClose()
            }
        }
    }

    // Back closes the topmost thing: the preview, then a sheet, then the editor itself (which
    // asks first when there are unsaved changes).
    BackHandler {
        when {
            state.previewing -> viewModel.setPreviewing(false)
            state.sheet != EditorSheet.None -> viewModel.closeSheet()
            else -> viewModel.requestClose()
        }
    }

    val now = viewModel.clock.instant()
    val zone = viewModel.clock.zone
    val today = now.atZone(zone).toLocalDate()
    val spacing = Tokens.spacing
    val pastWarnings = warnings(state.draft.rules, now, zone, state.defaultTime)
        .filterIsInstance<ValidationWarning.InPast>()
        .map { it.index }
        .toSet()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                EditorTopBar(
                    title = stringResource(
                        when {
                            state.asPreset && state.editingPreset != null -> R.string.editor_title_edit_preset
                            state.asPreset -> R.string.editor_title_new_preset
                            state.isNew -> R.string.editor_title_new
                            else -> R.string.editor_title_edit
                        },
                    ),
                    // A preset has nothing to ring, so there is no alert to look at.
                    onPreview = if (state.asPreset) null else {
                        {
                            focusManager.clearFocus()
                            viewModel.setPreviewing(true)
                        }
                    },
                    onDelete = if (state.isNew && state.editingPreset == null) null else viewModel::delete,
                    deleteDescription = stringResource(
                        if (state.editingPreset != null) R.string.editor_delete_preset else R.string.editor_delete,
                    ),
                    onBack = viewModel::requestClose,
                )
            },
            bottomBar = {
                SaveBar(
                    enabled = state.loaded,
                    onSave = {
                        haptics.perform(HapticFeedbackType.Confirm)
                        focusManager.clearFocus()
                        viewModel.save()
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen),
            ) {
                EditorSection(
                    title = stringResource(if (state.asPreset) R.string.editor_preset_title else R.string.editor_text_title),
                    icon = if (state.asPreset) Icons.Outlined.Bookmarks else Icons.AutoMirrored.Outlined.Notes,
                    // Which shape this came from, so the form is not a mystery already filled in.
                    note = state.fromPresetName,
                ) {
                    // The toggle first: it changes what every part under it means. Only where
                    // it means something — turning a reminder that already exists into a
                    // preset would leave a question about the reminder nobody asked.
                    if (state.isNew || state.editingPreset != null) {
                        PresetToggle(asPreset = state.asPreset, onChange = viewModel::setAsPreset)
                        Spacer(Modifier.height(spacing.md))
                    }
                    TextSection(
                        text = state.draft.text,
                        suggestions = if (state.asPreset) emptyList() else state.suggestedTexts,
                        onTextChange = viewModel::setText,
                        error = state.showErrors && ValidationError.TextBlank in state.errors,
                        placeholderRes = if (state.asPreset) R.string.editor_preset_name_placeholder else R.string.editor_text_placeholder,
                        writeRes = if (state.asPreset) R.string.editor_name_preset else R.string.editor_write,
                        onCurate = { viewModel.curate(CurateKind.TEXTS) },
                        autoFocus = state.focusText,
                    )
                    // A preset carries optional wording; a reminder is its wording.
                    if (state.asPreset) {
                        Spacer(Modifier.height(spacing.lg))
                        PresetTextField(text = state.presetText, onChange = viewModel::setPresetText)
                    }
                }
                EditorSection(
                    title = stringResource(R.string.editor_tags_title),
                    icon = Icons.Outlined.LocalOffer,
                    note = stringResource(R.string.editor_optional),
                ) {
                    TagsSection(
                        existingTags = state.existingTags,
                        selected = state.draft.tags,
                        onToggle = viewModel::toggleTag,
                        onAdd = viewModel::addTag,
                        onCurate = { viewModel.curate(CurateKind.TAGS) },
                    )
                }
                EditorSection(
                    title = stringResource(R.string.editor_when_title),
                    icon = Icons.Outlined.Schedule,
                    note = stringResource(R.string.editor_when_optional),
                ) {
                    TriggersSection(
                        rules = state.draft.rules,
                        ruleMatch = state.draft.ruleMatch,
                        onRuleMatch = viewModel::setRuleMatch,
                        clock = viewModel.clock,
                        today = today,
                        defaultTime = state.defaultTime,
                        inPast = pastWarnings,
                        onAdd = {
                            focusManager.clearFocus()
                            viewModel.openKindPicker()
                        },
                        onQuickAdd = { trigger ->
                            focusManager.clearFocus()
                            viewModel.commitTrigger(null, trigger)
                        },
                        onEdit = viewModel::editTrigger,
                        onRemove = viewModel::removeTrigger,
                        onAddCondition = viewModel::addCondition,
                        onEditCondition = viewModel::editCondition,
                        onRemoveCondition = viewModel::removeCondition,
                    )
                }
                EditorSection(
                    title = stringResource(R.string.editor_recurrence_title),
                    icon = Icons.Outlined.Repeat,
                ) {
                    RecurrenceSection(
                        recurrence = state.draft.recurrence,
                        presets = state.recurrencePresets,
                        onPick = viewModel::pickRecurrencePreset,
                        onCustom = viewModel::setRecurrence,
                        onSavePreset = viewModel::saveRecurrencePreset,
                        onDeletePreset = viewModel::deleteRecurrencePreset,
                    )
                }
                EditorSection(
                    title = stringResource(R.string.editor_what_title),
                    icon = Icons.Outlined.NotificationsActive,
                    note = stringResource(R.string.editor_what_optional),
                ) {
                    ActionsSection(
                        selected = state.draft.actions,
                        onToggle = viewModel::toggleAction,
                    )
                }
                Spacer(Modifier.height(spacing.xxl))
            }
        }

        when (val sheet = state.sheet) {
            EditorSheet.None -> Unit
            EditorSheet.PickKind -> TriggerKindSheet(
                preferred = state.defaultKind,
                onPick = viewModel::pickKind,
                onDismiss = viewModel::closeSheet,
            )
            is EditorSheet.ConfigureCondition -> ConditionSheet(
                initial = sheet.initial as? dev.rwilco.model.Condition.TimeWindow,
                onConfirm = { condition -> viewModel.commitCondition(sheet.ruleIndex, sheet.conditionIndex, condition) },
                onDismiss = viewModel::closeSheet,
            )
            is EditorSheet.Configure -> {
                val commit = { trigger: dev.rwilco.model.Trigger -> viewModel.commitTrigger(sheet.index, trigger) }
                when (sheet.kind) {
                    TriggerKind.DATE_TIME -> DateTimeSheet(
                        initial = sheet.initial as? dev.rwilco.model.Trigger.AtDateTime,
                        now = now.atZone(zone),
                        onConfirm = commit,
                        onDismiss = viewModel::closeSheet,
                    )
                    TriggerKind.DATE -> DateOnlySheet(
                        initial = sheet.initial as? dev.rwilco.model.Trigger.OnDate,
                        today = today,
                        defaultTime = state.defaultTime,
                        onConfirm = commit,
                        onDismiss = viewModel::closeSheet,
                    )
                    TriggerKind.REPEAT_TIME -> RepeatTimeSheet(
                        initial = sheet.initial as? dev.rwilco.model.Trigger.AtTime,
                        onConfirm = commit,
                        onDismiss = viewModel::closeSheet,
                    )
                    TriggerKind.COUNTDOWN -> CountdownSheet(
                        clock = viewModel.clock,
                        initial = sheet.initial as? dev.rwilco.model.Trigger.Countdown,
                        onConfirm = commit,
                        onDismiss = viewModel::closeSheet,
                    )
                    TriggerKind.RANDOM -> RandomSheet(
                        initial = sheet.initial as? dev.rwilco.model.Trigger.Random,
                        onConfirm = commit,
                        onDismiss = viewModel::closeSheet,
                    )
                    TriggerKind.PLACE -> LocationSheet(
                        initial = sheet.initial as? dev.rwilco.model.Trigger.Location,
                        onConfirm = commit,
                        onDismiss = viewModel::closeSheet,
                        savedPlaces = state.savedPlaces,
                    )
                }
            }
        }

        if (state.confirmingDiscard) {
            DiscardDialog(onKeep = viewModel::keepEditing, onDiscard = viewModel::discard)
        }

        when (state.curating) {
            null -> Unit
            CurateKind.TEXTS -> CuratePanel(
                title = stringResource(R.string.curate_texts_title),
                items = state.allTexts,
                removeLabel = stringResource(R.string.curate_text_remove),
                onRename = viewModel::renameText,
                onRemove = viewModel::hideText,
                onDismiss = { viewModel.curate(null) },
            )
            CurateKind.TAGS -> CuratePanel(
                title = stringResource(R.string.curate_tags_title),
                items = state.existingTags,
                removeLabel = stringResource(R.string.curate_tag_remove),
                onRename = viewModel::renameTag,
                onRemove = viewModel::removeTag,
                onDismiss = { viewModel.curate(null) },
            )
        }

        if (state.previewing) {
            AlertScreen(
                content = AlertContent.fromDraft(state.draft, today, state.defaultTime),
                preview = true,
                onDone = { viewModel.setPreviewing(false) },
                onSnooze = { _ -> viewModel.setPreviewing(false) },
                onView = { viewModel.setPreviewing(false) },
            )
        }
    }
}

@Composable
private fun EditorTopBar(
    title: String,
    onBack: () -> Unit,
    onPreview: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    deleteDescription: String,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Tokens.spacing.sm)
                .heightIn(min = Tokens.sizes.control),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Tokens.spacing.sm),
            )
            if (onPreview != null) {
                IconButton(onClick = onPreview) {
                    Icon(Icons.Outlined.Visibility, contentDescription = stringResource(R.string.editor_preview))
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = deleteDescription)
                }
            }
        }
    }
}

/** The one primary action, fixed at the bottom, above the keyboard when it is up. */
@Composable
private fun SaveBar(enabled: Boolean, onSave: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Tokens.spacing.screen, vertical = Tokens.spacing.md),
        ) {
            Button(
                onClick = onSave,
                enabled = enabled,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.sizes.control),
            ) {
                Text(stringResource(R.string.common_save), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * One step of the form: its own raised card, an icon to recognise it by, and its name.
 *
 * Four labelled bands under hairlines were not enough — the eye slid down one flat column and
 * the headings read as more text. A card that stands off the ground, with a badge in front of
 * its name, says "this is a part" before anything is read at all.
 */
@Composable
private fun EditorSection(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    note: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    // The same step off the ground as a card on Home: at "low" the card was only its outline.
    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
        border = BorderStroke(Tokens.strokes.edge, scheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = spacing.md),
    ) {
        Column(modifier = Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(Tokens.sizes.badge)
                        .background(scheme.surfaceContainerHighest, RoundedCornerShape(Tokens.sizes.badge / 3)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(Tokens.sizes.badge / 2),
                    )
                }
                Spacer(Modifier.width(spacing.sm))
                Text(
                    text = title.uppercase(currentLocale()),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.semantics { heading() },
                )
                if (note != null) {
                    Spacer(Modifier.width(spacing.sm))
                    // Quieter by weight and case, not by fading: at 12sp a faded grey on the
                    // light scheme falls under the contrast anyone can read.
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(spacing.md))
            content()
        }
    }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = Tokens.spacing.sm),
    )
}

@Composable
internal fun FieldError(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.padding(top = Tokens.spacing.xs),
    )
}

@Composable
internal fun FieldWarning(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = Tokens.spacing.xs),
    )
}

