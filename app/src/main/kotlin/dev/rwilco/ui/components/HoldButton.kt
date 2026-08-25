package dev.rwilco.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

/** How long the finger has to stay down. Long enough that a brush of the thumb never gets there. */
private const val HOLD_MS = 2_000

/**
 * The ring's radius, and the whole point of it: a fingertip covers a circle of roughly 20dp
 * around the touch, so a ring drawn at 34dp is a clear 14dp — about 5mm — outside the finger.
 * It is drawn outside the button's own box on purpose; nothing between here and the card's
 * rounded edge clips it, and a ring the finger hides is no feedback at all.
 */
private val RING_RADIUS = 34.dp
private val RING_STROKE = 3.dp
private val DISC = 44.dp

/**
 * A control that answers only to a finger that stays: press, and a ring fills around the
 * button; let go early and it empties, having done nothing. For the one action on a card that
 * must never happen by accident — silencing what is meant to ring.
 *
 * [label] is the verb, not the state: a pause glyph on its own reads as "this is paused" as
 * easily as "pause this", and the card has no room for that doubt. The word and the button are
 * one target, so the whole thing is pressable and nothing beside it pretends to be.
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
    val progress = remember { Animatable(0f) }
    var pressed by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (!pressed) {
            // Let go early: the ring empties faster than it filled, and nothing happens.
            progress.animateTo(0f, tween(motion.fast))
            return@LaunchedEffect
        }
        haptics.perform(HapticFeedbackType.SegmentTick)
        progress.snapTo(0f)
        progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
        haptics.perform(HapticFeedbackType.Confirm)
        onHoldComplete()
        // The ring has said what it had to say; the state under it has changed.
        progress.snapTo(0f)
    }

    Row(
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
        )
        // Wide enough that the ring, which reaches 12dp past the disc, lands in this gap
        // rather than across the word.
        Spacer(Modifier.width(Tokens.spacing.md))
        Box(
            modifier = Modifier
                .size(Tokens.sizes.control)
                .drawBehind {
                    if (progress.value <= 0f) return@drawBehind
                    val radius = RING_RADIUS.toPx()
                    val stroke = RING_STROKE.toPx()
                    drawCircle(color = scheme.outlineVariant, radius = radius, style = Stroke(stroke))
                    drawArc(
                        color = scheme.onSurface,
                        startAngle = -90f,
                        sweepAngle = 360f * progress.value,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                },
            contentAlignment = Alignment.Center,
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
        }
    }
}
