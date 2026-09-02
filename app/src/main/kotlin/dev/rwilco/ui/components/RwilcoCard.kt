package dev.rwilco.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import dev.rwilco.ui.theme.Tokens
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The card surface: one raised step above the ground with a hairline, no shadow and no tonal
 * elevation. On the dark scheme a shadow is invisible and elevation tints amber, so the line is
 * what says "card" in both themes.
 *
 * [rail] is a band of colour down the leading edge, which is how a list of cards gets a rhythm
 * you can read without reading: the colours are the ones the app already owns (a trigger
 * family's), said at ten times the area of the keycap that used to be the only place they
 * appeared. Null for a card with nothing to say with one.
 */
@Composable
fun RwilcoCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /**
     * A press held on the card. Only ever alongside [onClick]: a card whose only way in is a
     * gesture nothing announces is a card most people never open.
     */
    onLongClick: (() -> Unit)? = null,
    /** What the held press does, for a screen reader — it cannot feel the hold. */
    longClickLabel: String? = null,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    rail: Color? = null,
    content: @Composable () -> Unit,
) {
    val border = BorderStroke(Tokens.strokes.edge, MaterialTheme.colorScheme.outlineVariant)
    // Wrapped INSIDE the surface rather than drawn behind it, for two reasons that are really
    // one: the surface paints its own colour over anything behind it, and it clips its content
    // to the shape — so from in here the band is visible and the corner curves it, instead of a
    // straight bar sticking out of a rounded card.
    val painted: @Composable () -> Unit = if (rail == null) {
        content
    } else {
        { Box(Modifier.fillMaxWidth().railBehind(rail)) { content() } }
    }
    val haptics = Tokens.haptics
    if (onClick != null && onLongClick != null) {
        // Surface's own onClick knows nothing about a held press, so the gesture is a modifier
        // and the surface is the plain one. Clipped first, or the ripple squares off the
        // corners the card is drawn with.
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = longClickLabel,
                    onLongClick = {
                        haptics.perform(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                    onClick = onClick,
                ),
            shape = shape,
            color = color,
            border = border,
            content = painted,
        )
    } else if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            border = border,
            content = painted,
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = color,
            border = border,
            content = painted,
        )
    }
}

/** The band itself, down whichever edge is the leading one in this language. */
private fun Modifier.railBehind(color: Color, width: Dp = RAIL): Modifier = drawBehind {
    val band = width.toPx()
    val x = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - band
    drawRect(color = color, topLeft = Offset(x, 0f), size = Size(band, size.height))
}

/**
 * Wide enough to be a colour rather than a line, narrow enough not to be a second card. Three
 * read as a bright bit of the card's own hairline; five is a band.
 */
private val RAIL = 5.dp
