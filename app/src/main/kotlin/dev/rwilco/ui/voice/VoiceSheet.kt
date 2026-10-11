package dev.rwilco.ui.voice

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
import dev.rwilco.voice.VoiceAlternative
import dev.rwilco.voice.VoiceCheck
import dev.rwilco.voice.VoiceFailure
import dev.rwilco.voice.VoiceInput
import dev.rwilco.voice.VoiceInputEvent
import dev.rwilco.voice.VoiceOutcome
import dev.rwilco.voice.VoiceState
import dev.rwilco.voice.doubtWords
import dev.rwilco.voice.marks
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
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
    // Written, shown to be checked, and gone by itself when there is nothing to look at twice —
    // the countdown drawn on "Listo" — unless a hand reaches for the sheet first. Keyed by the
    // reminder's id: an alternative chosen is the same reminder, and a touch is still a touch.
    val created = state as? VoiceState.Created
    val createdId = created?.reminder?.id
    var touched by remember(createdId) { mutableStateOf(false) }
    val closing = remember(createdId) { Animatable(0f) }
    LaunchedEffect(createdId) {
        if (createdId != null) haptics.perform(HapticFeedbackType.Confirm)
    }
    val closesItself = created != null && created.check.clear && !touched
    LaunchedEffect(createdId, closesItself) {
        closing.snapTo(0f)
        if (!closesItself) return@LaunchedEffect
        closing.animateTo(1f, tween(CREATED_DWELL_MS, easing = LinearEasing))
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
                    .padding(bottom = spacing.md)
                    // Any touch on a written reminder's sheet is somebody reading it: it stays.
                    .pointerInput(createdId) {
                        if (createdId == null) return@pointerInput
                        awaitPointerEventScope {
                            while (true) {
                                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) touched = true
                            }
                        }
                    },
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
                val words = when (current) {
                    is VoiceState.Listening -> current.partial
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
                            Text(words, style = MaterialTheme.typography.headlineSmall, color = scheme.onSurface)
                        }
                    }
                    // What it is turning into while it is said — the words without their "when",
                    // and the "when" in the colours the editor's own sentence over "Guardar" uses.
                    val shown: Spoken? = if (current is VoiceState.Listening || current is VoiceState.Thinking) reading else null
                    if (shown != null) {
                        Spacer(Modifier.height(spacing.sm))
                        ReminderSentence(
                            parts = sentenceParts(shown.text, shown.rules, shown.match, shown.recurrence, shown.deadline),
                            today = today,
                            defaultTime = defaultTime,
                            zone = zone,
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
    /** How far the written reminder's sheet is through going by itself, 0..1. */
    closing: () -> Float,
    closesItself: Boolean,
    onCancel: () -> Unit,
    onDone: () -> Unit,
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
        if (check.heard.isNotBlank()) HeardLine(check)
        val doubts = check.doubtWords()
        if (doubts.isNotEmpty()) DoubtLine(doubts)
        if (onAlternative != null && check.alternatives.isNotEmpty()) Alternatives(check.alternatives, today, defaultTime, onAlternative)
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
    // Under an hour a distance is in minutes alone: the seconds tick on Home, not in a line read once.
    val distance = if (parts.underAnHour) {
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
private fun HeardLine(check: VoiceCheck) {
    val scheme = MaterialTheme.colorScheme
    val inks = TriggerFamily.entries.associateWith { it.color() }
    val text = buildAnnotatedString {
        append(check.heard)
        for (mark in check.marks()) {
            val style = when (mark.kind) {
                HeardMark.Kind.MANNER -> SpanStyle(color = scheme.outline)
                HeardMark.Kind.WHEN -> SpanStyle(color = mark.family?.let(inks::getValue) ?: scheme.onSurface, fontWeight = FontWeight.SemiBold)
                HeardMark.Kind.DOUBT -> SpanStyle(color = scheme.error, textDecoration = TextDecoration.Underline)
            }
            addStyle(style, mark.range.first, mark.range.last + 1)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.xs)) {
        Text(stringResource(R.string.voice_heard), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
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

/**
 * How long a written reminder with nothing to look at twice is shown before the sheet goes by
 * itself: long enough to read the card and the sentence once (the owner's "~6 s", 2026-10-11).
 */
private const val CREATED_DWELL_MS = 6_000

/** How strongly the countdown's bar shows on "Listo": a shade of the button, not a second colour. */
private const val CLOSING_BAR_ALPHA = 0.35f

/** How much the disc grows at the loudest. */
private const val DISC_GROWTH = 0.28f
