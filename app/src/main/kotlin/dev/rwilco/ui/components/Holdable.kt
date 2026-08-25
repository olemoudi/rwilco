package dev.rwilco.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.rwilco.ui.theme.Tokens

/** The same 700ms everywhere: one length of hold in the app, so a hand only learns it once. */
const val HOLD_MILLIS = 700

/**
 * A tap and a hold on the same thing.
 *
 * The tap is what the control is for; the hold is the second thing it can do, reported by the
 * screen dimming around a filling ring ([HoldOverlay]) so nobody has to guess how long to wait
 * or whether it took. Letting go early leaves the tap intact — a hold that fails is just a tap.
 *
 * A screen reader cannot hold, so the second thing is offered as a custom action instead.
 */
@Composable
fun Modifier.holdable(
    icon: ImageVector,
    label: String,
    onHold: () -> Unit,
    onTap: () -> Unit,
): Modifier {
    val haptics = Tokens.haptics
    val motion = Tokens.motion
    val overlay = LocalHoldOverlay.current
    var pressed by remember { mutableStateOf(false) }
    // Set the moment the hold completes, so the release that follows is not also a tap.
    var held by remember { mutableStateOf(false) }

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
        held = false
        haptics.perform(HapticFeedbackType.SegmentTick)
        overlay.prompt = HoldPrompt(icon, label)
        overlay.progress.snapTo(0f)
        overlay.progress.animateTo(1f, tween(HOLD_MILLIS, easing = LinearEasing))
        haptics.perform(HapticFeedbackType.Confirm)
        held = true
        onHold()
        overlay.progress.animateTo(0f, tween(motion.fast))
        overlay.prompt = null
    }

    return this
        .pointerInput(onTap, onHold) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                },
                onTap = { if (!held) onTap() },
            )
        }
        .semantics {
            customActions = listOf(CustomAccessibilityAction(label) { onHold(); true })
        }
}
