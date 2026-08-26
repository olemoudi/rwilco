package dev.rwilco.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.rwilco.R
import dev.rwilco.model.TriggerFamily
import dev.rwilco.ui.theme.LocalDarkTheme
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.familyColor

/** How long the card has to be held open before the action takes. */
private const val SWIPE_HOLD_MILLIS = 500

private val GLASS = 48.dp

/**
 * Swipe right = done, swipe left = delete — but not on the swipe alone.
 *
 * A card that acts the instant a thumb crosses a line is a card that gets dealt with while
 * somebody is scrolling past it. So the swipe opens the card and then asks you to mean it: hold
 * it there and the glyph fills like a glass of water; let go, or slide back, and nothing has
 * happened. Half a second is longer than any accident and shorter than any wait.
 *
 * The box is never allowed to settle at its dismissed end. The row is leaving the list anyway,
 * so the snap back is not seen — and a box left resting there outlives the row, which is what
 * once handed a reminder back from "undo" frozen halfway across the screen.
 */
@Composable
fun SwipeableCard(
    onDone: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val haptics = Tokens.haptics
    val motion = Tokens.motion
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { false },
        positionalThreshold = { distance -> distance * 0.35f },
    )
    val fill = remember { Animatable(0f) }
    // Set when this opening has already acted (or has been called off), so the box asking again
    // while it refuses to settle cannot act twice.
    var spent by remember { mutableStateOf(false) }

    val armed = state.targetValue != SwipeToDismissBoxValue.Settled
    val toDone = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd

    // A gesture that outlives the app being on screen is not a gesture any more.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Only a box that is open: spending one at rest would swallow the first swipe
            // on every card after every alert, screen-off or app switch.
            if (event == Lifecycle.Event.ON_PAUSE && state.targetValue != SwipeToDismissBoxValue.Settled) spent = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(armed) {
        if (!armed) {
            // Back at rest: the glass empties and the next opening may act.
            spent = false
            fill.animateTo(0f, tween(motion.fast))
            return@LaunchedEffect
        }
        if (spent) return@LaunchedEffect
        haptics.perform(HapticFeedbackType.GestureThresholdActivate)
        fill.snapTo(0f)
        fill.animateTo(1f, tween(SWIPE_HOLD_MILLIS, easing = LinearEasing))
        spent = true
        haptics.perform(HapticFeedbackType.Confirm)
        if (toDone) onDone() else onDelete()
    }

    val doneColor = familyColor(TriggerFamily.PLACE, LocalDarkTheme.current)
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val color = if (toDone) doneColor else MaterialTheme.colorScheme.error
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 2.dp)
                    .background(color.copy(alpha = 0.18f), MaterialTheme.shapes.large)
                    .padding(horizontal = Tokens.spacing.lg),
                contentAlignment = if (toDone) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                FillingGlyph(
                    icon = if (toDone) Icons.Outlined.Check else Icons.Outlined.Delete,
                    contentDescription = stringResource(if (toDone) R.string.card_swipe_done else R.string.card_swipe_delete),
                    color = color,
                    fill = fill.value,
                )
            }
        },
        content = { content() },
    )
}

/**
 * The glyph in a glass. The water rises from the bottom while the card is held open, so how
 * much longer to hold is something you can watch rather than something you have to guess.
 */
@Composable
private fun FillingGlyph(icon: ImageVector, contentDescription: String, color: Color, fill: Float) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(GLASS)
            .clip(CircleShape)
            .drawBehind {
                drawCircle(color = color.copy(alpha = 0.18f))
                if (fill > 0f) {
                    val top = size.height * (1f - fill.coerceIn(0f, 1f))
                    drawRect(color = color, topLeft = Offset(0f, top), size = Size(size.width, size.height - top))
                }
                drawCircle(color = color.copy(alpha = 0.55f), style = Stroke(width = 1.5.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            // Ink on the water once there is water to sit on; the action's own colour before.
            tint = if (fill > 0.45f) scheme.surfaceContainerLowest else color,
            modifier = Modifier.size(22.dp),
        )
    }
}
