package dev.rwilco.ui.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.StrikethroughS
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.rwilco.R
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Spoken
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerFamily
import dev.rwilco.model.family
import dev.rwilco.model.hasDeadline
import dev.rwilco.model.isAnchored
import dev.rwilco.model.partsBetween
import dev.rwilco.ui.components.NoBounce
import dev.rwilco.ui.components.SheetBounds
import dev.rwilco.ui.editor.ReminderSentence
import dev.rwilco.ui.editor.sentenceParts
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.countdownText
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.format.recurrenceLabel
import dev.rwilco.ui.format.rememberWords
import dev.rwilco.ui.format.triggerPhrase
import dev.rwilco.ui.home.DeadlineRow
import dev.rwilco.ui.home.RecurrenceRow
import dev.rwilco.ui.home.TriggerRow
import dev.rwilco.ui.home.TriggerRowUi
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.color
import dev.rwilco.voice.HeardMark
import dev.rwilco.voice.Refining
import dev.rwilco.voice.VoiceAlternative
import dev.rwilco.voice.VoiceCheck
import dev.rwilco.voice.VoiceFailure
import dev.rwilco.voice.VoiceInput
import dev.rwilco.voice.VoiceInputEvent
import dev.rwilco.voice.VoiceOutcome
import dev.rwilco.voice.VoicePreview
import dev.rwilco.voice.droppedWords
import dev.rwilco.voice.VoiceState
import dev.rwilco.voice.doubtWords
import dev.rwilco.voice.marks
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Saying a reminder instead of writing it (0.176.0).
 *
 * The sheet listens, shows the words as they come and — underneath, in the colours the editor's
 * own sentence uses — what they are turning into, so "comprar pan cuando llegue a casa" is seen to
 * become "al llegar a Casa" before anything is written. When the sentence is in it is written at
 * once, and the sheet turns into the check of it (0.182.0): the reminder as Home will show it,
 * when it first rings, the sentence as heard with each "when" in its family's colour, the words
 * nothing read underlined, and the other half of an open hour one tap away. A clean reading goes by
 * itself after a few seconds, the countdown drawn on "Listo" and stopped by a touch; one with a
 * doubt or an alternative waits for "Listo". Anything it cannot write whole goes to the editor with
 * the words already in it, and a line saying why (`Spoken.kt`).
 *
 * It listens until "Terminado" and never ends by itself (0.186.0; the owner: room to say it calmly,
 * with pauses): a pause only closes a stretch. A long silence, or the app left behind, pauses the
 * microphone with the words kept, for "Seguir" or "Terminado".
 *
 * The microphone is the phone's own recognizer, on the phone wherever it can be ([VoiceInput]).
 * Nothing loops: the disc grows with the voice it hears and stands still in silence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSheet(
    /** Where the sheet is; held by Home's ViewModel, so a rotation keeps it. */
    state: VoiceState,
    /** What the words heard so far come to: what of them is a correction, and what they would be written as. */
    preview: VoicePreview?,
    /** The person's own words, for the recognizer to lean towards (0.189.0). */
    words: List<String>,
    today: LocalDate,
    defaultTime: LocalTime,
    zone: ZoneId,
    onEvent: (VoiceInputEvent) -> Unit,
    /** The sheet has started to leave: anything it was working on stops now. */
    onStop: () -> Unit,
    onUndo: (Reminder) -> Unit,
    onEdit: (Reminder) -> Unit,
    /** Another reading of the sentence, chosen in place of the written one. */
    onAlternative: (VoiceAlternative) -> Unit,
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

    /**
     * Stops whatever was going on, slides it out, then answers. Once. The answer is given whatever
     * becomes of the animation — a finger catching the sheet on its way down has still pressed
     * the button — except when the sheet itself is being taken away (a rotation): then nothing is
     * answered here, and the state Home's ViewModel still holds answers again on the new screen,
     * where navigating can happen.
     */
    fun leave(then: () -> Unit) {
        if (leaving) return
        leaving = true
        listener.cancel()
        onStop()
        scope.launch {
            try {
                sheetState.hide()
            } catch (cancelled: CancellationException) {
                if (!isActive) throw cancelled
            }
            then()
        }
    }

    // Once the sheet is on its way out nothing it was doing may still move it: a "Terminado" whose
    // wait runs out under "Cancelar" would write the reminder the person has just let go of.
    fun send(event: VoiceInputEvent) {
        if (!leaving) onEvent(event)
    }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        send(VoiceInputEvent.Permission(ok))
    }
    // Asked once per sheet: a rotation while the system's dialog is up must not ask again.
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state is VoiceState.Asking) {
        if (state is VoiceState.Asking && !asked) {
            asked = true
            askPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // A fresh round of listening each time the sheet comes to it: on opening, on "otra vez", on
    // "seguir" after a pause, and after a rotation, which took the old ear with the old screen.
    // It listens across pauses until "Terminado" (0.186.0), and the ear closes the moment the
    // sheet is anywhere else — the sentence in, a pause, a failure.
    val listening = state as? VoiceState.Listening
    val earOpen = listening != null && !listening.paused
    LaunchedEffect(earOpen) {
        if (!earOpen) return@LaunchedEffect
        listener.start(locale, words) { event -> send(VoiceInputEvent.Heard(event)) }
        try {
            awaitCancellation()
        } finally {
            listener.cancel()
        }
    }
    // "Terminado": the ear gives its last stretch, and one that never answers does not hold the
    // sheet — a moment after its last word, what has been heard is the sentence.
    val finishing = listening?.finishing == true
    val transcript = listening?.transcript
    LaunchedEffect(finishing) {
        if (finishing) listener.stop()
    }
    LaunchedEffect(finishing, transcript) {
        if (!finishing) return@LaunchedEffect
        delay(FINISH_GRACE_MS)
        send(VoiceInputEvent.Finish)
    }
    // Never ended by a silence, but never a microphone left open either: a long one with no new
    // word closes it, the words kept, and so does leaving the app (where Android would silence it).
    LaunchedEffect(earOpen && !finishing, transcript) {
        if (!earOpen || finishing) return@LaunchedEffect
        delay(QUIET_MS)
        send(VoiceInputEvent.Pause)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentSend by rememberUpdatedState(::send)
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            // A rotation stops the screen too, and takes the ear with it; the new one listens on.
            // The ear is closed here and now: a stopped app's composition does not run again until
            // it is back, and the microphone must not wait for that.
            if (event == Lifecycle.Event.ON_STOP && context.findActivity()?.isChangingConfigurations != true) {
                currentSend(VoiceInputEvent.Pause)
                listener.cancel()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // A sentence said calmly outlasts the screen's timeout, and nobody touches it while talking.
    val view = LocalView.current
    DisposableEffect(view, earOpen) {
        view.keepScreenOn = earOpen
        onDispose { view.keepScreenOn = false }
    }
    // Written, shown to be checked, and gone by itself when there is nothing to look at twice —
    // the countdown drawn on "Listo" — unless a hand reaches for the sheet first. Keyed by the
    // reminder's id: an alternative chosen is the same reminder, and a touch is still a touch.
    // Saved across a rotation, as the touch that stopped it is a decision, and the haptic once said is said.
    val created = state as? VoiceState.Created
    val createdId = created?.reminder?.id
    var touched by rememberSaveable(createdId) { mutableStateOf(false) }
    var confirmed by rememberSaveable(createdId) { mutableStateOf(false) }
    val closing = remember(createdId) { Animatable(0f) }
    LaunchedEffect(createdId) {
        if (createdId != null && !confirmed) {
            confirmed = true
            haptics.perform(HapticFeedbackType.Confirm)
        }
    }
    // The person's own "time to take action", and never by itself for somebody exploring the
    // screen by touch: a screen reader's swipes are not presses, and the sheet would go mid-sentence.
    val dwell = LocalAccessibilityManager.current
        ?.calculateRecommendedTimeoutMillis(CREATED_DWELL_MS, containsIcons = true, containsText = true, containsControls = true)
        ?: CREATED_DWELL_MS
    val closesItself = created != null && created.check.clear && !touched && dwell < Int.MAX_VALUE
    LaunchedEffect(createdId, closesItself) {
        closing.snapTo(0f)
        if (!closesItself) return@LaunchedEffect
        closing.animateTo(1f, tween(dwell.toInt(), easing = LinearEasing))
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
                    // Any touch on a written reminder's sheet — gutters included — is somebody reading it: it stays.
                    .pointerInput(createdId) {
                        if (createdId == null) return@pointerInput
                        awaitPointerEventScope {
                            while (true) {
                                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) touched = true
                            }
                        }
                    }
                    .padding(horizontal = spacing.screen)
                    .navigationBarsPadding()
                    .padding(bottom = spacing.md),
            ) {
                val current = state
                val checking = current is VoiceState.Created || current is VoiceState.Confirm
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (current is VoiceState.Created) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(Tokens.sizes.glyphMedium))
                        Spacer(Modifier.width(spacing.sm))
                    }
                    Text(
                        text = stringResource(statusOf(current)),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (current is VoiceState.Created) scheme.onSurface else scheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                // Gemini is being asked — before anything is written (the grammar read nothing),
                // or beside a reminder already written (it had doubts): either way, it shows, and
                // it ends when Gemini answers or the patience runs out (0.183.0).
                val asking = (current as? VoiceState.Thinking)?.asking == true || (current as? VoiceState.Created)?.check?.refining == Refining.ASKING
                AnimatedVisibility(visible = asking, enter = fadeIn(tween(Tokens.motion.fast)), exit = fadeOut(tween(Tokens.motion.fast))) {
                    LinearProgressIndicator(
                        color = scheme.onSurfaceVariant,
                        trackColor = scheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
                    )
                }
                val words = when (current) {
                    is VoiceState.Listening -> current.transcript
                    is VoiceState.Thinking -> current.text
                    is VoiceState.Created -> current.reminder.text
                    is VoiceState.Confirm -> current.reminder.text
                    is VoiceState.Failed -> current.partial
                    is VoiceState.ToEditor -> current.text
                    VoiceState.Asking -> ""
                }
                if (checking) {
                    // The check of what was written (or of Gemini's guess, before it is): the
                    // part that may grow past the screen scrolls, and the buttons stay put.
                    val (reminder, check) = when (current) {
                        is VoiceState.Created -> current.reminder to current.check
                        is VoiceState.Confirm -> current.reminder to current.check
                        else -> error("not a check")
                    }
                    Spacer(Modifier.height(spacing.md))
                    Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        CheckView(
                            reminder = reminder,
                            check = check,
                            today = today,
                            defaultTime = defaultTime,
                            zone = zone,
                            onAlternative = if (current is VoiceState.Created) onAlternative else null,
                        )
                    }
                } else {
                    Spacer(Modifier.height(spacing.lg))
                    Disc(current)
                    Spacer(Modifier.height(spacing.lg))
                    val fast = Tokens.motion.fast
                    AnimatedContent(
                        targetState = words.isBlank(),
                        transitionSpec = { fadeIn(tween(fast)) togetherWith fadeOut(tween(fast)) },
                        label = "voice-words",
                    ) { blank ->
                        if (blank) {
                            Text(stringResource(R.string.voice_hint), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                        } else {
                            // A correction is seen to be taken as one while it is said: what it
                            // corrects is struck through as soon as the words after it line up.
                            val struck = preview?.takeIf { it.heard == words }?.dropped.orEmpty()
                            Text(struckThrough(words, struck, scheme.outline), style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface)
                        }
                    }
                    // What it is turning into while it is said — the words without their "when",
                    // and the "when" in the colours the editor's own sentence over "Guardar" uses.
                    val shown: Spoken? = if (current is VoiceState.Listening || current is VoiceState.Thinking) preview?.reading else null
                    if (shown != null) {
                        Spacer(Modifier.height(spacing.sm))
                        ReminderSentence(
                            parts = sentenceParts(shown.text, shown.rules, shown.match, shown.recurrence, shown.deadline),
                            today = today,
                            defaultTime = defaultTime,
                            zone = zone,
                        )
                    }
                    // Paused: what to do now, since the microphone closed without anybody pressing anything.
                    AnimatedVisibility(visible = listening?.paused == true, enter = fadeIn(tween(fast)), exit = fadeOut(tween(fast))) {
                        Text(
                            stringResource(R.string.voice_paused_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = spacing.md),
                        )
                    }
                }
                Spacer(Modifier.height(spacing.xl))
                Buttons(
                    state = current,
                    closing = { closing.value },
                    closesItself = closesItself,
                    onCancel = { leave(onClosed) },
                    onDone = { leave(onClosed) },
                    onFinish = { send(VoiceInputEvent.Finish) },
                    onResume = { send(VoiceInputEvent.Resume) },
                    onUndo = { reminder -> leave { onUndo(reminder) } },
                    onEdit = { reminder -> leave { onEdit(reminder) } },
                    onType = { leave { onToEditor(words, null, null) } },
                    onConfirm = onConfirm,
                    onEditGuess = { form -> leave { onToEditor(words, null, form) } },
                    onRetry = { send(VoiceInputEvent.Retry) },
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

/**
 * The mic, grown by how loud the voice is; struck through when it cannot listen. A written
 * reminder's sheet shows the check of it instead (0.182.0), so the disc is never drawn for one.
 */
@Composable
private fun Disc(state: VoiceState) {
    val scheme = MaterialTheme.colorScheme
    val open = state is VoiceState.Listening && !state.paused
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
                .background(if (open) scheme.onSurface else scheme.surfaceContainerHighest, CircleShape),
        ) {
            Icon(
                imageVector = when (state) {
                    is VoiceState.Failed -> Icons.Outlined.MicOff
                    else -> Icons.Outlined.Mic
                },
                contentDescription = stringResource(if (open) R.string.voice_mic_open else R.string.voice_mic_closed),
                tint = if (open) scheme.surface else scheme.onSurface,
                modifier = Modifier.size(Tokens.sizes.glyphLarge),
            )
        }
    }
}

@Composable
private fun Buttons(
    state: VoiceState,
    /** How far the written reminder's sheet is through going by itself, 0..1. */
    closing: () -> Float,
    closesItself: Boolean,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onFinish: () -> Unit,
    onResume: () -> Unit,
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
            // While Gemini is asked, nobody has to wait for it: the words can go to the editor now.
            is VoiceState.Thinking -> {
                Quiet(R.string.sheet_cancel, onCancel)
                if (state.asking) Quiet(R.string.voice_type, onType)
            }
            VoiceState.Asking, is VoiceState.ToEditor -> {
                Quiet(R.string.sheet_cancel, onCancel)
            }
            // Only "Terminado" ends it (0.186.0); after a pause, "Seguir" listens on beside it.
            is VoiceState.Listening -> {
                Quiet(R.string.sheet_cancel, onCancel)
                if (state.paused) Quiet(R.string.voice_resume, onResume)
                Primary(R.string.voice_stop, onFinish, Modifier.weight(1f))
            }
            // "Listo" is the answer the sheet expects — it is what was said — so it is the
            // primary, in the thumb's reach; taking it back and changing it sit beside it.
            is VoiceState.Created -> {
                Quiet(R.string.common_undo) { onUndo(state.reminder) }
                Quiet(R.string.voice_edit) { onEdit(state.reminder) }
                Primary(R.string.voice_done, onDone, Modifier.weight(1f), progress = closing.takeIf { closesItself })
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

/**
 * The sheet's one primary action. With [progress], it is also the clock of a sheet going by itself:
 * a bar along its foot that runs out as the moment comes, so the going is seen before it happens.
 */
@Composable
private fun Primary(label: Int, onClick: () -> Unit, modifier: Modifier = Modifier, progress: (() -> Float)? = null) {
    val haptics = Tokens.haptics
    val shape = MaterialTheme.shapes.medium
    val bar = MaterialTheme.colorScheme.surface.copy(alpha = CLOSING_BAR_ALPHA)
    val track = Tokens.strokes.track
    val closingWords = stringResource(R.string.voice_closes_itself)
    Button(
        onClick = {
            haptics.perform(HapticFeedbackType.ContextClick)
            onClick()
        },
        shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
            .heightIn(min = Tokens.sizes.primary)
            .then(
                if (progress == null) {
                    Modifier
                } else {
                    Modifier
                        .semantics { stateDescription = closingWords }
                        .clip(shape)
                        .drawWithContent {
                            drawContent()
                            val height = track.toPx()
                            drawRect(bar, topLeft = Offset(0f, size.height - height), size = Size(size.width * (1f - progress()), height))
                        }
                },
            ),
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * The written reminder, checked against what was said (0.182.0).
 *
 * First the reminder as Home will show it — its words, and each rule as its row on the card, with
 * the word between rules that says how they are read together — and when it first rings, as a
 * day, an hour and a distance. Then the sentence as heard, each "when" in its family's colour and
 * the words that only said how it was said dimmed, so what was read from where can be seen at a
 * glance; the words nothing read are underlined and named. Last, the other reading of an open hour,
 * one tap from taking this one's place. [onAlternative] is null for Gemini's guess, which has none.
 */
@Composable
private fun CheckView(
    reminder: Reminder,
    check: VoiceCheck,
    today: LocalDate,
    defaultTime: LocalTime,
    zone: ZoneId,
    onAlternative: ((VoiceAlternative) -> Unit)?,
) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(reminder.text, style = MaterialTheme.typography.titleLarge, color = scheme.onSurface)
            reminder.rules.forEachIndexed { index, rule ->
                if (index > 0) {
                    Text(stringResource(reminder.ruleMatch.joinRes), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
                TriggerRow(TriggerRowUi(rule.trigger, rule.conditions, rule.trigger.family, nextAt = null, window = null), today, defaultTime)
            }
            if (reminder.recurrence.isAnchored) RecurrenceRow(reminder.recurrence, today, zone)
            if (reminder.hasDeadline) reminder.deadline?.let { DeadlineRow(it, reminder.expiresAt, today, zone) }
            // The row already says a lone moment's day and hour; then only the distance is news.
            val lone = (reminder.rules.singleOrNull()?.trigger as? Trigger.AtDateTime)?.at
            check.firesAt?.let { FiresLine(it, today, zone, distanceOnly = lone != null && lone == it.atZone(zone).toLocalDateTime()) }
        }
        // The marks and the doubts are about the grammar's reading: while Gemini's is the one
        // shown, the sentence is shown as heard and nothing in it is claimed to be what it says.
        if (check.heard.isNotBlank()) HeardLine(check, marked = check.readsAsSaid)
        val dropped = if (check.readsAsSaid) check.droppedWords() else emptyList()
        if (dropped.isNotEmpty()) DroppedLine(dropped)
        val doubts = if (check.readsAsSaid) check.doubtWords() else emptyList()
        if (doubts.isNotEmpty()) DoubtLine(doubts)
        if (onAlternative != null) {
            // Gemini's reading, or the grammar's once Gemini's took its place: a whole reading,
            // words and all, so it is shown as a sentence and not as a chip.
            for (offer in check.alternatives.filter { it.kind != VoiceAlternative.Kind.OTHER_HALF }) ReadingOffer(offer, today, defaultTime, zone, onAlternative)
            // The other half of an open hour belongs to the grammar's reading: not offered while Gemini's is the one shown.
            val halves = check.alternatives.filter { it.kind == VoiceAlternative.Kind.OTHER_HALF }
                .takeIf { check.alternatives.none { it.kind == VoiceAlternative.Kind.ORIGINAL } }.orEmpty()
            if (halves.isNotEmpty()) Alternatives(halves, today, defaultTime, onAlternative)
        }
    }
}

/** A whole other reading of the sentence — Gemini's, or the grammar's own — and the one tap that makes it the reminder. */
@Composable
private fun ReadingOffer(offer: VoiceAlternative, today: LocalDate, defaultTime: LocalTime, zone: ZoneId, onAlternative: (VoiceAlternative) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val spacing = Tokens.spacing
    val haptics = Tokens.haptics
    val reminder = offer.reminder
    Surface(shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.padding(spacing.md)) {
            Text(
                stringResource(if (offer.kind == VoiceAlternative.Kind.GEMINI) R.string.voice_gemini_offer else R.string.voice_original_offer),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            ReminderSentence(
                parts = sentenceParts(reminder.text, reminder.rules, reminder.ruleMatch, reminder.recurrence, reminder.deadline),
                today = today,
                defaultTime = defaultTime,
                zone = zone,
            )
            val description = stringResource(if (offer.kind == VoiceAlternative.Kind.GEMINI) R.string.voice_use_gemini else R.string.voice_use_original)
            SuggestionChip(
                onClick = {
                    haptics.perform(HapticFeedbackType.ContextClick)
                    onAlternative(offer)
                },
                label = { Text(stringResource(R.string.voice_use_this), style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { contentDescription = description }) },
                icon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(SuggestionChipDefaults.IconSize)) },
                colors = SuggestionChipDefaults.suggestionChipColors(iconContentColor = scheme.onSurfaceVariant, labelColor = scheme.onSurface),
                modifier = Modifier.testTag(VOICE_OFFER_TAG),
            )
        }
    }
}

/**
 * "Sonará mañana a las 17:00 · en 26 h": when it first rings, the day and hour in mono as
 * everywhere — or, [distanceOnly], "Sonará en 26 h" under a row that already said the rest.
 */
@Composable
private fun FiresLine(at: Instant, today: LocalDate, zone: ZoneId, distanceOnly: Boolean) {
    val words = rememberWords()
    val local = at.atZone(zone)
    val hour = TimeText.time(local.toLocalTime(), words.is24h, words.locale)
    val parts = partsBetween(Instant.now(), at)
    // Under an hour a distance is in minutes alone: the seconds tick on Home, not in a line read
    // once. A moment gone by (a sheet left open) says so in Home's own words.
    val distance = if (parts.underAnHour && !parts.overdue) {
        stringResource(R.string.countdown_in, stringResource(R.string.countdown_minutes, parts.minutes.coerceAtLeast(1)))
    } else {
        countdownText(parts)
    }
    val line = if (distanceOnly) {
        stringResource(R.string.voice_fires_in, distance)
    } else {
        stringResource(R.string.voice_fires_at, dayWord(words, local.toLocalDate(), today), hour, distance)
    }
    val mono = MonoStyles.date.toSpanStyle()
    Text(
        text = buildAnnotatedString {
            append(line)
            for (piece in listOf(hour, distance)) {
                val at = line.indexOf(piece)
                if (at >= 0) addStyle(mono, at, at + piece.length)
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The sentence as heard, marked ([marks]): each "when" in its family's colour, how it was said dimmed, doubts underlined. */
@Composable
private fun HeardLine(check: VoiceCheck, marked: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val inks = TriggerFamily.entries.associateWith { it.color() }
    val text = buildAnnotatedString {
        append(check.heard)
        for (mark in if (marked) check.marks() else emptyList()) {
            val style = when (mark.kind) {
                HeardMark.Kind.MANNER -> SpanStyle(color = scheme.outline)
                HeardMark.Kind.WHEN -> SpanStyle(color = mark.family?.let(inks::getValue) ?: scheme.onSurface, fontWeight = FontWeight.SemiBold)
                HeardMark.Kind.DOUBT -> SpanStyle(color = scheme.error, textDecoration = TextDecoration.Underline)
                HeardMark.Kind.DROPPED -> SpanStyle(color = scheme.outline, textDecoration = TextDecoration.LineThrough)
            }
            addStyle(style, mark.range.first, mark.range.last + 1)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.xs)) {
        Text(stringResource(R.string.voice_heard), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
    }
}

/**
 * The words taken out before reading, named (0.187.0): struck through above, and said here in
 * words too, since a line through them is not something a screen reader says.
 */
@Composable
private fun DroppedLine(dropped: List<String>) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.StrikethroughS, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(Tokens.sizes.glyphMedium))
        Spacer(Modifier.width(Tokens.spacing.sm))
        Text(
            stringResource(R.string.voice_dropped, dropped.joinToString(stringResource(R.string.voice_doubt_join))),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

/** [text] with the stretches in [struck] drawn through, in [ink]. */
private fun struckThrough(text: String, struck: List<IntRange>, ink: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    for (range in struck) {
        val first = range.first.coerceAtLeast(0)
        val last = range.last.coerceAtMost(text.length - 1)
        if (first <= last) addStyle(SpanStyle(color = ink, textDecoration = TextDecoration.LineThrough), first, last + 1)
    }
}

/** The words nothing read, named: the reminder is written, and this is what to look at twice. */
@Composable
private fun DoubtLine(doubts: List<String>) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.HelpOutline, contentDescription = null, tint = scheme.error, modifier = Modifier.size(Tokens.sizes.glyphMedium))
        Spacer(Modifier.width(Tokens.spacing.sm))
        Text(
            stringResource(R.string.voice_doubt, doubts.joinToString(stringResource(R.string.voice_doubt_join))),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
        )
    }
}

/** The other readings, each a chip that puts it in the written one's place. Neutral: none of them is what fires next yet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Alternatives(alternatives: List<VoiceAlternative>, today: LocalDate, defaultTime: LocalTime, onAlternative: (VoiceAlternative) -> Unit) {
    val words = rememberWords()
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.xs)) {
        Text(stringResource(R.string.voice_alternatives), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            for (alternative in alternatives) {
                val reminder = alternative.reminder
                val label = reminder.rules.singleOrNull()?.let { triggerPhrase(words, it.trigger, today, defaultTime) }
                    ?: recurrenceLabel(words, reminder.recurrence, today)
                val description = stringResource(R.string.voice_alternative_use, label)
                SuggestionChip(
                    onClick = {
                        haptics.perform(HapticFeedbackType.ContextClick)
                        onAlternative(alternative)
                    },
                    label = { Text(label, style = MaterialTheme.typography.labelLarge) },
                    icon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(SuggestionChipDefaults.IconSize)) },
                    // Never the chip's default amber: amber is what fires next, and this is not yet.
                    colors = SuggestionChipDefaults.suggestionChipColors(iconContentColor = scheme.onSurfaceVariant, labelColor = scheme.onSurface),
                    modifier = Modifier.testTag(VOICE_ALTERNATIVE_TAG).semantics { contentDescription = description },
                )
            }
        }
    }
}

/** The word between two rules, as the editor's own sentence says it. */
private val RuleMatch.joinRes: Int
    get() = when (this) {
        RuleMatch.ANY -> R.string.editor_sentence_or
        RuleMatch.ALL -> R.string.editor_sentence_and
        RuleMatch.TOGETHER -> R.string.editor_sentence_at_once
    }

/** For the device tests: each chip that offers another reading. */
const val VOICE_ALTERNATIVE_TAG = "voice-alternative"

/** For the device tests: the "Usar esta" under a whole other reading (Gemini's, or the original). */
const val VOICE_OFFER_TAG = "voice-offer"

private fun statusOf(state: VoiceState): Int = when (state) {
    VoiceState.Asking -> R.string.voice_asking
    is VoiceState.Listening -> when {
        state.paused -> R.string.voice_paused
        state.finishing -> R.string.voice_thinking
        else -> R.string.voice_listening
    }
    is VoiceState.Thinking -> if (state.asking) R.string.voice_asking_gemini else R.string.voice_thinking
    is VoiceState.ToEditor -> R.string.voice_thinking
    is VoiceState.Confirm -> R.string.voice_confirm
    is VoiceState.Created -> if (!state.check.readsAsSaid) R.string.voice_created_gemini else when (state.check.refining) {
        Refining.NONE -> R.string.voice_created
        Refining.ASKING -> R.string.voice_created_refining
        Refining.AGREED -> R.string.voice_created_agreed
        Refining.OFFERED -> R.string.voice_created_offered
        Refining.FAILED -> R.string.voice_created_failed
    }
    is VoiceState.Failed -> when (state.why) {
        VoiceFailure.NO_PERMISSION -> R.string.voice_no_permission
        VoiceFailure.NOT_HEARD -> R.string.voice_not_heard
        VoiceFailure.BROKEN -> R.string.voice_broken
    }
}

/**
 * How long a written reminder with nothing to look at twice is shown before the sheet goes by
 * itself: long enough to read the card and the sentence once (the owner's "~6 s", 2026-10-11).
 */
private const val CREATED_DWELL_MS = 6_000L

/**
 * How long without a new word before the microphone closes by itself, the words kept (0.186.0):
 * far past any pause for thought, short of a phone forgotten on the table listening to the room.
 */
private const val QUIET_MS = 30_000L

/** How long after its last word "Terminado" waits for the ear's last stretch before taking what it has heard. */
private const val FINISH_GRACE_MS = 2_000L

/** The activity behind a Compose context, which may be wrapped in a theme or two. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** How strongly the countdown's bar shows on "Listo": a shade of the button, not a second colour. */
private const val CLOSING_BAR_ALPHA = 0.35f

/** How much the disc grows at the loudest. */
private const val DISC_GROWTH = 0.28f
