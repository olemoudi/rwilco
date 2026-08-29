package dev.rwilco.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import dev.rwilco.alarm.TestAlert
import dev.rwilco.model.Action
import dev.rwilco.model.toggling
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.home.labelRes
import dev.rwilco.ui.theme.Tokens

/**
 * The one row on this screen that proves the rest of it: a real reminder ten seconds out,
 * through the real path (see [TestAlert]).
 *
 * **And it asks what to try.** Everything at once answers "does anything arrive"; the questions
 * people actually have are narrower — does the full screen come up over the lock, is the alarm
 * sound too much at alarm volume, does the buzz reach me in a pocket — and each of those wants
 * the others out of the way. The tiles are the reminder's own ([Action]), because a rehearsal
 * that could ask for something a reminder cannot is a rehearsal of nothing.
 *
 * The choice is not a setting: it is remembered while the screen is open and forgotten after,
 * like the words of a search.
 */
@Composable
fun TestAlertCard(onTest: (Set<Action>) -> Unit, modifier: Modifier = Modifier) {
    val spacing = Tokens.spacing
    // Kept by name, which is what a Bundle can hold: the choice survives a rotation and dies
    // with the screen, like the words of a search.
    var chosen by rememberSaveable { mutableStateOf(TestAlert.EVERYTHING.map { it.name }) }
    val actions = chosen.mapNotNull { name -> Action.entries.firstOrNull { it.name == name } }.toSet()
    RwilcoCard(modifier = modifier) {
        Column(Modifier.padding(spacing.lg)) {
            SettingTitle(
                title = stringResource(R.string.settings_test_alert),
                info = stringResource(R.string.settings_test_alert_hint),
            )
            Spacer(Modifier.height(spacing.sm))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (action in Action.entries) {
                    PresetChip(
                        label = stringResource(action.labelRes),
                        selected = action in actions,
                        onClick = { chosen = actions.toggling(action).map { it.name } },
                    )
                }
            }
            Spacer(Modifier.height(spacing.md))
            Button(
                onClick = { onTest(actions) },
                // Nothing switched on is nothing to try: the moment would pass in silence.
                enabled = actions.isNotEmpty(),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.sizes.control),
            ) {
                Text(stringResource(R.string.settings_test_alert_go), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
