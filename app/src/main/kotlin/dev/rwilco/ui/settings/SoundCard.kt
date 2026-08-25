package dev.rwilco.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlayArrow
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
import dev.rwilco.model.Action
import dev.rwilco.model.AlertSound
import dev.rwilco.model.Chime
import dev.rwilco.model.SoundLimits
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.Stepper
import dev.rwilco.ui.theme.Tokens

/**
 * What a reminder sounds like, and — for the insistent one — how often it says it again.
 *
 * The four bundled chimes are the app's own, built the way a car builds one: two or three short
 * tones and then silence. A car does not shout, and neither should a phone that only wants you
 * to look at it. Every one of them plays on a tap, because choosing a sound by reading its name
 * is choosing blind.
 */
@Composable
fun SoundCard(
    sound: AlertSound,
    plays: Int,
    gapMinutes: Int,
    insistentInUse: Boolean,
    onSound: (AlertSound) -> Unit,
    onPlays: (Int) -> Unit,
    onGap: (Int) -> Unit,
) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val preview = remember(context) { SoundPreview(context) }
    DisposableEffect(preview) { onDispose { preview.stop() } }

    // Any audio file the phone can open. mp3 and wav are what people have; the rest is a bonus.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onSound(context.rememberSound(uri) ?: return@rememberLauncherForActivityResult)
    }

    RwilcoCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
            Column {
                SettingTitle(
                    title = stringResource(R.string.settings_sound_title),
                    info = stringResource(R.string.settings_sound_hint),
                )
                Spacer(Modifier.height(spacing.sm))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    for (chime in Chime.entries) {
                        val choice = AlertSound.Bundled(chime)
                        PresetChip(
                            label = stringResource(chime.labelRes),
                            selected = sound == choice,
                            onClick = {
                                onSound(choice)
                                preview.play(choice)
                            },
                        )
                    }
                    PresetChip(
                        label = stringResource(R.string.settings_sound_system),
                        selected = sound == AlertSound.System,
                        onClick = {
                            onSound(AlertSound.System)
                            preview.play(AlertSound.System)
                        },
                    )
                    (sound as? AlertSound.Custom)?.let { custom ->
                        PresetChip(label = custom.label, selected = true, onClick = { preview.play(custom) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { preview.play(sound) },
                    modifier = Modifier.weight(1f).heightIn(min = Tokens.sizes.touch),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(spacing.sm))
                    Text(stringResource(R.string.settings_sound_play))
                }
                OutlinedButton(
                    onClick = { pick.launch(AUDIO_TYPES) },
                    modifier = Modifier.weight(1f).heightIn(min = Tokens.sizes.touch),
                ) {
                    Icon(Icons.Outlined.LibraryMusic, contentDescription = null)
                    Spacer(Modifier.width(spacing.sm))
                    Text(stringResource(R.string.settings_sound_custom))
                }
            }
            // The two numbers only mean anything to a reminder that asks for the insistent
            // sound, so they only appear once something does.
            if (insistentInUse) {
                Column {
                    SettingTitle(
                        title = stringResource(R.string.settings_sound_plays),
                        info = stringResource(R.string.settings_sound_plays_hint),
                    )
                    Spacer(Modifier.height(spacing.sm))
                    Stepper(
                        valueLabel = stringResource(R.string.settings_sound_plays_value, plays),
                        onDecrement = { onPlays(plays - 1) },
                        onIncrement = { onPlays(plays + 1) },
                        decrementEnabled = plays > SoundLimits.PLAYS.first,
                        incrementEnabled = plays < SoundLimits.PLAYS.last,
                    )
                }
                Column {
                    SettingTitle(stringResource(R.string.settings_sound_gap))
                    Spacer(Modifier.height(spacing.sm))
                    Stepper(
                        valueLabel = stringResource(R.string.settings_sound_gap_value, gapMinutes),
                        onDecrement = { onGap(gapMinutes - 1) },
                        onIncrement = { onGap(gapMinutes + 1) },
                        decrementEnabled = gapMinutes > SoundLimits.GAP_MINUTES.first,
                        incrementEnabled = gapMinutes < SoundLimits.GAP_MINUTES.last,
                    )
                }
            }
        }
    }
}

private val AUDIO_TYPES = arrayOf("audio/mpeg", "audio/wav", "audio/x-wav", "audio/ogg", "audio/*")

val Chime.labelRes: Int
    get() = when (this) {
        Chime.ALERT -> R.string.sound_chime_alert
        Chime.TWO_TONE -> R.string.sound_chime_two_tone
        Chime.LOW -> R.string.sound_chime_low
        Chime.SOFT -> R.string.sound_chime_soft
    }

/** Whether anything at all asks for the sound that comes back. */
fun insistentInUse(defaults: Set<Action>, anyReminder: Boolean): Boolean =
    Action.SOUND_UNTIL_ANSWERED in defaults || anyReminder
