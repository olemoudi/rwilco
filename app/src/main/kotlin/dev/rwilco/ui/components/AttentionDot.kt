package dev.rwilco.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.rwilco.ui.theme.Tokens

/**
 * A dot on the corner of a section's icon: something inside is wrong in a way the fold would
 * otherwise hide until somebody happened to open it.
 *
 * The sibling of [UpdateReadyBadge] — the same corner, the same ring of whatever is behind it, the
 * same short way in and out — in the error ink rather than the green of something ready, and with
 * no glyph in it: it is a mark, not a message. **What it means is said in words** by the row's own
 * summary, which is also the only thing a screen reader has to read, so this carries no
 * description of its own rather than saying "warning" twice.
 */
@Composable
fun AttentionDot(visible: Boolean, ground: Color, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Tokens.motion.fast)) + scaleIn(tween(Tokens.motion.medium), initialScale = 0.6f),
        exit = fadeOut(tween(Tokens.motion.fast)) + scaleOut(tween(Tokens.motion.fast), targetScale = 0.6f),
        modifier = modifier.offset(x = DOT / 3, y = -DOT / 3),
    ) {
        Box(
            modifier = Modifier
                .size(DOT)
                // Drawn over the edge of the ink, which is what turns the ring into a cut-out.
                .border(RING, ground, CircleShape)
                .background(MaterialTheme.colorScheme.error, CircleShape),
        )
    }
}

private val DOT = 14.dp
private val RING = 2.dp
