package dev.rwilco.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.DayWindow
import dev.rwilco.ui.components.DayToggles
import dev.rwilco.ui.components.TimeField
import dev.rwilco.ui.theme.Tokens
import java.time.DayOfWeek

/**
 * Whether part of a contact follows Settings or was set here by hand, and the way back
 * (`Contacts.kt`). Said on the form because nothing else on screen shows it: two contacts reading
 * "cada 3 meses" behave differently the day Settings change, and this is the only place to tell.
 */
@Composable
internal fun SettingsFollowRow(byHand: Boolean, follows: String, onReset: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.sizes.touch)
            .padding(top = Tokens.spacing.xs),
    ) {
        Text(
            text = if (byHand) stringResource(R.string.editor_contact_by_hand) else follows,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (byHand) {
            TextButton(onClick = onReset, modifier = Modifier.heightIn(min = Tokens.sizes.touch)) {
                Text(stringResource(R.string.editor_contact_reset))
            }
        }
    }
}

/**
 * When a contact is told about: its kind's days and window from Settings, or its own once changed
 * here — the same day discs and the same two times Settings are drawn with, so the two read as one
 * thing set in two places.
 */
@Composable
internal fun ContactWhenSection(
    days: Set<DayOfWeek>,
    window: DayWindow,
    byHand: Boolean,
    onToggleDay: (DayOfWeek) -> Unit,
    onWindow: (DayWindow) -> Unit,
    onReset: () -> Unit,
) {
    val spacing = Tokens.spacing
    DayToggles(selected = days, onToggle = onToggleDay)
    Spacer(Modifier.height(spacing.md))
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
        TimeField(
            time = window.from,
            onChange = { onWindow(window.copy(from = it)) },
            label = stringResource(R.string.settings_contacts_from),
            modifier = Modifier.weight(1f),
        )
        TimeField(
            time = window.to,
            onChange = { onWindow(window.copy(to = it)) },
            label = stringResource(R.string.settings_contacts_to),
            modifier = Modifier.weight(1f),
        )
    }
    SettingsFollowRow(byHand = byHand, follows = stringResource(R.string.editor_contact_follows), onReset = onReset)
}
