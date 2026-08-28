package dev.rwilco.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.SafetyNetLimits
import dev.rwilco.model.SafetyNetSettings
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.Stepper
import dev.rwilco.ui.theme.Tokens

/**
 * What the safety net waits for, and where it refuses to be armed.
 *
 * The three numbers are the whole of the rule, so this is also where the rule is written down:
 * a reminder that rang and was let go gets one quiet word about it, after **a tenth of the gap
 * to its next ring** or **the longest wait**, whichever comes first — and nothing at all when
 * the rings are closer together than the floor, because there the next ring is already the net.
 *
 * They are defaults in the honest sense: the switch is per reminder (in "qué pasa"), and these
 * say how long it means. Changing them changes every reminder that carries one, which is the
 * point — the alternative is three numbers on every reminder, and nobody wants to answer that
 * twice.
 */
@Composable
fun SafetyNetCard(settings: SafetyNetSettings, onChange: (SafetyNetSettings) -> Unit) {
    val spacing = Tokens.spacing
    RwilcoCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
            Text(
                text = stringResource(R.string.settings_net_about),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                SettingTitle(
                    title = stringResource(R.string.settings_net_after),
                    info = stringResource(R.string.settings_net_after_hint),
                )
                Spacer(Modifier.height(spacing.sm))
                Stepper(
                    valueLabel = stringResource(R.string.settings_net_after_value, settings.afterHours),
                    onDecrement = { onChange(settings.copy(afterHours = settings.afterHours - 1)) },
                    onIncrement = { onChange(settings.copy(afterHours = settings.afterHours + 1)) },
                    decrementEnabled = settings.afterHours > SafetyNetLimits.AFTER_HOURS.first,
                    incrementEnabled = settings.afterHours < SafetyNetLimits.AFTER_HOURS.last,
                )
            }
            Column {
                SettingTitle(
                    title = stringResource(R.string.settings_net_fraction),
                    info = stringResource(R.string.settings_net_fraction_hint),
                )
                Spacer(Modifier.height(spacing.sm))
                Stepper(
                    valueLabel = stringResource(R.string.settings_net_fraction_value, settings.fraction),
                    onDecrement = { onChange(settings.copy(fraction = settings.fraction - 1)) },
                    onIncrement = { onChange(settings.copy(fraction = settings.fraction + 1)) },
                    decrementEnabled = settings.fraction > SafetyNetLimits.FRACTION.first,
                    incrementEnabled = settings.fraction < SafetyNetLimits.FRACTION.last,
                )
            }
            Column {
                SettingTitle(
                    title = stringResource(R.string.settings_net_floor),
                    info = stringResource(R.string.settings_net_floor_hint),
                )
                Spacer(Modifier.height(spacing.sm))
                Stepper(
                    valueLabel = stringResource(R.string.settings_net_floor_value, settings.minCadenceMinutes),
                    onDecrement = { onChange(settings.copy(minCadenceMinutes = settings.minCadenceMinutes - FLOOR_STEP)) },
                    onIncrement = { onChange(settings.copy(minCadenceMinutes = settings.minCadenceMinutes + FLOOR_STEP)) },
                    decrementEnabled = settings.minCadenceMinutes > SafetyNetLimits.MIN_CADENCE_MINUTES.first,
                    incrementEnabled = settings.minCadenceMinutes < SafetyNetLimits.MIN_CADENCE_MINUTES.last,
                )
            }
        }
    }
}

/** A quarter of an hour a tap: the floor is a rough line, not a setting anybody tunes to the minute. */
private const val FLOOR_STEP = 15
