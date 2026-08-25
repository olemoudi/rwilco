package dev.rwilco.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.rwilco.model.LAST_ORDINAL
import dev.rwilco.model.MAX_RECURRENCE_AMOUNT
import dev.rwilco.model.MIN_RECURRENCE_AMOUNT
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrencePreset
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.components.Stepper
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.theme.Tokens
import java.time.DayOfWeek
import java.time.format.TextStyle

/** How many recurrences get a button of their own before the rest go behind the dots. */
private const val VISIBLE_PRESETS = 4

/**
 * What "hecho" leaves behind, and when it comes back.
 *
 * The answers people give most are buttons — "no repetir" first, because it is the default and
 * the way back — and everything else is one tap further in: the dots open the whole list, and
 * "personalizado" builds one from parts. A recurrence somebody builds can be kept under a name
 * from inside that same pane, which is what puts it in the row next time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecurrenceSection(
    recurrence: Recurrence,
    presets: List<RecurrencePreset>,
    onPick: (RecurrencePreset) -> Unit,
    onCustom: (Recurrence) -> Unit,
    onSavePreset: (String?, String, Recurrence) -> Unit,
    onDeletePreset: (String) -> Unit,
) {
    var listing by rememberSaveable { mutableStateOf(false) }
    // The preset being built or edited: null closed, "" a new one, otherwise its id.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val spacing = Tokens.spacing

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
            for (preset in presets.take(VISIBLE_PRESETS - 1)) {
                RecurrenceButton(
                    label = presetLabel(preset),
                    selected = recurrence == preset.recurrence,
                    onClick = { onPick(preset) },
                )
            }
        }
        Spacer(Modifier.height(spacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalAlignment = Alignment.CenterVertically) {
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
                icon = Icons.Outlined.Tune,
                label = stringResource(R.string.recur_custom),
                // Selected when what is set is not one of the buttons above.
                selected = recurrence != Recurrence.None && presets.take(VISIBLE_PRESETS - 1).none { it.recurrence == recurrence },
                onClick = { editing = "" },
            )
        }
        // What is set, in words, when it is not already written on a button.
        if (recurrence != Recurrence.None) {
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = recurrenceLabel(recurrence),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (listing) {
        RecurrenceListDialog(
            presets = presets,
            selected = recurrence,
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
            initial = existing?.recurrence ?: recurrence.takeIf { it != Recurrence.None } ?: Recurrence.After(1, RecurrenceUnit.DAYS),
            initialName = existing?.name.orEmpty(),
            onConfirm = { built, name ->
                editing = null
                onCustom(built)
                if (name.isNotBlank()) onSavePreset(existing?.id, name.trim(), built)
            },
            onDismiss = { editing = null },
        )
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
        modifier = Modifier.heightIn(min = 44.dp),
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
                Column(
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    modifier = Modifier.weight(1f, fill = false),
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
                                        text = presetLabel(preset),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (preset.recurrence == selected) scheme.surface else scheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            IconButton(onClick = { onEdit(preset) }) {
                                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.recur_edit), tint = scheme.onSurfaceVariant)
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
 * One built from parts. The name field sits above the parts on purpose: it is the answer to
 * "will I want this again?", and that is worth asking before the fiddling rather than after.
 */
@Composable
private fun CustomRecurrenceDialog(
    initial: Recurrence,
    initialName: String,
    onConfirm: (Recurrence, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    var name by rememberSaveable { mutableStateOf(initialName) }
    var byMonthDay by rememberSaveable { mutableStateOf(initial is Recurrence.MonthlyWeekday) }
    var amount by rememberSaveable { mutableStateOf((initial as? Recurrence.After)?.amount ?: 1) }
    var unit by rememberSaveable { mutableStateOf(((initial as? Recurrence.After)?.unit ?: RecurrenceUnit.DAYS).name) }
    var ordinal by rememberSaveable { mutableStateOf((initial as? Recurrence.MonthlyWeekday)?.ordinal ?: 1) }
    var weekday by rememberSaveable { mutableStateOf(((initial as? Recurrence.MonthlyWeekday)?.day ?: DayOfWeek.SUNDAY).name) }
    val locale = currentLocale()
    val ordinals = stringArrayResource(R.array.recur_ordinals)

    val built: Recurrence = if (byMonthDay) {
        Recurrence.MonthlyWeekday(ordinal, DayOfWeek.valueOf(weekday))
    } else {
        Recurrence.After(amount, RecurrenceUnit.valueOf(unit))
    }

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
                SegmentedChoice(
                    options = listOf(stringResource(R.string.recur_kind_after), stringResource(R.string.recur_kind_monthly)),
                    selectedIndex = if (byMonthDay) 1 else 0,
                    onSelect = { byMonthDay = it == 1 },
                )
                Spacer(Modifier.height(spacing.lg))
                if (byMonthDay) {
                    SectionTitle(stringResource(R.string.recur_which_one))
                    SegmentedChoice(
                        options = ordinals.toList(),
                        selectedIndex = if (ordinal >= LAST_ORDINAL) ordinals.lastIndex else ordinal - 1,
                        onSelect = { ordinal = if (it == ordinals.lastIndex) LAST_ORDINAL else it + 1 },
                    )
                    Spacer(Modifier.height(spacing.md))
                    SectionTitle(stringResource(R.string.random_days_label))
                    WeekdayChoice(selected = DayOfWeek.valueOf(weekday), onSelect = { weekday = it.name })
                } else {
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
                        ),
                        selectedIndex = RecurrenceUnit.valueOf(unit).ordinal,
                        onSelect = { unit = RecurrenceUnit.entries[it].name },
                    )
                    Spacer(Modifier.height(spacing.sm))
                    Text(
                        text = stringResource(R.string.recur_after_done),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(spacing.lg))
                Text(
                    text = recurrenceLabel(built),
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

/** Seven initials in the locale's week order; the same shape as the day toggles elsewhere. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayChoice(selected: DayOfWeek, onSelect: (DayOfWeek) -> Unit) {
    val locale = currentLocale()
    val days = remember(locale) { List(7) { java.time.temporal.WeekFields.of(locale).firstDayOfWeek.plus(it.toLong()) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.xs), verticalArrangement = Arrangement.spacedBy(Tokens.spacing.xs)) {
        for (day in days) {
            RecurrenceButton(
                label = day.getDisplayName(TextStyle.SHORT, locale),
                selected = day == selected,
                onClick = { onSelect(day) },
            )
        }
    }
}

/** A preset's own name, or the shape's own words when it has none. */
@Composable
private fun presetLabel(preset: RecurrencePreset): String =
    preset.name.ifEmpty { recurrenceLabel(preset.recurrence) }

/** A recurrence in as few words as it can be said in. */
@Composable
fun recurrenceLabel(recurrence: Recurrence): String {
    val locale = currentLocale()
    return when (recurrence) {
        Recurrence.None -> stringResource(R.string.recur_none)
        Recurrence.ByTrigger -> stringResource(R.string.recur_by_trigger)
        is Recurrence.After -> when (recurrence.unit) {
            RecurrenceUnit.HOURS -> stringResource(R.string.recur_hours, recurrence.amount)
            RecurrenceUnit.DAYS ->
                if (recurrence.amount == 1) stringResource(R.string.recur_next_day)
                else stringResource(R.string.recur_days, recurrence.amount)
            RecurrenceUnit.WEEKS ->
                if (recurrence.amount == 1) stringResource(R.string.recur_week)
                else stringResource(R.string.recur_weeks, recurrence.amount)
            RecurrenceUnit.MONTHS ->
                if (recurrence.amount == 1) stringResource(R.string.recur_month)
                else stringResource(R.string.recur_months, recurrence.amount)
        }
        is Recurrence.MonthlyWeekday -> {
            val ordinals = stringArrayResource(R.array.recur_ordinals)
            val ordinal = if (recurrence.ordinal >= LAST_ORDINAL) ordinals.last() else ordinals[(recurrence.ordinal - 1).coerceIn(ordinals.indices)]
            stringResource(R.string.recur_monthly_weekday, ordinal, recurrence.day.getDisplayName(TextStyle.FULL, locale))
        }
    }
}
