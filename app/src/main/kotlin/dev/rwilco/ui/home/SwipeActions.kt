package dev.rwilco.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.rwilco.R
import dev.rwilco.model.TriggerFamily
import dev.rwilco.ui.theme.LocalDarkTheme
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.familyColor

/**
 * How long the card has to be held open before the action takes. 300 ms since 0.74.0 (it was
 * 500): the swipe itself is already the deliberate half of the gesture — nothing here happens
 * on a scroll — and the hold is there so a thumb that opened a card by accident can take it
 * back, which it can as well in three tenths of a second as in five.
 */
private const val SWIPE_HOLD_MILLIS = 300

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
 *
 * **And nothing moves until the hand leaves.** The glass fills under a thumb that is still on
 * the screen, so acting there and then pulled the next card up into the space this one was
 * still being held in — and whatever was underneath arrived under a finger that had not let go
 * of the last thing it touched. A reminder that comes back is worse: it is dealt with, sorted,
 * and lands back in the same place, so the card under the thumb changes into a different
 * reading of itself. So the row goes blank at once (the action HAS taken: the glass is full and
 * the phone has said so) and keeps its height, leaving the hole it made; the list closes up on
 * the release, when there is no finger left for it to move anything under.
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
    // Whether a finger is still on this card. Watched on the Initial pass and consumed nowhere,
    // so the swipe itself never notices it is being read.
    var pressed by remember { mutableStateOf(false) }
    // The action the full glass earned, waiting for the hand to leave: true is "hecho", false is
    // "borrar". Held rather than read off the box at release time, because by then the box has
    // begun sliding back and its direction says Settled — which would delete what was ticked.
    var takenAsDone by remember { mutableStateOf<Boolean?>(null) }

    val armed = state.targetValue != SwipeToDismissBoxValue.Settled
    val toDone = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
    // What the swipes do, for whoever cannot swipe — on every card, the hero included (0.68.0):
    // hung on the card's own modifier they reached every card but the one the screen is about.
    val doneLabel = stringResource(R.string.card_swipe_done)
    val deleteLabel = stringResource(R.string.card_swipe_delete)

    // A gesture that outlives the app being on screen is not a gesture any more.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Only a box that is open: spending one at rest would swallow the first swipe
            // on every card after every alert, screen-off or app switch.
            if (event == Lifecycle.Event.ON_PAUSE && state.targetValue != SwipeToDismissBoxValue.Settled) spent = true
            // An app that has left the screen takes the hand with it: no pointer is going to
            // report a release now, and an action already earned must not be lost waiting for
            // one that cannot come.
            if (event == Lifecycle.Event.ON_PAUSE) pressed = false
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
        takenAsDone = toDone
    }

    // The row leaves the list only once the finger has: see the note above.
    LaunchedEffect(takenAsDone, pressed) {
        val done = takenAsDone ?: return@LaunchedEffect
        if (pressed) return@LaunchedEffect
        if (done) onDone() else onDelete()
        // And the hole closes with it. It was made for the hand, and the hand has gone: what
        // belongs in that space now fades into it — the card below sliding up as the row
        // leaves, or this same one back with its next moment on it, which is what a reminder
        // that comes round again does. Left blank instead, it stayed blank: the row is still
        // in the list under the same key, so nothing rebuilt it until a scroll took it off
        // screen and back.
        takenAsDone = null
    }

    val doneColor = familyColor(TriggerFamily.PLACE, LocalDarkTheme.current)
    // Blank the instant it takes, and keep the height until the row itself goes: what is left is
    // the hole the card made, which is the one thing that can be under a thumb without being
    // something the thumb might have meant to press.
    val shown by animateFloatAsState(
        targetValue = if (takenAsDone != null) 0f else 1f,
        // Out fast, because the hole is the answer to a gesture that has just finished; back in
        // slower, because it is a card arriving rather than one being taken away.
        animationSpec = tween(if (takenAsDone != null) motion.fast else motion.medium),
        label = "swipeTaken",
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(doneLabel) { onDone(); true },
                CustomAccessibilityAction(deleteLabel) { onDelete(); true },
            )
        }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        pressed = event.changes.any { it.pressed }
                    }
                }
            }
            .alpha(shown),
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
                    // Silent: the glass is composed under every card at rest, and named it put
                    // a stray "Eliminar" between every two cards in a screen reader's walk.
                    // The actions are the custom actions above.
                    contentDescription = null,
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
private fun FillingGlyph(icon: ImageVector, contentDescription: String?, color: Color, fill: Float) {
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
