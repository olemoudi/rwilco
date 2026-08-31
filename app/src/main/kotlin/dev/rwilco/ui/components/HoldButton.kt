package dev.rwilco.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.rwilco.ui.theme.Tokens

/**
 * The disc's own size, and it is the touch floor rather than the glyph's: a hold is a gesture
 * that has to be *kept* on the control, so it is the one target it is least excusable to make
 * hard to land on.
 */
private val DISC = 48.dp

/**
 * A control that answers only to a finger that stays: press, and the screen dims around one
 * filling ring ([HoldOverlay]); let go early and it fades, having done nothing. For the one
 * action on a card that must never happen by accident — silencing what is meant to ring.
 *
 * [label] is the verb, not the state — a pause glyph on its own reads as "this is paused" as
 * easily as "pause this" — and it is always said, in both shapes this comes in.
 *
 * [compact] lays the same control out as a pill with the verb beside the glyph instead of a disc
 * with the verb under it. The disc is right where the control is the point (the alert, a screen
 * of its own); the pill is right on a card, where the disc and its caption took a column ~96dp
 * wide out of the top row and stood there competing with the reminder's own words for the width
 * — on the one line of the card that anybody actually reads.
 *
 * A screen reader gets an ordinary click action instead: TalkBack's double tap is already a
 * deliberate act, and there is no thumb to slip.
 */
@Composable
fun HoldButton(
    icon: ImageVector,
    label: String,
    onHoldComplete: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val haptics = Tokens.haptics
    val motion = Tokens.motion
    val scheme = MaterialTheme.colorScheme
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
            // Let go early: the screen comes back and nothing has happened.
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
        onHoldComplete()
        // Done: the screen comes back whether or not the finger has lifted yet.
        overlay.progress.animateTo(0f, tween(motion.fast))
        overlay.prompt = null
    }

    val held = modifier
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                },
            )
        }
        .semantics(mergeDescendants = true) {
            contentDescription = label
            role = Role.Button
            onClick {
                onHoldComplete()
                true
            }
        }
    // A control's own surface and line, in both shapes: the small grey glyphs on the card are
    // read-only, and nothing but this should look pressable.
    if (compact) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = scheme.surfaceContainerHigh,
            border = BorderStroke(Tokens.strokes.control, scheme.outline),
            modifier = held.heightIn(min = DISC),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = Tokens.spacing.md),
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Tokens.spacing.xs))
                Text(text = label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurface)
            }
        }
        return
    }
    Column(modifier = held, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = scheme.surfaceContainerHigh,
            border = BorderStroke(Tokens.strokes.control, scheme.outline),
            modifier = Modifier.size(DISC),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = scheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
    }
}
