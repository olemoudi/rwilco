package dev.rwilco.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.rwilco.ui.theme.Tokens

/** The same 500ms everywhere: one length of hold in the app, so a hand only learns it once. */
const val HOLD_MILLIS = 500

/**
 * What the hold tells the control it sits on: [held] is true from the moment a hold completes
 * until the next finger goes down, and it is how the control knows to let this one go without
 * doing its ordinary job.
 */
@Stable
class HoldState {
    var held by mutableStateOf(false)
        internal set
}

@Composable
fun rememberHoldState(): HoldState = remember { HoldState() }

/**
 * A second thing a control can do, on a held finger, reported by the screen dimming around a
 * filling ring ([HoldOverlay]) so nobody has to guess how long to wait or whether it took.
 *
 * This only *watches*. The control keeps its own click — its ripple, its semantics, its role —
 * and asks [HoldState.held] whether the gesture that just ended was a hold rather than a tap.
 * Handling the tap here instead is what broke these chips: a Material chip has a clickable of
 * its own, the innermost handler sees the finger first, and a `detectTapGestures` outside it
 * waits for a press that has already been spoken for — so neither thing happened. Watching
 * consumes nothing and steals nothing, which is why both can live on one chip.
 *
 * A screen reader cannot hold, so the second thing is offered as a custom action too.
 */
@Composable
fun Modifier.holdable(
    icon: ImageVector,
    label: String,
    onHold: () -> Unit,
    state: HoldState,
): Modifier {
    val haptics = Tokens.haptics
    val motion = Tokens.motion
    val overlay = LocalHoldOverlay.current
    var pressed by remember { mutableStateOf(false) }

    // A press that survives the app leaving the screen is not a press any more. Without this a
    // finger still down when an alert takes over — or when anything else pulls the app away —
    // leaves the hold armed, starved of frames, to finish itself off the moment the person comes
    // back: a reminder pausing itself with nobody touching the phone.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) pressed = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(pressed) {
        if (!pressed) {
            if (overlay.prompt != null) {
                overlay.progress.animateTo(0f, tween(motion.fast))
                overlay.prompt = null
            }
            return@LaunchedEffect
        }
        haptics.perform(HapticFeedbackType.SegmentTick)
        overlay.prompt = HoldPrompt(icon, label)
        overlay.progress.snapTo(0f)
        overlay.progress.animateTo(1f, tween(HOLD_MILLIS, easing = LinearEasing))
        haptics.perform(HapticFeedbackType.Confirm)
        // Set before the finger lifts, which is when the control asks.
        state.held = true
        onHold()
        overlay.progress.animateTo(0f, tween(motion.fast))
        overlay.prompt = null
    }

    return this
        .pointerInput(onHold) {
            val slop = viewConfiguration.touchSlop
            awaitPointerEventScope {
                while (true) {
                    // Unconsumed or not: the control under this has every right to the tap.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    state.held = false
                    pressed = true
                    var finger = down.id
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == finger } ?: break
                        // A finger on its way somewhere — scrolling the row this chip is in —
                        // is not a hold, and must not become one by standing still afterwards.
                        if (!change.pressed || (change.position - down.position).getDistance() > slop) break
                    }
                    pressed = false
                }
            }
        }
        .semantics {
            customActions = listOf(CustomAccessibilityAction(label) { onHold(); true })
        }
}
