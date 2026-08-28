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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import dev.rwilco.model.RecurrenceWarning
import dev.rwilco.model.decidesItsOwnDates
import dev.rwilco.model.recurrenceWarning
import dev.rwilco.model.Reminder
import dev.rwilco.model.TriggerKind
import dev.rwilco.model.ValidationError
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.ValidationWarning
import dev.rwilco.model.warnings
import dev.rwilco.ui.alert.AlertContent
import dev.rwilco.ui.alert.AlertScreen
import dev.rwilco.ui.components.DiscardDialog
import dev.rwilco.ui.editor.sheets.ConditionSheet
import dev.rwilco.ui.editor.sheets.CountdownSheet
import dev.rwilco.ui.editor.sheets.DateRangeSheet
import dev.rwilco.ui.editor.sheets.DateSheet
import dev.rwilco.ui.editor.sheets.IntervalSheet
import dev.rwilco.ui.editor.sheets.LocationSheet
import dev.rwilco.ui.editor.sheets.RandomSheet
import dev.rwilco.ui.editor.sheets.CalendarSheet
import dev.rwilco.model.Status
import dev.rwilco.model.namesAnHour
import dev.rwilco.model.ringCadence
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.theme.Tokens
import java.time.LocalDate
import java.time.LocalTime

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
    // One line per rule, the worst thing there is to say about it. Remembered rather than
    // recomputed: working out that a rule can never fire means walking its next sixty-four
    // moments (nextFireOfRule), and that is not work for a recomposition.
    val ruleWarnings = remember(state.draft.rules, state.draft.ruleMatch, state.defaultTime) {
        val worst = HashMap<Int, Int>()
        for (warning in warnings(state.draft.rules, now, zone, state.defaultTime, state.draft.ruleMatch, state.dayShape).sortedBy(::severityOf)) {
            val (index, message) = when (warning) {
                // The strongest thing that can be said: under "todos" one dud rule is the
                // whole reminder, so it is said on the rule that causes it.
                is ValidationWarning.NeverCompletes -> warning.index to R.string.editor_warning_never_completes
                is ValidationWarning.PlacesConflict -> warning.index to R.string.editor_warning_places_conflict
                is ValidationWarning.NeverFires -> warning.index to R.string.editor_warning_never_fires
                is ValidationWarning.InPast -> warning.index to R.string.editor_warning_past
                // Advice, not a fault, and it goes on the place: that is the half somebody
                // would put the condition on.
                is ValidationWarning.MomentsCannotCoincide -> warning.index to R.string.editor_warning_moments_apart
                is ValidationWarning.BetterAsCondition -> warning.placeIndex to R.string.editor_warning_better_as_condition
            }
            worst.putIfAbsent(index, message)
        }
        worst
    }
    // How often this shape comes back, which is what the safety net stretches under. Walking
    // two of its moments is not work for a recomposition, so it is remembered like the warnings
    // above — and asked of the draft as it would be saved, which is what the net will see.
    val netCadence = remember(state.draft.rules, state.draft.ruleMatch, state.draft.recurrence, state.defaultTime, state.dayShape) {
        state.draft
            .toReminder("draft", now, now, Status.ACTIVE, zone = zone, shape = state.dayShape)
            .ringCadence(now, zone, state.defaultTime, shape = state.dayShape)
    }
    // The same question for the calendar in "Vuelve", which has fences of its own and no rule
    // index to hang a message on. Remembered for the same reason: it walks moments.
    val recurrenceWarning = remember(state.draft.recurrence, state.defaultTime, state.dayShape) {
        when (recurrenceWarning(state.draft.recurrence, now, zone, state.dayShape)) {
            RecurrenceWarning.OVER -> R.string.editor_warning_recurrence_over
            RecurrenceWarning.NEVER_FIRES -> R.string.editor_warning_never_fires
            null -> null
        }
    }

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
                // Always. The one primary action of the screen used to step aside while a tag
                // was being typed, and a person who left that field open lost the way to save
                // at all — see TagsSection. Nothing is worth hiding "Guardar" for: clearing the
                // focus on the way in commits whatever was in the field first.
                SaveBar(
                    enabled = state.loaded,
                    // Read back over the button that commits it: five cards up a scrolling
                    // column is too far to hold the whole thing in your head.
                    sentence = sentenceParts(
                        text = state.draft.text,
                        rules = state.draft.rules,
                        match = state.draft.ruleMatch,
                        recurrence = state.draft.recurrence,
                    ),
                    today = today,
                    defaultTime = state.defaultTime,
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
                        allSuggestions = if (state.asPreset) emptyList() else state.allTexts,
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
                        ruleWarnings = ruleWarnings,
                        onAdd = {
                            focusManager.clearFocus()
                            viewModel.openKindPicker()
                        },
                        onQuickAdd = { trigger ->
                            focusManager.clearFocus()
                            viewModel.commitTrigger(null, trigger)
                        },
                        suggestions = state.suggestedTriggers,
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
                        today = today,
                        chanceDecides = state.draft.rules.any { it.trigger.decidesItsOwnDates },
                        defaultTime = state.defaultTime,
                        rulesNameAnHour = state.draft.rules.any { it.trigger.namesAnHour },
                        warning = recurrenceWarning,
                        onPick = viewModel::pickRecurrencePreset,
                        onCustom = viewModel::setRecurrence,
                        onCalendar = viewModel::openCalendar,
                        onAddCondition = viewModel::addRecurrenceCondition,
                        onEditCondition = viewModel::editRecurrenceCondition,
                        onRemoveCondition = viewModel::removeRecurrenceCondition,
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
                // What happens if none of the four cards above it lands: the last word on the
                // form, and the only one the person did not have to answer.
                Spacer(Modifier.height(spacing.lg))
                SafetyNetNote(cadence = netCadence, settings = state.safetyNetSettings)
                Spacer(Modifier.height(spacing.xxl))
            }
        }

        when (val sheet = state.sheet) {
            EditorSheet.None -> Unit
            EditorSheet.PickKind -> TriggerKindSheet(
                kinds = state.kindOrder,
                preferred = state.defaultKind,
                onPick = viewModel::pickKind,
                onDismiss = viewModel::closeSheet,
            )
            is EditorSheet.ConfigureCalendar -> CalendarSheet(
                initial = sheet.initial,
                suggested = sheet.suggested,
                today = today,
                defaultTime = state.defaultTime,
                shape = state.dayShape,
                savedWindows = state.savedWindows,
                onConfirm = viewModel::commitCalendar,
                onDismiss = viewModel::closeSheet,
            )
            is EditorSheet.ConfigureRecurrenceCondition -> ConditionSheet(
                initial = sheet.initial,
                savedPlaces = state.savedPlaces,
                onConfirm = { condition -> viewModel.commitRecurrenceCondition(sheet.index, condition) },
                onDismiss = viewModel::closeSheet,
            )
            is EditorSheet.ConfigureCondition -> ConditionSheet(
                initial = sheet.initial,
                savedPlaces = state.savedPlaces,
                onConfirm = { condition -> viewModel.commitCondition(sheet.ruleIndex, sheet.conditionIndex, condition) },
                onDismiss = viewModel::closeSheet,
            )
            is EditorSheet.Configure -> {
                val commit = { trigger: dev.rwilco.model.Trigger -> viewModel.commitTrigger(sheet.index, trigger) }
                when (sheet.kind) {
                    // One tile for a date, whether it carries an hour or not; DATE_TIME is a
                    // stored favourite from when there were two, and opens the same sheet.
                    TriggerKind.DATE, TriggerKind.DATE_TIME -> DateSheet(
                        initial = sheet.initial,
                        now = now.atZone(zone),
                        defaultTime = state.defaultTime,
                        shape = state.dayShape,
                        savedWindows = state.savedWindows,
                        onConfirm = commit,
                        onDismiss = viewModel::closeSheet,
                    )
                    TriggerKind.DATE_RANGE -> DateRangeSheet(
                        initial = sheet.initial as? dev.rwilco.model.Trigger.DateRange,
                        today = today,
                        combining = state.draft.ruleMatch == RuleMatch.TOGETHER && state.draft.rules.size > 1,
                        onConfirm = commit,
                        onDismiss = viewModel::closeSheet,
                    )
                    TriggerKind.INTERVAL -> IntervalSheet(
                        initial = sheet.initial as? dev.rwilco.model.Trigger.Interval,
                        combining = state.draft.ruleMatch == RuleMatch.TOGETHER && state.draft.rules.size > 1,
                        savedWindows = state.savedWindows,
                        onConfirm = commit,
                        onDismiss = viewModel::closeSheet,
                    )
                    // A repeating time is not a tile any more — it is the calendar in
                    // "Vuelve" — but the kind is still a stored favourite, so it opens the
                    // nearest thing the sheet still has rather than nothing at all.
                    TriggerKind.REPEAT_TIME -> DateSheet(
                        initial = sheet.initial,
                        now = now.atZone(zone),
                        defaultTime = state.defaultTime,
                        shape = state.dayShape,
                        savedWindows = state.savedWindows,
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
private fun SaveBar(
    enabled: Boolean,
    sentence: List<SentencePart>,
    today: LocalDate,
    defaultTime: LocalTime,
    onSave: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Tokens.spacing.screen, vertical = Tokens.spacing.md),
        ) {
            // Only once there is an arrangement to read: while somebody is typing the words into
            // a blank draft this would be their own sentence handed back to them, under the
            // keyboard, in the room the form needs.
            if (sentence.saysMoreThanWords()) {
                ReminderSentence(
                    parts = sentence,
                    today = today,
                    defaultTime = defaultTime,
                    modifier = Modifier.padding(bottom = Tokens.spacing.sm),
                )
            }
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

/** Worst first, so the line a rule gets is the one that matters most. */
private fun severityOf(warning: ValidationWarning): Int = when (warning) {
    is ValidationWarning.NeverCompletes -> 0
    is ValidationWarning.MomentsCannotCoincide -> 1
    is ValidationWarning.PlacesConflict -> 1
    is ValidationWarning.NeverFires -> 2
    is ValidationWarning.InPast -> 3
    is ValidationWarning.BetterAsCondition -> 4
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

