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
import androidx.compose.ui.platform.LocalContext
import dev.rwilco.ui.components.LocalSnackbar
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
    /** The tone for the reminders that keep asking, or null when there is no distinction. */
    insistentSound: AlertSound?,
    plays: Int,
    gapMinutes: Int,
    insistentInUse: Boolean,
    toHeadphones: Boolean,
    onSound: (AlertSound) -> Unit,
    onInsistentSound: (AlertSound?) -> Unit,
    onPlays: (Int) -> Unit,
    onGap: (Int) -> Unit,
    onToHeadphones: (Boolean) -> Unit,
) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val preview = remember(context) { SoundPreview(context) }
    DisposableEffect(preview) { onDispose { preview.stop() } }
    // Which preview is playing, if any: every button here is its own "parar" while it runs.
    var playing by remember { mutableStateOf<PreviewMode?>(null) }
    // And which of the two tones it is playing, because the same continuous preview is offered
    // for both and only the button that started one may say "parar".
    var playingInsistent by remember { mutableStateOf(false) }
    // The tone the reminders that keep asking actually use, which is the one above until
    // somebody draws the distinction (AppSettings.soundFor).
    val insistentTone = insistentSound ?: sound
    val listen: (PreviewMode, Boolean) -> Unit = { mode, insistent ->
        if (playing != null) {
            preview.stop()
        } else {
            val tone = if (insistent) insistentTone else sound
            playingInsistent = insistent
            when (mode) {
                PreviewMode.ONCE -> preview.play(tone, toHeadphones) { playing = it }
                PreviewMode.LOOPING -> preview.playLooping(tone, toHeadphones) { playing = it }
                PreviewMode.ROUND -> preview.playRound(tone, plays, REHEARSAL_GAP_MS, toHeadphones) { playing = it }
            }
        }
    }

    // Any audio file the phone can open. mp3 and wav are what people have; the rest is a bonus.
    // A file that cannot be copied (no space, a provider that will not read) is said (0.68.0):
    // the chip used to simply not change, with no word about why.
    val snackbar = LocalSnackbar.current
    val copyFailed = stringResource(R.string.settings_sound_copy_failed)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onSound(context.rememberSound(uri) ?: run { snackbar.show(copyFailed); return@rememberLauncherForActivityResult })
    }
    val pickInsistent = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onInsistentSound(context.rememberSound(uri) ?: run { snackbar.show(copyFailed); return@rememberLauncherForActivityResult })
    }

    RwilcoCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
            Column {
                SettingTitle(
                    title = stringResource(R.string.settings_sound_title),
                    info = stringResource(R.string.settings_sound_hint),
                )
                Spacer(Modifier.height(spacing.sm))
                SoundChips(sound = sound, onPick = { onSound(it); preview.play(it, toHeadphones) { p -> playing = p } })
            }
            AlarmVolume()
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { listen(PreviewMode.ONCE, false) },
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
                val looping = playing == PreviewMode.LOOPING && !playingInsistent
                OutlinedButton(
                    onClick = { listen(PreviewMode.LOOPING, false) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.touch),
                ) {
                    Icon(if (looping) Icons.Outlined.Stop else Icons.Outlined.Loop, contentDescription = null)
                    Spacer(Modifier.width(spacing.sm))
                    Text(stringResource(if (looping) R.string.settings_sound_stop else R.string.settings_sound_loop))
                }
                Spacer(Modifier.height(spacing.xs))
                Text(
                    text = stringResource(R.string.settings_sound_loop_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingSwitchRow(
                title = stringResource(R.string.settings_sound_headphones),
                info = stringResource(R.string.settings_sound_headphones_hint),
                checked = toHeadphones,
                onCheckedChange = onToHeadphones,
            )
            // A tone you are going to hear five times is a different choice from one you hear
            // once. Off, there is no distinction and both use the one above.
            //
            // **Always here**, unlike the two numbers below. It used to be inside their fold,
            // which made it a setting nobody could find: with nothing yet asking for the
            // insistent sound the whole row was missing, so somebody who went to Settings to
            // choose that tone found no such choice — and a preference that only appears once
            // you have already written the reminder is, to anybody looking for it, a
            // preference the app does not offer. What a thing sounds like is answered here,
            // like every other tone on this card, whether or not anything is asking yet.
            SettingSwitchRow(
                title = stringResource(R.string.settings_sound_two_tones),
                info = stringResource(R.string.settings_sound_two_tones_hint),
                checked = insistentSound != null,
                // Turning it on starts from what is already chosen, so the switch by itself
                // never changes what anything sounds like.
                onCheckedChange = { on -> onInsistentSound(if (on) sound else null) },
            )
            if (insistentSound != null) {
                Column {
                    SoundChips(
                        sound = insistentSound,
                        onPick = { onInsistentSound(it); preview.play(it, toHeadphones) { p -> playing = p } },
                    )
                    Spacer(Modifier.height(spacing.sm))
                    OutlinedButton(
                        onClick = { pickInsistent.launch(AUDIO_TYPES) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.touch),
                    ) {
                        Icon(Icons.Outlined.LibraryMusic, contentDescription = null)
                        Spacer(Modifier.width(spacing.sm))
                        Text(stringResource(R.string.settings_sound_custom))
                    }
                    // And heard in continuo, like the one above. This tone is not only what the
                    // round repeats: a full-screen alert carrying a reminder that keeps asking
                    // rings THIS one, round and round (AppSettings.soundFor), so it is the half
                    // of the choice that most needs hearing that way. A chip plays itself once
                    // when it is tapped, which is why only this button is here, and there is no
                    // second copy of the hint above: it would say the same thing twice.
                    Spacer(Modifier.height(spacing.sm))
                    val loopingInsistent = playing == PreviewMode.LOOPING && playingInsistent
                    OutlinedButton(
                        onClick = { listen(PreviewMode.LOOPING, true) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.touch),
                    ) {
                        Icon(if (loopingInsistent) Icons.Outlined.Stop else Icons.Outlined.Loop, contentDescription = null)
                        Spacer(Modifier.width(spacing.sm))
                        Text(stringResource(if (loopingInsistent) R.string.settings_sound_stop else R.string.settings_sound_loop))
                    }
                }
            }
            // The two numbers are the other half of it, and they stay behind the fold: they
            // describe a round — how many times it comes back, and how far apart — and that
            // only means anything to a reminder that has asked for one.
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
                        onClick = { listen(PreviewMode.ROUND, true) },
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

/**
 * The four chimes, the phone's own, and whatever file has been chosen: one row, used for each of
 * the two tones. The custom chip only appears once there is one; choosing a new file is the
 * button underneath, not a chip that would open a picker.
 */
@Composable
private fun SoundChips(sound: AlertSound, onPick: (AlertSound) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        for (chime in Chime.entries) {
            val choice = AlertSound.Bundled(chime)
            PresetChip(label = stringResource(chime.labelRes), selected = sound == choice, onClick = { onPick(choice) })
        }
        PresetChip(
            label = stringResource(R.string.settings_sound_system),
            selected = sound == AlertSound.System,
            onClick = { onPick(AlertSound.System) },
        )
        (sound as? AlertSound.Custom)?.let { custom ->
            PresetChip(label = custom.label, selected = true, onClick = { onPick(custom) })
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
