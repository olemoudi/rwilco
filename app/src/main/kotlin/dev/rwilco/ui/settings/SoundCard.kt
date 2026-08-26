package dev.rwilco.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.media.AudioManager
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
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.Action
import dev.rwilco.model.AlertSound
import dev.rwilco.model.Chime
import dev.rwilco.model.SoundLimits
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.Stepper
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens

/**
 * What a reminder sounds like, and — for the insistent one — how often it says it again.
 *
 * The four bundled chimes are the app's own, built the way a car builds one: a low tone struck
 * and left to ring, not a beep. A car does not shout, and neither should a phone that only
 * wants you to look at it. Every one of them plays on a tap, because choosing a sound by
 * reading its name is choosing blind — and the insistent round can be heard whole, with its
 * waits shortened, rather than taken on trust.
 */
@Composable
fun SoundCard(
    sound: AlertSound,
    plays: Int,
    gapMinutes: Int,
    insistentInUse: Boolean,
    toHeadphones: Boolean,
    onSound: (AlertSound) -> Unit,
    onPlays: (Int) -> Unit,
    onGap: (Int) -> Unit,
    onToHeadphones: (Boolean) -> Unit,
) {
    val spacing = Tokens.spacing
    val haptics = Tokens.haptics
    val context = LocalContext.current
    val preview = remember(context) { SoundPreview(context) }
    DisposableEffect(preview) { onDispose { preview.stop() } }
    // Which preview is playing, if any: every button here is its own "parar" while it runs.
    var playing by remember { mutableStateOf<PreviewMode?>(null) }
    val listen: (PreviewMode) -> Unit = { mode ->
        if (playing != null) {
            preview.stop()
        } else {
            when (mode) {
                PreviewMode.ONCE -> preview.play(sound, toHeadphones) { playing = it }
                PreviewMode.LOOPING -> preview.playLooping(sound, toHeadphones) { playing = it }
                PreviewMode.ROUND -> preview.playRound(sound, plays, REHEARSAL_GAP_MS, toHeadphones) { playing = it }
            }
        }
    }

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
                                preview.play(choice, toHeadphones) { playing = it }
                            },
                        )
                    }
                    PresetChip(
                        label = stringResource(R.string.settings_sound_system),
                        selected = sound == AlertSound.System,
                        onClick = {
                            onSound(AlertSound.System)
                            preview.play(AlertSound.System, toHeadphones) { playing = it }
                        },
                    )
                    (sound as? AlertSound.Custom)?.let { custom ->
                        PresetChip(label = custom.label, selected = true, onClick = { preview.play(custom, toHeadphones) { playing = it } })
                    }
                }
            }
            AlarmVolume()
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { listen(PreviewMode.ONCE) },
                    modifier = Modifier.weight(1f).heightIn(min = Tokens.sizes.touch),
                ) {
                    Icon(if (playing != null) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(spacing.sm))
                    Text(stringResource(if (playing != null) R.string.settings_sound_stop else R.string.settings_sound_play))
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
            // What a full-screen reminder sounds like: the same tone, round and round until
            // somebody answers it. Half a minute of it here is more than anybody needs.
            Column {
                OutlinedButton(
                    onClick = { listen(PreviewMode.LOOPING) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.touch),
                ) {
                    Icon(if (playing == PreviewMode.LOOPING) Icons.Outlined.Stop else Icons.Outlined.Loop, contentDescription = null)
                    Spacer(Modifier.width(spacing.sm))
                    Text(stringResource(if (playing == PreviewMode.LOOPING) R.string.settings_sound_stop else R.string.settings_sound_loop))
                }
                Spacer(Modifier.height(spacing.xs))
                Text(
                    text = stringResource(R.string.settings_sound_loop_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingTitle(
                    title = stringResource(R.string.settings_sound_headphones),
                    info = stringResource(R.string.settings_sound_headphones_hint),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(spacing.md))
                Switch(
                    checked = toHeadphones,
                    onCheckedChange = { on ->
                        if (on) haptics.perform(HapticFeedbackType.ToggleOn)
                        onToHeadphones(on)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.surface,
                        checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
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
                // What those two numbers actually feel like. The real waits are minutes long;
                // this plays the round with them shortened, and says so underneath.
                Column {
                    OutlinedButton(
                        onClick = { listen(PreviewMode.ROUND) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.touch),
                    ) {
                        Icon(if (playing == PreviewMode.ROUND) Icons.Outlined.Stop else Icons.Outlined.Repeat, contentDescription = null)
                        Spacer(Modifier.width(spacing.sm))
                        Text(stringResource(if (playing == PreviewMode.ROUND) R.string.settings_sound_insistent_stop else R.string.settings_sound_insistent_play))
                    }
                    Spacer(Modifier.height(spacing.xs))
                    Text(
                        text = stringResource(R.string.settings_sound_insistent_hint, plays, gapMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The wait in the rehearsal: long enough to be a gap, short enough to sit through. */
private const val REHEARSAL_GAP_MS = 1_200L

/**
 * The alarm volume, where the sound is chosen — because "probar" without it is guesswork.
 *
 * It is the phone's own slider, not a copy: reminders play on the alarm stream and that is the
 * only thing that governs how loud they are. Read again whenever the screen comes back, since
 * the hardware keys move it too.
 */
@Composable
private fun AlarmVolume() {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    val max = remember(audio) { audio?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 0 }
    var level by remember { mutableIntStateOf(audio?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) level = audio?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (audio == null || max <= 0) return
    Column {
        SettingTitle(
            title = stringResource(R.string.settings_sound_volume),
            info = stringResource(R.string.settings_sound_volume_hint),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = level.toFloat(),
                onValueChange = { value ->
                    level = value.toInt()
                    // Refused while Do Not Disturb is on without policy access; the card next
                    // door is where that is said, and a slider that throws is worse than one
                    // that quietly does not move the phone.
                    runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, level, 0) }
                },
                valueRange = 0f..max.toFloat(),
                steps = (max - 1).coerceAtLeast(0),
                // Neutral, like the radius slider: amber on this screen would claim to mean
                // "what fires next", which is the one thing it is not.
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.onSurface,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(spacing()))
            Text(
                text = "$level/$max",
                style = MonoStyles.label,
                color = if (level == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun spacing() = Tokens.spacing.sm

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
