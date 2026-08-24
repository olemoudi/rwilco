package dev.rwilco.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.rwilco.ui.theme.LocalDarkTheme

/**
 * The lamp: a soft radial wash of the primary colour from the top-right, drawn behind whatever
 * is inside. [intensity] 0..1; the hero uses a low one and steps it up as the moment nears, the
 * full-screen alert turns it all the way up. Darker schemes need a stronger wash to read as
 * light at all, hence the two scales.
 */
@Composable
fun Modifier.lampGlow(color: Color, intensity: Float): Modifier {
    val dark = LocalDarkTheme.current
    val alpha = (if (dark) 0.55f else 0.35f) * intensity.coerceIn(0f, 1f)
    return drawBehind {
        val radius = size.width * 0.75f
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
                center = Offset(size.width * 0.9f, 0f),
                radius = radius,
            ),
        )
    }
}
