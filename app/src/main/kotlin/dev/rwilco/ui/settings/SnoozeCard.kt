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
import dev.rwilco.model.SNOOZE_PLACES
import dev.rwilco.model.Snooze
import dev.rwilco.model.SnoozeLimits
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.Stepper
import dev.rwilco.ui.format.snoozeLabel
import dev.rwilco.ui.theme.Tokens

/**
 * The things about a snooze that are the person's to say: how long "a little later" is, which
 * offers the alert shows (0.137.0), and which two the notification carries — it has room for
 * three buttons and "hecho" is one.
 *
 * **Nothing chosen here takes an answer away.** The alert used to show every offer every time —
 * up to ten held buttons on the one screen answered half awake — and an offer switched off in
 * the middle row is one door further, behind "a otro momento", not gone ([snoozeBoard]).
 */
@Composable
fun SnoozeCard(
    settings: AppSettings,
    onCustomMinutes: (Int) -> Unit,
    onPick: (Snooze) -> Unit,
    /** An offer on the alert, or behind "a otro momento": by its name, or [SNOOZE_PLACES]. */
    onShown: (String, Boolean) -> Unit = { _, _ -> },
) {
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
                    title = stringResource(R.string.settings_snooze_shown),
                    info = stringResource(R.string.settings_snooze_shown_hint),
                )
                Spacer(Modifier.height(spacing.sm))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for (snooze in Snooze.entries) {
                        val shown = snooze.name !in settings.hiddenSnoozes
                        PresetChip(
                            label = snoozeLabel(snooze, settings.snoozeCustomMinutes),
                            selected = shown,
                            onClick = { onShown(snooze.name, !shown) },
                        )
                    }
                    // The phone's own offers, as one: "al llegar a casa", "al salir de aquí".
                    val placesShown = SNOOZE_PLACES !in settings.hiddenSnoozes
                    PresetChip(
                        label = stringResource(R.string.settings_snooze_shown_places),
                        selected = placesShown,
                        onClick = { onShown(SNOOZE_PLACES, !placesShown) },
                    )
                }
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
