package dev.rwilco.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.rwilco.R
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The tick the indicator shows once a hold has been kept; a test hook, and nothing else reads it. */
const val GUARD_TICK_TAG = "guard-tick"

/**
 * What a guarded control is about, as the indicator says it: the glyph in the ring while it
 * fills, the verb while the finger is on it ([holding]), and the word once the hold has been
 * kept ([done] — "Pospuesto" for "Posponer"). [detail] is the second line in both phases: the
 * offer itself, "10 min" or "al salir de aquí".
 */
data class GuardedAction(
    val icon: ImageVector,
    val holding: String,
    val done: String = holding,
    val detail: String? = null,
)

/**
 * The alert screen's defence against a thumb that lands before the eyes have: for the first
 * moments after the screen shows, nothing but Silence answers at all ([armed]); after that,
 * every answer is a finger *kept* on its button ([dev.rwilco.ui.theme.Motion.guardHold]), and
 * given only when it lifts.
 *
 * The screen is what takes over a phone at three in the morning, or comes up under a hand
 * already reaching into a pocket, and its answers are the one kind that cannot be taken back:
 * a reminder dismissed is gone. So a tap — any tap — does nothing here, and the screen says so.
 * The one exception is the noise: silencing confirms nothing and can be reflexive.
 *
 * This holds the state and the rules; the timing (the countdown, the ring, the tick) is driven
 * by [rememberPressGuard] and [guarded], and drawn by [GuardIndicator]. The rules are pure so
 * that the safety property — *nothing fires unless a hold was begun armed, kept to its end
 * and then let go* — has a test that needs no device.
 */
@Stable
class PressGuard(
    /**
     * Skip the countdown the first time this guard arms — the hold stays. For a screen that
     * nobody was startled by: a reminder opened on purpose from a card or a note, or the
     * strips left on a screen that was already armed when one of them was answered. A screen
     * shown *again* after that (the phone picked up a minute later) counts down as ever.
     */
    internal val skipFirstCountdown: Boolean = false,
) {
    /** Whole seconds still to run before the screen answers; 0 once it does. */
    var secondsLeft by mutableIntStateOf(0)
        internal set

    /** Whether the screen answers to a hold at all. False until the countdown has run, and again whenever the screen is left. */
    var armed by mutableStateOf(false)
        internal set

    /** The action a finger is on, from the press until it lifts. One finger, so one of these. */
    var holding by mutableStateOf<GuardedAction?>(null)
        internal set

    /** Whether the hold reached its end: the tick is up and the finger has not lifted yet. */
    var confirmed by mutableStateOf(false)
        internal set

    /** A hold let go early: the indicator says how to answer, for a moment. */
    var hinting by mutableStateOf(false)
        internal set

    /** The ring: drains 1→0 while the countdown runs, fills 0→1 while a finger is kept on something. */
    internal val progress = Animatable(0f)

    /** The countdown has run: from here on a hold is an answer. */
    fun arm() {
        secondsLeft = 0
        armed = true
    }

    /**
     * The screen has been left — or is only now being shown. Whatever a finger was doing is
     * forgotten: a press that survives the app leaving the screen is not a press any more, and
     * must not finish itself off when the person comes back.
     */
    fun disarm() {
        armed = false
        holding = null
        confirmed = false
        hinting = false
    }

    /** A finger has gone down on [action]. Whether the hold counts: only once armed, and only one at a time. */
    fun begin(action: GuardedAction): Boolean {
        if (!armed || holding != null) return false
        holding = action
        confirmed = false
        hinting = false
        return true
    }

    /** The hold on [action] has been kept for its whole length. Nothing is done yet: the finger is still down. */
    fun complete(action: GuardedAction) {
        if (armed && holding === action) confirmed = true
    }

    /**
     * The finger has lifted from [action]. Whether the action is to be done — true only for a
     * hold that was begun armed and kept to its end. A hold let go early is answered with the
     * hint instead; a press that never counted (during the countdown, or a second finger) is
     * nothing at all.
     */
    fun release(action: GuardedAction): Boolean {
        if (holding !== action) return false
        val fire = confirmed
        holding = null
        confirmed = false
        hinting = !fire
        return fire
    }
}

/**
 * A guard for the screen at hand, keyed on [key]: a new key is a new screen — the next reminder
 * taking over from the one just answered, right under the thumb that answered it — and the
 * countdown starts over. It also starts over every time the screen is shown again, because a
 * phone lit up by an alarm and picked up a minute later is the accidental tap this exists for.
 *
 * Two exceptions, both since 0.68.0, and both only for the *first* arming of a guard: with
 * [openedOnPurpose] (a card or a note was tapped — the eyes arrived first) there is no
 * countdown, only the hold; and a new key on a screen whose last guard was already armed
 * (a strip answered, the others left) arms at once too, because two dead seconds per answer
 * on a screen of five was the guard costing more than the accident it guards against.
 */
@Composable
fun rememberPressGuard(key: Any?, openedOnPurpose: Boolean = false): PressGuard {
    val last = remember { mutableStateOf<PressGuard?>(null) }
    val guard = remember(key) {
        PressGuard(skipFirstCountdown = openedOnPurpose || last.value?.armed == true).also { last.value = it }
    }
    val haptics = Tokens.haptics
    val motion = Tokens.motion
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(guard, lifecycleOwner) {
        var skipCountdown = guard.skipFirstCountdown
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                if (!skipCountdown) {
                    coroutineScope {
                        launch {
                            guard.progress.snapTo(1f)
                            guard.progress.animateTo(0f, tween(motion.guardArm, easing = LinearEasing))
                        }
                        val seconds = (motion.guardArm + 999) / 1000
                        for (left in seconds downTo 1) {
                            guard.secondsLeft = left
                            delay(minOf(1000L, motion.guardArm - (left - 1) * 1000L))
                        }
                    }
                    // A small tick for the hand, so nobody has to watch the digits to know.
                    haptics.perform(HapticFeedbackType.ContextClick)
                }
                skipCountdown = false
                guard.arm()
                // The hint as a promise, not a correction: it used to appear only after a
                // press that failed, so the gesture was taught by making somebody fail at it.
                guard.hinting = true
                awaitCancellation()
            } finally {
                guard.disarm()
            }
        }
    }
    LaunchedEffect(guard, guard.hinting) {
        if (guard.hinting) {
            delay(motion.guardHint.toLong())
            guard.hinting = false
        }
    }
    return guard
}

/** Material's own alpha for a control that is not answering right now. */
private const val ASLEEP_ALPHA = 0.38f

/**
 * A control that answers only to a finger that stays: press, and the ring up top fills; keep it
 * there for the whole hold and the tick comes up; lift, and [onConfirmed] runs. Let go early
 * and nothing happens, except that the indicator says how to. Until the guard is [armed] the
 * control is faded and takes nothing.
 *
 * The control keeps its own surface and words; this is the touch, the ripple and the semantics.
 * Put it after the shape's clip, so the ripple has the shape's corners.
 *
 * A screen reader gets an ordinary click action instead: TalkBack's double tap is already a
 * deliberate act, and there is no thumb to slip.
 */
@Composable
fun Modifier.guarded(guard: PressGuard, action: GuardedAction, onConfirmed: () -> Unit): Modifier {
    val haptics = Tokens.haptics
    val motion = Tokens.motion
    val interaction = remember { MutableInteractionSource() }
    val waitWord = stringResource(R.string.alert_guard_wait)
    var pressed by remember { mutableStateOf(false) }
    val fade by animateFloatAsState(
        targetValue = if (guard.armed) 1f else ASLEEP_ALPHA,
        animationSpec = tween(motion.medium),
        label = "guardFade",
    )

    // Disarmed under a finger — the screen was left — and the finger is forgotten with it.
    LaunchedEffect(guard.armed) {
        if (!guard.armed) pressed = false
    }

    LaunchedEffect(pressed) {
        if (!pressed) {
            val ours = guard.holding === action
            if (guard.release(action)) {
                onConfirmed()
            } else if (ours) {
                // Let go early: the ring runs back down under the hint.
                guard.progress.animateTo(0f, tween(motion.fast))
            }
            return@LaunchedEffect
        }
        if (!guard.begin(action)) return@LaunchedEffect
        haptics.perform(HapticFeedbackType.SegmentTick)
        guard.progress.snapTo(0f)
        guard.progress.animateTo(1f, tween(motion.guardHold, easing = LinearEasing))
        guard.complete(action)
        haptics.perform(HapticFeedbackType.Confirm)
    }

    return this
        .graphicsLayer { alpha = fade }
        .pointerInput(guard) {
            detectTapGestures(
                onPress = { offset ->
                    val press = PressInteraction.Press(offset)
                    interaction.emit(press)
                    pressed = true
                    val released = tryAwaitRelease()
                    pressed = false
                    interaction.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                },
            )
        }
        .indication(interaction, ripple())
        .semantics(mergeDescendants = true) {
            role = Role.Button
            if (guard.armed) {
                onClick {
                    onConfirmed()
                    true
                }
            } else {
                // Disabled *and* said why: "desactivado" alone, with no reason and no end,
                // is a screen reader being told the alarm is broken.
                disabled()
                stateDescription = waitWord
            }
        }
}

/**
 * Something on the screen that is not an answer but should still sleep through the countdown
 * with the rest — a toggle that unfolds the offers. Faded until [PressGuard.armed]; the control
 * itself takes `enabled` from the same flag.
 */
@Composable
fun Modifier.asleepUntilArmed(guard: PressGuard): Modifier {
    val fade by animateFloatAsState(
        targetValue = if (guard.armed) 1f else ASLEEP_ALPHA,
        animationSpec = tween(Tokens.motion.medium),
        label = "guardFade",
    )
    return graphicsLayer { alpha = fade }
}

private val RING = 48.dp
private val RING_STROKE = 4.dp
private val GLYPH = 22.dp
private val TICK = 28.dp

/**
 * Where the guard reports, at the top of the screen — the one place a hand holding a button
 * at the bottom is never over. One ring and a line of words in every phase, so the eye learns
 * one spot: the digits while the countdown runs and the ring drains; the action's glyph while
 * the ring fills under a finger; the tick, popping in, once the hold has been kept — and it
 * stays until the finger lifts. A hold let go early gets the hint here for a moment.
 *
 * It keeps its height while empty so nothing under it moves.
 */
@Composable
fun GuardIndicator(guard: PressGuard, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val motion = Tokens.motion
    val spacing = Tokens.spacing
    val arming = guard.secondsLeft > 0
    val action = guard.holding
    val shown = arming || action != null || guard.hinting
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(motion.fast), label = "guardIndicator")
    val pop by animateFloatAsState(
        targetValue = if (guard.confirmed) 1f else 0f,
        animationSpec = tween(motion.medium, easing = motion.emphasized),
        label = "guardTick",
    )
    // The last thing held, so the fade-out still has its words in it.
    var last by remember { mutableStateOf(action) }
    SideEffect { if (action != null) last = action }
    val words = action ?: last

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
        modifier = modifier
            .heightIn(min = RING)
            .graphicsLayer { this.alpha = alpha }
            // What changes up here is what a screen reader needs to hear change: the wait, the
            // hint, the answer being held, the answer given.
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(RING)
                .drawBehind {
                    val stroke = RING_STROKE.toPx()
                    val radius = size.minDimension / 2 - stroke / 2
                    drawCircle(color = scheme.onSurface.copy(alpha = 0.25f), radius = radius, style = Stroke(stroke))
                    drawArc(
                        color = scheme.onSurface,
                        startAngle = -90f,
                        sweepAngle = 360f * guard.progress.value,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    if (pop > 0f) drawCircle(color = scheme.onSurface.copy(alpha = pop), radius = radius + stroke / 2)
                },
        ) {
            when {
                arming -> AnimatedContent(
                    targetState = guard.secondsLeft,
                    transitionSpec = { fadeIn(tween(motion.fast)) togetherWith fadeOut(tween(motion.fast)) },
                    label = "guardCountdown",
                ) { left ->
                    Text(text = left.toString(), style = MonoStyles.time, color = scheme.onSurface)
                }
                else -> {
                    Icon(
                        imageVector = if (guard.hinting) Icons.Outlined.TouchApp else words?.icon ?: Icons.Outlined.TouchApp,
                        contentDescription = null,
                        tint = scheme.onSurface,
                        modifier = Modifier
                            .size(GLYPH)
                            .graphicsLayer { this.alpha = 1f - pop },
                    )
                    if (guard.confirmed) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = scheme.background,
                            modifier = Modifier
                                .size(TICK)
                                .graphicsLayer {
                                    scaleX = pop
                                    scaleY = pop
                                    this.alpha = pop
                                }
                                .testTag(GUARD_TICK_TAG),
                        )
                    }
                }
            }
        }
        Column {
            when {
                // A number alone read as "it is loading"; a word beside it says what the wait is.
                arming -> Text(
                    text = stringResource(R.string.alert_guard_wait),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurfaceVariant,
                )
                guard.hinting -> Text(
                    text = stringResource(R.string.alert_hold_hint),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                )
                words != null -> {
                    Text(
                        text = if (guard.confirmed) words.done else words.holding,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                    )
                    words.detail?.let { detail ->
                        Text(text = detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
