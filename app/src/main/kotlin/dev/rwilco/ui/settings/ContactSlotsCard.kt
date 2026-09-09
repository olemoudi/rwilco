package dev.rwilco.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.ContactSlot
import dev.rwilco.model.DayWindow
import dev.rwilco.ui.components.DayToggles
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.TimeField
import dev.rwilco.ui.theme.Tokens
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * The openings one kind of contact is raised in.
 *
 * **This list is the budget.** One contact to an opening, so however many rows are here is how
 * many people a week the app will ever ask you about, and everybody else waits their turn. That
 * is the whole of the pacing, said in one place rather than as a number somewhere else — which
 * is why the hint under the title says it out loud instead of leaving it to be discovered.
 *
 * A day and two times, the same shape the weekend is set with next door. An empty list is a
 * kind that is never raised at all, which is a legitimate thing to want and needs no switch.
 */
@Composable
fun ContactSlotsCard(
    slots: List<ContactSlot>,
    onChange: (List<ContactSlot>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    RwilcoCard(modifier = modifier) {
        Column(Modifier.padding(spacing.lg)) {
            Text(
                text = stringResource(R.string.settings_contacts_hint),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            for ((index, slot) in slots.withIndex()) {
                Spacer(Modifier.height(spacing.lg))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_contacts_slot, index + 1),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { onChange(slots.filterIndexed { at, _ -> at != index }) },
                        modifier = Modifier.heightIn(min = Tokens.sizes.touch),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.settings_contacts_slot_remove))
                    }
                }
                Spacer(Modifier.height(spacing.xs))
                // One day, not a set: an opening is a day. DayToggles is a set of one here, and
                // tapping the day already on leaves it on rather than emptying the slot.
                DayToggles(
                    selected = setOf(slot.day),
                    onToggle = { day -> onChange(slots.replacedAt(index, slot.copy(day = day))) },
                )
                Spacer(Modifier.height(spacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    TimeField(
                        time = slot.window.from,
                        onChange = { from -> onChange(slots.replacedAt(index, slot.copy(window = slot.window.copy(from = from)))) },
                        label = stringResource(R.string.settings_contacts_from),
                        modifier = Modifier.weight(1f),
                    )
                    TimeField(
                        time = slot.window.to,
                        onChange = { to -> onChange(slots.replacedAt(index, slot.copy(window = slot.window.copy(to = to)))) },
                        label = stringResource(R.string.settings_contacts_to),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(spacing.sm))
            TextButton(
                onClick = { onChange(slots + ContactSlot(nextDay(slots), DayWindow(LocalTime.of(18, 0), LocalTime.of(20, 0)))) },
                modifier = Modifier.heightIn(min = Tokens.sizes.touch),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.height(spacing.xs))
                Text(stringResource(R.string.settings_contacts_add))
            }
        }
    }
}

private fun List<ContactSlot>.replacedAt(index: Int, slot: ContactSlot): List<ContactSlot> =
    mapIndexed { at, existing -> if (at == index) slot else existing }

/** A day nothing is on yet, so a new opening does not land on one that is already taken. */
private fun nextDay(slots: List<ContactSlot>): DayOfWeek =
    DayOfWeek.entries.firstOrNull { day -> slots.none { it.day == day } } ?: DayOfWeek.MONDAY
