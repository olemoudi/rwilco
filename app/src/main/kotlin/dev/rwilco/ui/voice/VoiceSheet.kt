package dev.rwilco.ui.voice

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.rwilco.R
import dev.rwilco.model.Reminder
import dev.rwilco.model.Spoken
import dev.rwilco.ui.components.NoBounce
import dev.rwilco.ui.components.SheetBounds
import dev.rwilco.ui.editor.ReminderSentence
import dev.rwilco.ui.editor.sentenceParts
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.voice.VoiceFailure
import dev.rwilco.voice.VoiceInput
import dev.rwilco.voice.VoiceInputEvent
import dev.rwilco.voice.VoiceOutcome
import dev.rwilco.voice.VoiceState
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Saying a reminder instead of writing it (0.176.0).
 *
 * The sheet listens, shows the words as they come and — underneath, in the colours the editor's
 * own sentence uses — what they are turning into, so "comprar pan cuando llegue a casa" is seen to
 * become "al llegar a Casa" before anything is written. When the sentence is in it is written at
 * once and said back once more with its undo, then the sheet goes. Anything it cannot write whole
 * goes to the editor with the words already in it, and a line saying why (`Spoken.kt`).
 *
 * The microphone is the phone's own recognizer, on the phone wherever it can be ([VoiceInput]).
 * Nothing loops: the disc grows with the voice it hears and stands still in silence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(
    /** Where the sheet is; held by Home's ViewModel, so a rotation keeps it. */
    state: VoiceState,
    /** What the words heard so far would be written as, or null while they say no "when". */
    reading: Spoken?,
    today: LocalDate,
    defaultTime: LocalTime,
    zone: ZoneId,
    onEvent: (VoiceInputEvent) -> Unit,
    /** The sheet has started to leave: anything it was working on stops now. */
    onStop: () -> Unit,
    onUndo: (Reminder) -> Unit,
    onEdit: (Reminder) -> Unit,
    /** "Guardar" on Gemini's guess (0.178.0). */
    onConfirm: () -> Unit,
    /**
     * To the editor with these words; [VoiceOutcome.Why] says why, null when somebody asked to
     * type; the last is the whole reading to open on, when there is one.
     */
    onToEditor: (String, VoiceOutcome.Why?, String?) -> Unit,
    onClosed: () -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val spacing = Tokens.spacing
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    val listener = remember { VoiceInput.factory(context) }
    DisposableEffect(listener) { onDispose { listener.cancel() } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }

    /** Stops whatever was going on, slides it out, then answers. Once. */
    fun leave(then: () -> Unit) {
        if (leaving) return
        leaving = true
        listener.cancel()
        onStop()
        scope.launch {
            try {
                sheetState.hide()
            } finally {
                then()
            }
        }
    }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        onEvent(VoiceInputEvent.Permission(ok))
    }
    // Asked once per sheet: a rotation while the system's dialog is up must not ask again.
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state is VoiceState.Asking) {
        if (state is VoiceState.Asking && !asked) {
            asked = true
            askPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // A fresh round of listening each time the sheet comes to it: on opening, on "otra vez", and
    // after a rotation, which took the old ear with the old screen.
    val listening = state is VoiceState.Listening
    LaunchedEffect(listening) {
        if (listening) listener.start(locale) { event -> onEvent(VoiceInputEvent.Heard(event)) }
    }
    // Written, said back, and gone — unless a hand reaches for it first.
    val created = (state as? VoiceState.Created)?.reminder
    LaunchedEffect(created) {
        if (created == null) return@LaunchedEffect
        haptics.perform(HapticFeedbackType.Confirm)
        delay(CREATED_DWELL_MS)
        leave(onClosed)
    }
    // Not written: to the editor, with the words.
    val unwritten = state as? VoiceState.ToEditor
    LaunchedEffect(unwritten) {
        if (unwritten != null) leave { onToEditor(unwritten.text, unwritten.why, unwritten.form) }
    }

    NoBounce {
        ModalBottomSheet(
            onDismissRequest = { leave(onClosed) },
            sheetState = sheetState,
            containerColor = scheme.surfaceContainer,
            contentColor = scheme.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            SheetBounds(
                modifier = Modifier
                    .padding(horizontal = spacing.screen)
                    .navigationBarsPadding()
                    .padding(bottom = spacing.md),
            ) {
                val current = state
                Text(
                    text = stringResource(statusOf(current)),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(spacing.lg))
                Disc(current)
                Spacer(Modifier.height(spacing.lg))
                val words = when (current) {
                    is VoiceState.Listening -> current.partial
                    is VoiceState.Thinking -> current.text
                    is VoiceState.Created -> current.reminder.text
                    is VoiceState.Confirm -> current.reminder.text
                    is VoiceState.Failed -> current.partial
                    is VoiceState.ToEditor -> current.text
                    VoiceState.Asking -> ""
                }
                val fast = Tokens.motion.fast
                AnimatedContent(
                    targetState = words.isBlank(),
                    transitionSpec = { fadeIn(tween(fast)) togetherWith fadeOut(tween(fast)) },
                    label = "voice-words",
                ) { blank ->
                    if (blank) {
                        Text(stringResource(R.string.voice_hint), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                    } else {
                        Text(words, style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface)
                    }
                }
                // What will be written — the words without their "when", and the "when" in the
                // colours the editor's own sentence over "Guardar" uses.
                val shown: Spoken? = when (current) {
                    is VoiceState.Created -> current.reminder.let { Spoken(it.text, it.rules, it.ruleMatch, it.recurrence, it.deadline) }
                    is VoiceState.Confirm -> current.reminder.let { Spoken(it.text, it.rules, it.ruleMatch, it.recurrence, it.deadline) }
                    is VoiceState.Listening, is VoiceState.Thinking -> reading
                    else -> null
                }
                if (shown != null) {
                    Spacer(Modifier.height(spacing.sm))
                    ReminderSentence(
                        parts = sentenceParts(shown.text, shown.rules, shown.match, shown.recurrence, shown.deadline),
                        today = today,
                        defaultTime = defaultTime,
                        zone = zone,
                    )
                }
                Spacer(Modifier.height(spacing.xl))
                Buttons(
                    state = current,
                    onCancel = { leave(onClosed) },
                    onStop = { listener.stop() },
                    onUndo = { reminder -> leave { onUndo(reminder) } },
                    onEdit = { reminder -> leave { onEdit(reminder) } },
                    onType = { leave { onToEditor(words, null, null) } },
                    onConfirm = onConfirm,
                    onEditGuess = { form -> leave { onToEditor(words, null, form) } },
                    onRetry = { onEvent(VoiceInputEvent.Retry) },
                    onSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        leave(onClosed)
                    },
                )
            }
        }
    }
}

/** The mic, grown by how loud the voice is; a tick once it is written, struck through when it cannot listen. */
@Composable
private fun Disc(state: VoiceState) {
    val scheme = MaterialTheme.colorScheme
    val level = (state as? VoiceState.Listening)?.level ?: 0f
    val scale by animateFloatAsState(1f + DISC_GROWTH * level, tween(Tokens.motion.fast), label = "voice-level")
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(Tokens.sizes.tile)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(if (state is VoiceState.Listening) scheme.onSurface else scheme.surfaceContainerHighest, CircleShape),
        ) {
            Icon(
                imageVector = when (state) {
                    is VoiceState.Created -> Icons.Outlined.Check
                    is VoiceState.Failed -> Icons.Outlined.MicOff
                    else -> Icons.Outlined.Mic
                },
                contentDescription = stringResource(
                    when (state) {
                        is VoiceState.Listening -> R.string.voice_mic_open
                        is VoiceState.Created -> R.string.voice_created
                        else -> R.string.voice_mic_closed
                    },
                ),
                tint = if (state is VoiceState.Listening) scheme.surface else scheme.onSurface,
                modifier = Modifier.size(Tokens.sizes.glyphLarge),
            )
        }
    }
}

@Composable
private fun Buttons(
    state: VoiceState,
    onCancel: () -> Unit,
    onStop: () -> Unit,
    onUndo: (Reminder) -> Unit,
    onEdit: (Reminder) -> Unit,
    onType: () -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onConfirm: () -> Unit,
    onEditGuess: (String) -> Unit,
) {
    val spacing = Tokens.spacing
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
        when (state) {
            VoiceState.Asking, is VoiceState.Thinking, is VoiceState.ToEditor -> {
                Quiet(R.string.sheet_cancel, onCancel)
            }
            is VoiceState.Listening -> {
                Quiet(R.string.sheet_cancel, onCancel)
                Primary(R.string.voice_stop, onStop, Modifier.weight(1f))
            }
            is VoiceState.Created -> {
                Quiet(R.string.common_undo) { onUndo(state.reminder) }
                Primary(R.string.voice_edit, { onEdit(state.reminder) }, Modifier.weight(1f))
            }
            // A guess: nothing is written until "Guardar".
            is VoiceState.Confirm -> {
                Quiet(R.string.voice_edit) { onEditGuess(state.form) }
                Primary(R.string.common_save, onConfirm, Modifier.weight(1f))
            }
            is VoiceState.Failed -> {
                Quiet(R.string.voice_type, onType)
                if (state.why == VoiceFailure.NO_PERMISSION) {
                    Primary(R.string.voice_open_settings, onSettings, Modifier.weight(1f))
                } else {
                    Primary(R.string.voice_retry, onRetry, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Quiet(label: Int, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.heightIn(min = Tokens.sizes.control),
    ) {
        Text(stringResource(label))
    }
}

@Composable
private fun Primary(label: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = Tokens.haptics
    Button(
        onClick = {
            haptics.perform(HapticFeedbackType.ContextClick)
            onClick()
        },
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.heightIn(min = Tokens.sizes.primary),
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
    }
}

private fun statusOf(state: VoiceState): Int = when (state) {
    VoiceState.Asking -> R.string.voice_asking
    is VoiceState.Listening -> R.string.voice_listening
    is VoiceState.Thinking -> if (state.asking) R.string.voice_asking_gemini else R.string.voice_thinking
    is VoiceState.ToEditor -> R.string.voice_thinking
    is VoiceState.Confirm -> R.string.voice_confirm
    is VoiceState.Created -> R.string.voice_created
    is VoiceState.Failed -> when (state.why) {
        VoiceFailure.NO_PERMISSION -> R.string.voice_no_permission
        VoiceFailure.NOT_HEARD -> R.string.voice_not_heard
        VoiceFailure.BROKEN -> R.string.voice_broken
    }
}

/** How long a written reminder is said back before the sheet goes by itself. */
private const val CREATED_DWELL_MS = 4_000L

/** How much the disc grows at the loudest. */
private const val DISC_GROWTH = 0.28f
