package dev.rwilco.ui.editor.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.Condition
import dev.rwilco.model.SavedPlace
import dev.rwilco.ui.components.DayToggles
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.components.SheetScaffold
import dev.rwilco.ui.components.TimeField
import dev.rwilco.ui.theme.Tokens
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * The restriction put on a trigger, in one of its two kinds: a stretch of the day, or a place
 * the phone has to be in (or out of).
 *
 * This is the other half of "al llegar a casa, y sólo si es por la tarde" — the trigger says
 * what happens, this says when it is allowed to mean anything. The place kind is what makes
 * "a las nueve, y sólo si estoy en casa" expressible: two things true at once, which is what a
 * condition has always been for and what the "todos" of several triggers is not.
 *
 * A place condition picks from the places kept in Settings rather than opening the map. A
 * circle you are going to refer to by name in a sentence is a circle worth naming once, and
 * every arbitrary pin dropped here would be one more unnamed place to recognise later.
 */
@Composable
fun ConditionSheet(
    initial: Condition?,
    savedPlaces: List<SavedPlace>,
    onConfirm: (Condition) -> Unit,
    onDismiss: () -> Unit,
) {
    val window = initial as? Condition.TimeWindow
    val atPlace = initial as? Condition.AtPlace
    // A new condition on a reminder with nowhere saved cannot be a place one, so it does not
    // pretend to offer it.
    val placeOffered = savedPlaces.isNotEmpty() || atPlace != null
    var place by rememberSaveable { mutableStateOf(atPlace != null) }
    var from by rememberTime(window?.from ?: LocalTime.of(18, 0))
    var to by rememberTime(window?.to ?: LocalTime.of(22, 0))
    var days by rememberSaveable { mutableStateOf(window?.days?.map { it.name }?.toSet() ?: emptySet()) }
    var inside by rememberSaveable { mutableStateOf(atPlace?.inside ?: true) }
    var chosen by rememberSaveable {
        mutableStateOf(savedPlaces.indexOfFirst { it.label == atPlace?.label }.coerceAtLeast(0))
    }
    val selected = days.map(DayOfWeek::valueOf).toSet()
    val pickedPlace = savedPlaces.getOrNull(chosen)

    SheetScaffold(
        title = stringResource(R.string.condition_title),
        onDismiss = onDismiss,
        onConfirm = {
            if (place && pickedPlace != null) {
                onConfirm(Condition.AtPlace(pickedPlace.lat, pickedPlace.lng, pickedPlace.radiusM, pickedPlace.label, inside))
            } else {
                onConfirm(Condition.TimeWindow(from, to, selected))
            }
        },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
        confirmEnabled = if (place) pickedPlace != null else from != to,
    ) {
        if (placeOffered) {
            SegmentedChoice(
                options = listOf(stringResource(R.string.condition_kind_hours), stringResource(R.string.condition_kind_place)),
                selectedIndex = if (place) 1 else 0,
                onSelect = { place = it == 1 },
            )
        }
        if (place) {
            Text(
                text = stringResource(R.string.condition_place_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SegmentedChoice(
                options = listOf(stringResource(R.string.condition_place_inside), stringResource(R.string.condition_place_outside)),
                selectedIndex = if (inside) 0 else 1,
                onSelect = { inside = it == 0 },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                savedPlaces.forEachIndexed { index, saved ->
                    PresetChip(saved.label, selected = index == chosen, onClick = { chosen = index })
                }
            }
            return@SheetScaffold
        }
        Text(
            text = stringResource(R.string.condition_window_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            PresetChip(stringResource(R.string.trigger_every_day), selected = selected.isEmpty(), onClick = { days = emptySet() })
            PresetChip(stringResource(R.string.trigger_weekdays), selected = selected == WEEKDAYS, onClick = { days = WEEKDAYS.map { it.name }.toSet() })
            PresetChip(stringResource(R.string.trigger_weekends), selected = selected == WEEKEND, onClick = { days = WEEKEND.map { it.name }.toSet() })
        }
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            DayToggles(selected = selected, onToggle = { day -> days = if (day.name in days) days - day.name else days + day.name })
            Text(
                text = stringResource(R.string.random_days_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
