package dev.rwilco.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.AppSettings
import dev.rwilco.model.CustomSnoozeRefusal
import dev.rwilco.model.MAX_CUSTOM_SNOOZES
import dev.rwilco.model.SnoozeDay
import dev.rwilco.model.SnoozePart
import dev.rwilco.model.SnoozeSpec
import dev.rwilco.model.builtInSaying
import dev.rwilco.model.customSnoozeRefusal
import dev.rwilco.model.snoozeTerms
import dev.rwilco.model.until
import dev.rwilco.ui.components.DayToggles
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.components.SheetScaffold
import dev.rwilco.ui.components.Stepper
import dev.rwilco.ui.components.TimeField
import dev.rwilco.ui.components.rememberNow
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.format.rememberWords
import dev.rwilco.ui.format.snoozeLabel
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import java.time.Clock

/**
 * "Añadir un posponer": a snooze of the person's own, built out of the two things one can be — a
 * length ("dentro de 3 días") or a day and a moment of it ("el finde por la noche").
 *
 * **It has no name to type.** What the button will say is worked out from the snooze itself
 * ([snoozeLabel]) and shown here as it is built, with the moment it would come back at if it were
 * pressed right now — because "el finde por la noche" is words, and which night that is depends on
 * where somebody drew their weekend, three screens away. The three parts of the day are the
 * hours under "Tu día" and go on following them; an hour of its own is for everything else.
 *
 * What cannot be added says why instead of being a dead button: a copy of one the app already
 * offers, one already there, one more than the alert has room for ([customSnoozeRefusal]).
 */
@Composable
fun SnoozeBuilderSheet(settings: AppSettings, clock: Clock, onAdd: (SnoozeSpec) -> Unit, onDismiss: () -> Unit) {
    val spacing = Tokens.spacing
    val words = rememberWords()
    var draft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf(SnoozeDraft()) }
    val now by rememberNow(60_000, clock)
    val spec = draft.spec
    val refusal = settings.customSnoozeRefusal(spec)
    val back = spec.until(now, clock.zone, settings.snoozeTerms)?.atZone(clock.zone)

    SheetScaffold(
        title = stringResource(R.string.snooze_builder_title),
        onDismiss = onDismiss,
        onConfirm = { onAdd(spec) },
        confirmLabel = stringResource(R.string.sheet_add),
        confirmEnabled = refusal == null,
        dirty = draft != SnoozeDraft(),
    ) {
        SegmentedChoice(
            options = listOf(stringResource(R.string.snooze_builder_on), stringResource(R.string.snooze_builder_after)),
            selectedIndex = if (draft.length) 1 else 0,
            onSelect = { draft = draft.copy(length = it == 1) },
        )
        if (draft.length) {
            Stepper(
                valueLabel = draft.amount.toString(),
                onDecrement = { draft = draft.stepped(-1) },
                onIncrement = { draft = draft.stepped(+1) },
                decrementEnabled = draft.canStep(-1),
                incrementEnabled = draft.canStep(+1),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                for (unit in SnoozeUnit.entries) {
                    PresetChip(label = stringResource(unit.label), selected = draft.unit == unit, onClick = { draft = draft.counted(unit) })
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Caption(stringResource(R.string.snooze_builder_which_day))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    PresetChip(stringResource(R.string.snooze_builder_today), selected = draft.day == SnoozeDay.Today, onClick = { draft = draft.copy(day = SnoozeDay.Today) })
                    PresetChip(stringResource(R.string.snooze_builder_tomorrow), selected = draft.day == SnoozeDay.Tomorrow, onClick = { draft = draft.copy(day = SnoozeDay.Tomorrow) })
                    PresetChip(stringResource(R.string.snooze_weekend), selected = draft.day == SnoozeDay.Weekend, onClick = { draft = draft.copy(day = SnoozeDay.Weekend) })
                }
                // One day of the week, on the toggles every other week in the app is drawn with.
                DayToggles(
                    selected = setOfNotNull((draft.day as? SnoozeDay.Weekday)?.day),
                    onToggle = { draft = draft.copy(day = SnoozeDay.Weekday(it)) },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Caption(stringResource(R.string.snooze_builder_which_hour))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    for (part in SnoozePart.entries) {
                        PresetChip(label = stringResource(part.label), selected = draft.part == part, onClick = { draft = draft.copy(part = part) })
                    }
                    PresetChip(label = stringResource(R.string.snooze_builder_at), selected = draft.part == null, onClick = { draft = draft.copy(part = null) })
                }
                if (draft.part == null) {
                    TimeField(time = draft.time, onChange = { draft = draft.copy(time = it) }, modifier = Modifier.fillMaxWidth())
                } else {
                    Caption(stringResource(R.string.snooze_builder_parts_hint))
                }
            }
        }
        // What the button will say, and what pressing it right now would mean.
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(text = snoozeLabel(words, spec), style = MaterialTheme.typography.titleMedium)
            if (back != null) {
                Text(
                    text = stringResource(
                        R.string.snooze_builder_would,
                        dayWord(words, back.toLocalDate(), now.atZone(clock.zone).toLocalDate()) + " " + TimeText.time(back.toLocalTime(), words.is24h, words.locale),
                    ),
                    style = MonoStyles.date,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Caption(stringResource(R.string.snooze_builder_gone))
            }
            val why = when (refusal) {
                CustomSnoozeRefusal.ALREADY_OFFERED ->
                    settings.builtInSaying(spec)?.let { stringResource(R.string.snooze_builder_refused_offered, snoozeLabel(words, it, settings.snoozeCustomMinutes)) }
                CustomSnoozeRefusal.ALREADY_YOURS -> stringResource(R.string.snooze_builder_refused_yours)
                CustomSnoozeRefusal.TOO_MANY -> stringResource(R.string.snooze_builder_refused_many, MAX_CUSTOM_SNOOZES)
                // The steppers cannot reach it; nothing to say about a length nobody can ask for.
                CustomSnoozeRefusal.OUT_OF_RANGE, null -> null
            }
            if (why != null) {
                Text(text = why, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val SnoozeUnit.label: Int
    get() = when (this) {
        SnoozeUnit.MINUTES -> R.string.snooze_builder_unit_minutes
        SnoozeUnit.HOURS -> R.string.snooze_builder_unit_hours
        SnoozeUnit.DAYS -> R.string.snooze_builder_unit_days
    }

private val SnoozePart.label: Int
    get() = when (this) {
        SnoozePart.MORNING -> R.string.snooze_builder_morning
        SnoozePart.AFTERNOON -> R.string.snooze_builder_afternoon
        SnoozePart.EVENING -> R.string.snooze_builder_evening
    }

private val DraftSaver = listSaver<SnoozeDraft, Any>(save = { it.saved() }, restore = { snoozeDraftOf(it) })
