package dev.rwilco.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.AppSettings
import dev.rwilco.model.Snooze
import dev.rwilco.model.SnoozeLimits
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.Stepper
import dev.rwilco.ui.format.snoozeLabel
import dev.rwilco.ui.theme.Tokens

/**
 * The two things about a snooze that are the person's to say: how long "a little later" is,
 * and which two offers the notification carries — it has room for three buttons and "hecho" is
 * one. The alert screen offers every answer regardless, so nothing chosen here takes one away.
 */
@Composable
fun SnoozeCard(settings: AppSettings, onCustomMinutes: (Int) -> Unit, onPick: (Snooze) -> Unit) {
    val spacing = Tokens.spacing
    RwilcoCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
            Column {
                SettingTitle(
                    title = stringResource(R.string.settings_snooze_custom),
                    info = stringResource(R.string.settings_snooze_custom_hint),
                )
                Spacer(Modifier.height(spacing.sm))
                Stepper(
                    valueLabel = snoozeLabel(Snooze.CUSTOM, settings.snoozeCustomMinutes),
                    onDecrement = { onCustomMinutes(settings.snoozeCustomMinutes - SnoozeLimits.STEP) },
                    onIncrement = { onCustomMinutes(settings.snoozeCustomMinutes + SnoozeLimits.STEP) },
                    decrementEnabled = settings.snoozeCustomMinutes > SnoozeLimits.CUSTOM_MINUTES.first,
                    incrementEnabled = settings.snoozeCustomMinutes < SnoozeLimits.CUSTOM_MINUTES.last,
                )
            }
            Column {
                SettingTitle(
                    title = stringResource(R.string.settings_snooze_notification),
                    info = stringResource(R.string.settings_snooze_notification_hint),
                )
                Spacer(Modifier.height(spacing.sm))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for (snooze in Snooze.entries) {
                        PresetChip(
                            label = snoozeLabel(snooze, settings.snoozeCustomMinutes),
                            selected = snooze.name in settings.notificationSnoozes,
                            onClick = { onPick(snooze) },
                        )
                    }
                }
            }
        }
    }
}
