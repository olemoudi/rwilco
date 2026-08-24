package dev.rwilco.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.model.TriggerFamily
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.familyColor
import dev.rwilco.ui.theme.LocalDarkTheme

/**
 * Swipe right = done, swipe left = delete. Both are undoable from the snackbar the screen
 * shows, which is what makes a 40% threshold acceptable on a card this size.
 *
 * Crossing the threshold is answered on the spot — the glyph springs up and the wash deepens,
 * on the same frame as the haptic — so the thumb knows the swipe has "taken" before it lets go.
 */
@Composable
fun SwipeableCard(
    onDone: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val haptics = Tokens.haptics
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onDone()
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            true
        },
        positionalThreshold = { distance -> distance * 0.4f },
    )
    val armed = state.targetValue != SwipeToDismissBoxValue.Settled
    LaunchedEffect(armed) {
        if (armed) haptics.perform(HapticFeedbackType.GestureThresholdActivate)
    }
    val doneColor = familyColor(TriggerFamily.PLACE, LocalDarkTheme.current)
    val motion = Tokens.motion
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val toDone = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val color = if (toDone) doneColor else MaterialTheme.colorScheme.error
            val wash by animateColorAsState(
                targetValue = color.copy(alpha = if (armed) 0.32f else 0.18f),
                animationSpec = tween(motion.fast),
                label = "swipeWash",
            )
            val glyphScale by animateFloatAsState(
                targetValue = if (armed) 1.3f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                label = "swipeGlyph",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 2.dp)
                    .background(wash, MaterialTheme.shapes.large)
                    .padding(horizontal = Tokens.spacing.xl),
                contentAlignment = if (toDone) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = if (toDone) Icons.Outlined.Check else Icons.Outlined.Delete,
                    contentDescription = stringResource(if (toDone) R.string.card_swipe_done else R.string.card_swipe_delete),
                    tint = color,
                    modifier = Modifier.scale(glyphScale),
                )
            }
        },
        content = { content() },
    )
}


