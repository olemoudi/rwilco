package dev.rwilco.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.VibrationPattern
import dev.rwilco.model.VibrationRhythm
import dev.rwilco.model.VibrationStrength
import dev.rwilco.ui.alert.AlertRinger
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.theme.Tokens
import java.time.Duration

/**
 * What a reminder feels like, chosen by feel.
 *
 * A vibration is the one thing in this app nobody can look at, so the card ends in a button
 * that plays it — three seconds of it, not the minute an alarm gets. Choosing between "fuerte"
 * and "suave" by reading the words is choosing blind.
 */
@Composable
fun VibrationCard(pattern: VibrationPattern, onChange: (VibrationPattern) -> Unit) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val ringer = remember(context) { AlertRinger(context) }
    // A preview left buzzing behind a closed screen is the one bug this card could have.
    DisposableEffect(ringer) { onDispose { ringer.stop() } }

    RwilcoCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
            Column {
                SettingTitle(
                    title = stringResource(R.string.settings_vibration_strength),
                    info = stringResource(R.string.settings_vibration_hint),
                )
                Spacer(Modifier.height(spacing.sm))
                val strengths = listOf(VibrationStrength.STRONG, VibrationStrength.GENTLE)
                SegmentedChoice(
                    options = listOf(
                        stringResource(R.string.settings_vibration_strong),
                        stringResource(R.string.settings_vibration_gentle),
                    ),
                    selectedIndex = strengths.indexOf(pattern.strength).coerceAtLeast(0),
                    onSelect = { onChange(pattern.copy(strength = strengths[it])) },
                )
            }
            Column {
                SettingTitle(stringResource(R.string.settings_vibration_rhythm))
                Spacer(Modifier.height(spacing.sm))
                val rhythms = listOf(VibrationRhythm.PULSED, VibrationRhythm.CONTINUOUS)
                SegmentedChoice(
                    options = listOf(
                        stringResource(R.string.settings_vibration_pulsed),
                        stringResource(R.string.settings_vibration_continuous),
                    ),
                    selectedIndex = rhythms.indexOf(pattern.rhythm).coerceAtLeast(0),
                    onSelect = { onChange(pattern.copy(rhythm = rhythms[it])) },
                )
            }
            OutlinedButton(
                onClick = {
                    ringer.stop()
                    ringer.start(sound = false, vibrate = true, pattern = pattern, limit = PREVIEW)
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.touch),
            ) {
                Icon(Icons.Outlined.Vibration, contentDescription = null)
                Spacer(Modifier.width(spacing.sm))
                Text(stringResource(R.string.settings_vibration_try))
            }
            Text(
                text = stringResource(R.string.settings_vibration_limit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Long enough to tell one pattern from another, short enough that nobody waits for it to end. */
private val PREVIEW: Duration = Duration.ofSeconds(3)
