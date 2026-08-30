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
 * A place condition picks from the places kept by name rather than dropping a pin here. A
 * circle you are going to refer to by name in a sentence is a circle worth naming once, and
 * every arbitrary pin dropped here would be one more unnamed place to recognise later. So the
 * way to a new one is to *name* one — "Nuevo lugar" opens the place sheet in its Settings
 * shape and keeps what it makes — which is what made "y sólo si estoy en casa" reachable from
 * the editor at all: the kind used to be hidden until somebody had been to Settings first.
 */
@Composable
fun ConditionSheet(
    initial: Condition?,
    savedPlaces: List<SavedPlace>,
    onConfirm: (Condition) -> Unit,
    onDismiss: () -> Unit,
    /** Where a place named here is kept, so it is on the chips the moment it is made. */
    onKeepPlace: (SavedPlace) -> Unit = {},
) {
    val window = initial as? Condition.TimeWindow
    val atPlace = initial as? Condition.AtPlace
    var place by rememberSaveable { mutableStateOf(atPlace != null) }
    var addingPlace by rememberSaveable { mutableStateOf(false) }
    var from by rememberTime(window?.from ?: LocalTime.of(18, 0))
    var to by rememberTime(window?.to ?: LocalTime.of(22, 0))
    var days by rememberSaveable { mutableStateOf(window?.days?.map { it.name }?.toSet() ?: emptySet()) }
    var inside by rememberSaveable { mutableStateOf(atPlace?.inside ?: true) }
    // The condition's own place is offered too when nothing saved carries its name any more —
    // deleted or renamed in Settings since — so re-opening it does not quietly point it at
    // whichever place happens to be first.
    val offered = if (atPlace != null && savedPlaces.none { it.label == atPlace.label }) {
        savedPlaces + SavedPlace(atPlace.label, atPlace.lat, atPlace.lng, atPlace.radiusM)
    } else {
        savedPlaces
    }
    // By name and not by index: a place kept from here lands wherever the list puts it (a
    // renamed one replaces its namesake), and the chip that should light is the one just made.
    var chosenLabel by rememberSaveable { mutableStateOf(atPlace?.label ?: offered.firstOrNull()?.label) }
    val selected = days.map(DayOfWeek::valueOf).toSet()
    val pickedPlace = offered.firstOrNull { it.label == chosenLabel } ?: offered.firstOrNull()

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
        SegmentedChoice(
            options = listOf(stringResource(R.string.condition_kind_hours), stringResource(R.string.condition_kind_place)),
            selectedIndex = if (place) 1 else 0,
            onSelect = { place = it == 1 },
        )
        if (place) {
            Text(
                text = stringResource(if (offered.isEmpty()) R.string.condition_place_none else R.string.condition_place_hint),
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
                for (saved in offered) {
                    PresetChip(saved.label, selected = saved.label == pickedPlace?.label, onClick = { chosenLabel = saved.label })
                }
                PresetChip(stringResource(R.string.condition_new_place), selected = false, onClick = { addingPlace = true })
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
            PresetChip(stringResource(R.string.trigger_any_day), selected = selected.isEmpty(), onClick = { days = emptySet() })
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
    if (addingPlace) {
        // The same sheet Settings keeps a place with, over this one: a name, a pin, a radius,
        // and no arriving/leaving — a condition is a side of the line, not a crossing of it.
        LocationSheet(
            initial = null,
            title = stringResource(R.string.place_saved_title),
            pickTransition = false,
            onConfirm = { made ->
                onKeepPlace(SavedPlace(made.label, made.lat, made.lng, made.radiusM))
                chosenLabel = made.label
                addingPlace = false
            },
            onDismiss = { addingPlace = false },
        )
    }
}
