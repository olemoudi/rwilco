package dev.rwilco.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rwilco.ui.theme.Tokens

/**
 * A list inside a box says out loud that it goes on past the edge.
 *
 * A capped list whose rows happen to divide neatly into the height it is given is the worst
 * case there is: the last visible row ends flush with the bottom, nothing is cut, and the
 * result reads as a list that finishes there. Everything below it is lost without a hint that
 * it exists — and the shorter the list, the more convincing the lie, because a scrollbar people
 * would have looked for is not there either.
 *
 * So the edge with list behind it is faded into the container's own colour: [colour] is the
 * surface the list is drawn on, so the fade reads as the rows going *under* the edge rather
 * than as a shadow of its own. Drawn over the content and never under it, and only on the side
 * that has something behind it — a fade at an end that really is the end says the opposite of
 * what this is for. It appears and goes with the scroll ([Motion.fast]) so it is never a
 * flicker on a one-row overshoot.
 *
 * Pair it with a `contentPadding` of about half a row: the fade says there is more, and a row
 * cut in half says how much.
 */
@Composable
fun Modifier.scrollFade(
    state: ScrollableState,
    colour: Color,
    depth: Dp = FADE_DEPTH,
): Modifier {
    // canScrollBackward/Forward are the honest questions: they are false for a list that fits,
    // so a short list is never faded at all.
    val above by animateFloatAsState(
        targetValue = if (state.canScrollBackward) 1f else 0f,
        animationSpec = tween(Tokens.motion.fast),
        label = "scrollFadeTop",
    )
    val below by animateFloatAsState(
        targetValue = if (state.canScrollForward) 1f else 0f,
        animationSpec = tween(Tokens.motion.fast),
        label = "scrollFadeBottom",
    )
    return drawWithContent {
        drawContent()
        val band = depth.toPx().coerceAtMost(size.height / 2f)
        if (above > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(colour.copy(alpha = above), colour.copy(alpha = 0f)),
                    startY = 0f,
                    endY = band,
                ),
                size = Size(size.width, band),
            )
        }
        if (below > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(colour.copy(alpha = 0f), colour.copy(alpha = below)),
                    startY = size.height - band,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - band),
                size = Size(size.width, band),
            )
        }
    }
}

/** Deep enough to read as the rows going under the edge, shallow enough not to hide one. */
private val FADE_DEPTH = 28.dp
