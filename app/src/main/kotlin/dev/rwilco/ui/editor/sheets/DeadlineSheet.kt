package dev.rwilco.ui.editor.sheets

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.Deadline
import dev.rwilco.model.SavedWindow
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.components.SheetScaffold
import dev.rwilco.ui.components.Stepper
import dev.rwilco.ui.components.TimeField
import dev.rwilco.ui.theme.Tokens
import java.time.LocalTime

/**
 * How long a set of rules has to complete: hours of the day on the day the set is due, or so
 * many minutes from the first thing that happens. The second is only offered under "todos"
 * ([allowTimer]) — "a la vez" has no first trigger, only an instant at which everything holds.
 *
 * Shaped like [ConditionSheet]: a two-way choice, then the half that applies. The hours are the
 * same two fields and the same named stretches the window trigger offers, because it is the
 * same stretch of the day being asked for; the length is the countdown's two steppers.
 */
@Composable
fun DeadlineSheet(
    initial: Deadline?,
    allowTimer: Boolean,
    savedWindows: List<SavedWindow> = emptyList(),
    onConfirm: (Deadline) -> Unit,
    onDismiss: () -> Unit,
) {
    val window = initial as? Deadline.Window
    val initialTimer = initial as? Deadline.Timer
    var timer by rememberSaveable { mutableStateOf(initialTimer != null && allowTimer) }
    var from by rememberTime(window?.from ?: LocalTime.of(18, 0))
    var to by rememberTime(window?.to ?: LocalTime.of(22, 0))
    var minutes by rememberSaveable { mutableIntStateOf(initialTimer?.minutes ?: 120) }
    val hours = minutes / 60
    val rest = minutes % 60

    SheetScaffold(
        title = stringResource(R.string.deadline_sheet_title),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(if (timer) Deadline.Timer(minutes) else Deadline.Window(from, to)) },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
        confirmEnabled = if (timer) minutes in MIN_DEADLINE_MINUTES..MAX_DEADLINE_MINUTES else from != to,
    ) {
        if (allowTimer) {
            SegmentedChoice(
                options = listOf(stringResource(R.string.deadline_kind_window), stringResource(R.string.deadline_kind_timer)),
                selectedIndex = if (timer) 1 else 0,
                onSelect = { timer = it == 1 },
            )
        }
        Text(
            text = stringResource(if (timer) R.string.deadline_hint_timer else R.string.deadline_hint_window),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (timer) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                for (preset in PRESET_DEADLINE_MINUTES) {
                    val label = if (preset < 60) stringResource(R.string.countdown_minutes, preset) else stringResource(R.string.countdown_hours, preset / 60)
                    PresetChip(label = label, selected = minutes == preset, onClick = { minutes = preset })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.countdown_hours_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Stepper(
                        valueLabel = hours.toString(),
                        onDecrement = { minutes = (minutes - 60).coerceAtLeast(rest) },
                        onIncrement = { minutes = (minutes + 60).coerceAtMost(MAX_DEADLINE_MINUTES) },
                        decrementEnabled = hours > 0,
                        incrementEnabled = minutes + 60 <= MAX_DEADLINE_MINUTES,
                    )
                }
                Spacer(Modifier.width(Tokens.spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.countdown_minutes_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Stepper(
                        valueLabel = rest.toString(),
                        onDecrement = { minutes = (minutes - 1).coerceAtLeast(0) },
                        onIncrement = { minutes = (minutes + 1).coerceAtMost(MAX_DEADLINE_MINUTES) },
                        decrementEnabled = rest > 0,
                        incrementEnabled = minutes < MAX_DEADLINE_MINUTES,
                    )
                }
            }
            if (minutes < MIN_DEADLINE_MINUTES) {
                Text(
                    text = stringResource(R.string.countdown_zero_error),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            return@SheetScaffold
        }
        if (savedWindows.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                for (saved in savedWindows) {
                    PresetChip(
                        label = saved.label,
                        selected = saved.from == from && saved.to == to,
                        onClick = { from = saved.from; to = saved.to },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm), modifier = Modifier.fillMaxWidth()) {
            TimeField(time = from, onChange = { from = it }, label = stringResource(R.string.random_from), modifier = Modifier.weight(1f))
            TimeField(time = to, onChange = { to = it }, label = stringResource(R.string.random_to), modifier = Modifier.weight(1f))
        }
        if (from == to) {
            Text(
                text = stringResource(R.string.condition_window_error),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The lengths on the chips: the ones somebody means by "within the hour" and "by tonight". */
private val PRESET_DEADLINE_MINUTES = listOf(15, 30, 60, 120, 240, 480)

private const val MIN_DEADLINE_MINUTES = 1

/** A day. Longer than that is a window on the next day, which is what the other kind says. */
private const val MAX_DEADLINE_MINUTES = 24 * 60
