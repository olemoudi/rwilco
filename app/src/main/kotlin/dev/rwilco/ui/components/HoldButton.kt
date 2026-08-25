package dev.rwilco.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.rwilco.ui.theme.Tokens

/**
 * How long the finger has to stay. Long enough that a brush of the thumb never gets there,
 * short enough that meaning it does not feel like a punishment.
 */
private const val HOLD_MS = 700

private val DISC = 44.dp

/**
 * A control that answers only to a finger that stays: press, and the screen dims around one
 * filling ring ([HoldOverlay]); let go early and it fades, having done nothing. For the one
 * action on a card that must never happen by accident — silencing what is meant to ring.
 *
 * [label] is the verb, not the state — a pause glyph on its own reads as "this is paused" as
 * easily as "pause this" — and it sits small under the disc, where it names the button without
 * taking the room a reminder's own words need.
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
) {
    val haptics = Tokens.haptics
    val motion = Tokens.motion
    val scheme = MaterialTheme.colorScheme
    val overlay = LocalHoldOverlay.current
    var pressed by remember { mutableStateOf(false) }

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
        overlay.progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
        haptics.perform(HapticFeedbackType.Confirm)
        onHoldComplete()
        // Done: the screen comes back whether or not the finger has lifted yet.
        overlay.progress.animateTo(0f, tween(motion.fast))
        overlay.prompt = null
    }

    Column(
        modifier = modifier
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
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A control's own surface and line: the small grey glyphs on the card are read-only,
        // and nothing but this should look pressable.
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
