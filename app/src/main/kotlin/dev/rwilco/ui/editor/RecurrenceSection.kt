package dev.rwilco.ui.editor

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.rwilco.R
import dev.rwilco.model.Condition
import dev.rwilco.model.LAST_ORDINAL
import dev.rwilco.model.MAX_RECURRENCE_AMOUNT
import dev.rwilco.model.withSpanOf
import dev.rwilco.model.MIN_RECURRENCE_AMOUNT
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrencePreset
import dev.rwilco.model.asRepeat
import dev.rwilco.model.conditions
import dev.rwilco.model.RecurrenceHour
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.sameSpanAs
import dev.rwilco.model.countsFromRinging
import dev.rwilco.model.RecurrenceFrom
import dev.rwilco.model.SpanLanding
import dev.rwilco.model.landing
import dev.rwilco.model.landsExactly
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.format.recurrenceLabel
import dev.rwilco.ui.format.repeatSummary
import dev.rwilco.ui.format.conditionLabel
import dev.rwilco.ui.components.Stepper
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.components.scrollFade
import dev.rwilco.ui.theme.Tokens
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.LocalTime
import dev.rwilco.ui.format.rememberIs24h
import dev.rwilco.ui.format.Words
import dev.rwilco.ui.format.rememberWords
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.components.TimeField
import androidx.compose.material.icons.outlined.FormatQuote

/** How many recurrences get a button of their own before the rest go behind the dots. */
private const val VISIBLE_PRESETS = 4

/**
 * What "hecho" leaves behind, and when it comes back — the one place in the app where anything
 * repeats.
 *
 * It used to be two places. A "una hora que se repite" tile wrote a *trigger* that named its own
 * dates, this card wrote a recurrence that counted a span, they overlapped almost exactly ("el
 * cuarto miércoles" could be written either way), and nothing on either screen said which of the
 * two a given reminder had — the anchor row here even had a button that reached across and
 * opened the trigger sheet. A repeat is not a way of starting; it is the answer to "¿y vuelve?".
 * So there is one card, and it has both answers on it:
 *
 * - **por calendario** — the dates a series names, with an hour in them and an end
 *   ([Recurrence.Calendar]), behind its own sheet because it is five controls deep; and
 * - **cada tanto** — a span counted from something that happened ([Recurrence.After]), which is
 *   what the buttons and "personalizado" build.
 *
 * The answers people give most are buttons — "no repetir" first, because it is the default and
 * the way back — and everything else is one tap further in.
 *
 * **The anchor is asked here and nowhere else**, and only of a span: "cada semana" is half a
 * sentence, and the other half is which moment the week is counted from — the ringing (the
 * rhythm holds however late you answer) or dealing with it (the clock starts when you do). A
 * calendar is not asked, because a calendar already knows: its dates are its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecurrenceSection(
    recurrence: Recurrence,
    presets: List<RecurrencePreset>,
    today: LocalDate,
    /** Whether a random rule is on the draft: the last trigger that names dates of its own. */
    chanceDecides: Boolean,
    /** The hour a custom one starts from, and what "a las nueve" means to this person. */
    defaultTime: LocalTime,
    /**
     * Whether the rules already name an hour of their own. Then a span says which *day* it
     * comes back on and they say when in it, so the hour below decides nothing — which is worth
     * a line rather than a control that quietly does nothing.
     */
    rulesNameAnHour: Boolean,
    /**
     * The days of the week the rules allow, empty when they name none. What makes the landing
     * question worth asking at all: with no days named, a span lands where it lands.
     */
    rulesDays: Set<java.time.DayOfWeek>,
    /** The hour the rules name, offered to "justo el plazo" — which takes the rules out of the loop. */
    rulesHour: LocalTime?,
    /** The worst thing there is to say about the calendar, or null when there is nothing. */
    warning: Int?,
    onPick: (RecurrencePreset) -> Unit,
    onCustom: (Recurrence) -> Unit,
    onCalendar: () -> Unit,
    onAddCondition: () -> Unit,
    onEditCondition: (Int) -> Unit,
    onRemoveCondition: (Int) -> Unit,
    onSavePreset: (String?, String, Recurrence) -> Unit,
    onDeletePreset: (String) -> Unit,
    /** The repeat the words themselves say ("cada martes a las 8"), while "Vuelve" is unanswered. */
    understood: Recurrence? = null,
    onUnderstood: (Recurrence) -> Unit = {},
) {
    var listing by rememberSaveable { mutableStateOf(false) }
    val readWords = rememberWords()
    // The preset being built or edited: null closed, "" a new one, otherwise its id.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val spacing = Tokens.spacing
    val spans = presets.take(VISIBLE_PRESETS - 1)
    val calendar = recurrence.asRepeat()

    Column {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            RecurrenceButton(
                label = stringResource(R.string.recur_none),
                selected = recurrence == Recurrence.None,
                onClick = { onCustom(Recurrence.None) },
            )
            // What the words say, first among the answers and wearing the quote glyph the
            // "Cuándo" row uses for the same thing. Here and not there: once a rule is on the
            // form the quick row is gone, and a repeat is this card's question anyway.
            understood?.let { read ->
                val hour = (read as? Recurrence.Calendar)?.repeat?.time
                    ?.let { " · " + TimeText.time(it, readWords.is24h, readWords.locale) }.orEmpty()
                RecurrenceButton(
                    icon = Icons.Outlined.FormatQuote,
                    label = (recurrenceLabel(readWords, read, today) + hour).replaceFirstChar { it.titlecase(readWords.locale) },
                    selected = false,
                    onClick = { onUnderstood(read) },
                    contentDescription = stringResource(R.string.editor_when_from_words),
                )
            }
            for (preset in spans) {
                RecurrenceButton(
                    label = presetLabel(preset, today),
                    selected = recurrence.sameSpanAs(preset.recurrence),
                    onClick = { onPick(preset) },
                )
            }
            // One flow and not two rows: they are all answers to the same question, and a card
            // that spends four lines on seven buttons pushes "y sólo si" off the screen.
            if (presets.size > VISIBLE_PRESETS - 1) {
                RecurrenceButton(
                    icon = Icons.Outlined.MoreHoriz,
                    label = null,
                    selected = false,
                    onClick = { listing = true },
                    contentDescription = stringResource(R.string.recur_more),
                )
            }
            RecurrenceButton(
                icon = Icons.Outlined.CalendarMonth,
                label = stringResource(R.string.recur_calendar),
                selected = calendar != null,
                onClick = onCalendar,
            )
            RecurrenceButton(
                icon = Icons.Outlined.Tune,
                label = stringResource(R.string.recur_custom),
                // Selected when a span is set that is not one of the buttons above.
                selected = recurrence is Recurrence.After && spans.none { recurrence.sameSpanAs(it.recurrence) },
                onClick = { editing = "" },
            )
            // Only where it means anything: a random window is the one trigger left that works
            // out dates of its own, and offering "lo decide el azar" without one is offering
            // nothing.
            if (chanceDecides) {
                RecurrenceButton(
                    icon = Icons.Outlined.Casino,
                    label = stringResource(R.string.recur_by_trigger),
                    selected = recurrence == Recurrence.ByTrigger,
                    onClick = { onCustom(Recurrence.ByTrigger) },
                )
            }
        }
        // Which moment the span is counted from: the other half of the sentence, and a question
        // only a span has. A calendar answers it by being a calendar.
        if (recurrence is Recurrence.After) {
            Spacer(Modifier.height(spacing.md))
            Text(
                text = stringResource(R.string.recur_counts_from),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.xs))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                RecurrenceButton(
                    label = stringResource(R.string.recur_from_ringing),
                    selected = recurrence.countsFromRinging,
                    onClick = { onCustom(recurrence.copy(from = RecurrenceFrom.RANG)) },
                )
                RecurrenceButton(
                    label = stringResource(R.string.recur_from_dealt),
                    selected = !recurrence.countsFromRinging,
                    onClick = { onCustom(recurrence.copy(from = RecurrenceFrom.DEALT)) },
                )
            }
            // And which DAY, when the rules only allow some of them. "Los viernes a las 14:00,
            // y vuelve cada 30 días" has three honest readings and the app used to pick one
            // without saying so — see [SpanLanding]. Asked only where it means anything: a span
            // in hours never bends to a weekday, and rules that name no days have nothing to
            // bend to.
            if (recurrence.unit != RecurrenceUnit.HOURS && rulesDays.isNotEmpty()) {
                Spacer(Modifier.height(spacing.md))
                Text(
                    text = stringResource(R.string.recur_landing),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(spacing.xs))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    for (landing in SpanLanding.entries) {
                        RecurrenceButton(
                            label = stringResource(landing.labelRes),
                            selected = recurrence.landing == landing,
                            // "Justo el plazo" takes the rules out of the loop, so the hour they
                            // were naming goes with them — and the row below, which said it
                            // decided nothing, is suddenly the only thing that does. Adopting
                            // that hour here is the difference between "exactamente cada 30
                            // días a las 14:00" and one that quietly rings at breakfast.
                            onClick = {
                                val adopt = rulesHour
                                    ?.takeIf { landing == SpanLanding.EXACT && recurrence.hour == RecurrenceHour.DayStart }
                                    ?.let { RecurrenceHour.At(it) }
                                onCustom(recurrence.copy(landing = landing, hour = adopt ?: recurrence.hour))
                            },
                        )
                    }
                }
                Spacer(Modifier.height(spacing.xs))
                Text(
                    text = stringResource(recurrence.landing.hintRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // And which hour it lands on, which "cada día" leaves open and the app used to
            // answer on its own. Not asked of a span counted in hours: that one is exact by
            // definition, and an hour of the day means nothing to it.
            if (recurrence.unit != RecurrenceUnit.HOURS) {
                Spacer(Modifier.height(spacing.md))
                Text(
                    text = stringResource(R.string.recur_hour),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(spacing.xs))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    RecurrenceButton(
                        label = stringResource(R.string.recur_hour_day_start),
                        selected = recurrence.hour == RecurrenceHour.DayStart,
                        onClick = { onCustom(recurrence.copy(hour = RecurrenceHour.DayStart)) },
                    )
                    RecurrenceButton(
                        label = stringResource(R.string.recur_hour_same),
                        selected = recurrence.hour == RecurrenceHour.Same,
                        onClick = { onCustom(recurrence.copy(hour = RecurrenceHour.Same)) },
                    )
                    RecurrenceButton(
                        label = stringResource(R.string.recur_hour_custom),
                        selected = recurrence.hour is RecurrenceHour.At,
                        // Starting from the hour this person means by "a las nueve", so the
                        // button never opens on a time nobody chose.
                        onClick = { onCustom(recurrence.copy(hour = RecurrenceHour.At(defaultTime))) },
                    )
                }
                (recurrence.hour as? RecurrenceHour.At)?.let { chosen ->
                    Spacer(Modifier.height(spacing.sm))
                    TimeField(
                        time = chosen.time,
                        onChange = { onCustom(recurrence.copy(hour = RecurrenceHour.At(it))) },
                    )
                }
                // Under "justo el plazo" the rules have stopped deciding, so the note that says
                // this control decides nothing would be exactly wrong: it decides everything.
                val hourNote = when {
                    recurrence.landsExactly -> R.string.recur_hour_exact_note
                    rulesNameAnHour -> R.string.recur_hour_rules_note
                    else -> null
                }
                if (hourNote != null) {
                    Spacer(Modifier.height(spacing.xs))
                    Text(
                        text = stringResource(hourNote),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // What is set, in words — and only when it is not already written on a button,
            // which is what the line was always for and never actually checked.
            if (spans.none { recurrence.sameSpanAs(it.recurrence) }) {
                Spacer(Modifier.height(spacing.sm))
                Text(
                    text = recurrenceLabel(rememberWords(), recurrence, today),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // A calendar reads itself back, and carries the fences the rule it used to be could
        // carry: "el día 1, y sólo si estoy en casa" is the one thing the move would have lost.
        if (calendar != null) {
            Spacer(Modifier.height(spacing.md))
            Text(
                text = repeatSummary(rememberWords(), calendar, today),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (warning != null) FieldWarning(stringResource(warning))
            RecurrenceConditions(
                conditions = recurrence.conditions,
                onAdd = onAddCondition,
                onEdit = onEditCondition,
                onRemove = onRemoveCondition,
            )
        }
    }

    if (listing) {
        RecurrenceListDialog(
            presets = presets,
            selected = recurrence,
            today = today,
            onPick = {
                listing = false
                onPick(it)
            },
            onEdit = {
                listing = false
                editing = it.id
            },
            onDelete = onDeletePreset,
            onDismiss = { listing = false },
        )
    }
    editing?.let { id ->
        val existing = presets.firstOrNull { it.id == id }
        CustomRecurrenceDialog(
            initial = existing?.recurrence as? Recurrence.After
                ?: recurrence as? Recurrence.After
                ?: Recurrence.After(1, RecurrenceUnit.DAYS),
            initialName = existing?.name.orEmpty(),
            today = today,
            onConfirm = { built, name ->
                editing = null
                // The dialog says the span; the anchor and the hour already chosen on the card
                // stay, the same way picking a preset keeps them (pickRecurrencePreset).
                onCustom(recurrence.withSpanOf(built))
                // A preset being edited is saved whatever its name says — the built-in ones
                // have none, and an edit that changes nothing on the list is not an edit.
                if (existing != null || name.isNotBlank()) onSavePreset(existing?.id, name.trim(), built)
            },
            onDismiss = { editing = null },
        )
    }
}

/**
 * The calendar's fences: chips under what they restrict, because that is what they are — not
 * another way to ring, but a fence around this one.
 *
 * **Everything here has to look pressable on its own.** On a trigger row the same cluster sits
 * inside a family-coloured surface with an edge of its own, and the container does half the
 * saying; here it sits on the bare card under a line of grey summary text, so a bare text button
 * read as one more line of that summary and nobody found it. It wears the app's own "add one
 * more of these" shape instead — the same outlined pill as "nueva etiqueta", with the plus that
 * says what it does — and the chips get a control's line rather than a card's, which is the
 * token that means "this can be pressed". Both to [Sizes.touch], which they were under: a 32dp
 * chip is below the floor this app sets for anything a thumb has to hit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecurrenceConditions(
    conditions: List<Condition>,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
        modifier = Modifier.padding(top = Tokens.spacing.sm),
    ) {
        conditions.forEachIndexed { index, condition ->
            InputChip(
                selected = false,
                onClick = { onEdit(index) },
                colors = InputChipDefaults.inputChipColors(
                    containerColor = scheme.surfaceContainerHigh,
                    labelColor = scheme.onSurface,
                    leadingIconColor = scheme.onSurfaceVariant,
                    trailingIconColor = scheme.onSurfaceVariant,
                ),
                border = BorderStroke(Tokens.strokes.control, scheme.outline),
                label = { Text(conditionLabel(condition), style = MaterialTheme.typography.labelLarge) },
                leadingIcon = { Icon(Icons.Outlined.FilterAlt, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    // 48dp of thumb behind 20dp of glyph; see the same icon in Sections.kt.
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.editor_remove_condition),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clickable { onRemove(index) }
                            .size(20.dp),
                    )
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = Tokens.sizes.touch),
            )
        }
        OutlinedButton(
            onClick = onAdd,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(Tokens.strokes.control, scheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = scheme.surfaceContainerHigh,
                contentColor = scheme.onSurface,
            ),
            contentPadding = PaddingValues(horizontal = Tokens.spacing.md),
            modifier = Modifier.heightIn(min = Tokens.sizes.touch),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Tokens.spacing.sm))
            Text(
                text = stringResource(if (conditions.isEmpty()) R.string.editor_add_condition else R.string.editor_add_another_condition),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun RecurrenceButton(
    label: String?,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    contentDescription: String? = null,
) {
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = {
            haptics.perform(HapticFeedbackType.SegmentTick)
            onClick()
        },
        shape = MaterialTheme.shapes.small,
        // On is inverted, like every other "on" in the app.
        color = if (selected) scheme.onSurface else scheme.surfaceContainerHigh,
        border = if (selected) null else BorderStroke(Tokens.strokes.control, scheme.outline),
        modifier = Modifier.heightIn(min = Tokens.sizes.touch),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Tokens.spacing.md),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = if (selected) scheme.surface else scheme.onSurface,
                    modifier = Modifier.width(20.dp),
                )
                if (label != null) Spacer(Modifier.width(Tokens.spacing.sm))
            }
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) scheme.surface else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The whole list, with the two things you can do to one you made yourself. */
@Composable
private fun RecurrenceListDialog(
    presets: List<RecurrencePreset>,
    selected: Recurrence,
    today: LocalDate,
    onPick: (RecurrencePreset) -> Unit,
    onEdit: (RecurrencePreset) -> Unit,
    onDelete: (String) -> Unit,
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
            Column(Modifier.padding(spacing.lg)) {
                Text(stringResource(R.string.recur_list_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(spacing.md))
                // Kept ones pile up, and this list had neither a scroll nor a word about
                // where it ended: past the dialog's cap the rows below were simply unreachable.
                val scroll = rememberScrollState()
                Column(
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .scrollFade(scroll, scheme.surfaceContainer)
                        .verticalScroll(scroll),
                ) {
                    for (preset in presets) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                onClick = { onPick(preset) },
                                shape = MaterialTheme.shapes.small,
                                color = if (preset.recurrence == selected) scheme.onSurface else scheme.surfaceContainerHigh,
                                border = if (preset.recurrence == selected) null else BorderStroke(Tokens.strokes.control, scheme.outline),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = Tokens.sizes.touch),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = spacing.md)) {
                                    Text(
                                        text = presetLabel(preset, today),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (preset.recurrence == selected) scheme.surface else scheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            // Only a span can be built out of the parts this dialog has. One
                            // kept from before the calendar moved here says something this
                            // pane cannot say, and an "edit" that quietly rewrote it as
                            // "cada día" would be losing it rather than editing it.
                            if (preset.recurrence is Recurrence.After) {
                                IconButton(onClick = { onEdit(preset) }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.recur_edit), tint = scheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { onDelete(preset.id) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.recur_delete), tint = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(spacing.sm))
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurface),
                    modifier = Modifier
                        .align(Alignment.End)
                        .heightIn(min = Tokens.sizes.touch),
                ) { Text(stringResource(R.string.common_done)) }
            }
        }
    }
}

/**
 * A span built from parts: how many, of what. The name field sits above them on purpose — it is
 * the answer to "will I want this again?", and that is worth asking before the fiddling rather
 * than after.
 *
 * Spans only. The other shape a recurrence can have is a calendar, and a calendar is five
 * controls and two month grids deep — it has its own sheet, at its own height, rather than a
 * segmented control squeezing it into a dialog capped at 620dp.
 */
@Composable
private fun CustomRecurrenceDialog(
    initial: Recurrence.After,
    initialName: String,
    today: LocalDate,
    onConfirm: (Recurrence, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    var name by rememberSaveable { mutableStateOf(initialName) }
    var amount by rememberSaveable { mutableStateOf(initial.amount) }
    var unit by rememberSaveable { mutableStateOf(initial.unit.name) }

    val built = Recurrence.After(amount, RecurrenceUnit.valueOf(unit), initial.from, initial.hour)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = scheme.surfaceContainer,
            border = BorderStroke(Tokens.strokes.edge, scheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 620.dp),
        ) {
            Column(Modifier.padding(spacing.lg)) {
                Text(stringResource(R.string.recur_custom_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(spacing.md))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.recur_save_as)) },
                    placeholder = { Text(stringResource(R.string.recur_save_as_hint)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = scheme.outline,
                        unfocusedBorderColor = scheme.outlineVariant,
                        cursorColor = scheme.primary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(spacing.lg))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.recur_every), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Stepper(
                        valueLabel = amount.toString(),
                        onDecrement = { amount = (amount - 1).coerceAtLeast(MIN_RECURRENCE_AMOUNT) },
                        onIncrement = { amount = (amount + 1).coerceAtMost(MAX_RECURRENCE_AMOUNT) },
                        decrementEnabled = amount > MIN_RECURRENCE_AMOUNT,
                        incrementEnabled = amount < MAX_RECURRENCE_AMOUNT,
                    )
                }
                Spacer(Modifier.height(spacing.sm))
                SegmentedChoice(
                    options = listOf(
                        stringResource(R.string.recur_unit_hours),
                        stringResource(R.string.recur_unit_days),
                        stringResource(R.string.recur_unit_weeks),
                        stringResource(R.string.recur_unit_months),
                        stringResource(R.string.recur_unit_years),
                    ),
                    selectedIndex = RecurrenceUnit.valueOf(unit).ordinal,
                    onSelect = { unit = RecurrenceUnit.entries[it].name },
                )
                Spacer(Modifier.height(spacing.sm))
                // Which moment it counts from is asked on the card, not here: this pane
                // builds the span, and a second copy of that question would be a second
                // place for the two to disagree.
                Text(
                    text = stringResource(R.string.recur_after_done),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(spacing.lg))
                Text(
                    text = recurrenceLabel(rememberWords(), built, today),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurfaceVariant),
                        modifier = Modifier.heightIn(min = Tokens.sizes.control),
                    ) { Text(stringResource(R.string.sheet_cancel)) }
                    Button(
                        onClick = { onConfirm(built, name) },
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.onSurface, contentColor = scheme.surface),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Tokens.sizes.control),
                    ) { Text(stringResource(R.string.sheet_done), style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}

/** A preset's own name, or the shape's own words when it has none. */
@Composable
private fun presetLabel(preset: RecurrencePreset, today: LocalDate): String {
    val words = rememberWords()
    return preset.name.ifEmpty { recurrenceLabel(words, preset.recurrence, today) }
}


/** The three readings of where a span lands; see [SpanLanding]. */
private val SpanLanding.labelRes: Int
    get() = when (this) {
        SpanLanding.NEXT -> R.string.recur_landing_next
        SpanLanding.NEAREST -> R.string.recur_landing_nearest
        SpanLanding.EXACT -> R.string.recur_landing_exact
    }

private val SpanLanding.hintRes: Int
    get() = when (this) {
        SpanLanding.NEXT -> R.string.recur_landing_next_hint
        SpanLanding.NEAREST -> R.string.recur_landing_nearest_hint
        SpanLanding.EXACT -> R.string.recur_landing_exact_hint
    }
