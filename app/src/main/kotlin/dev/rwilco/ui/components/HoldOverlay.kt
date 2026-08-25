package dev.rwilco.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.rwilco.ui.theme.Tokens

/** What is being held, and how far along it is. One finger, so one of these at a time. */
data class HoldPrompt(val icon: ImageVector, val label: String)

/**
 * The state a [HoldButton] publishes while a finger is on it, and [HoldOverlay] draws. It lives
 * at the root of the app rather than in the button because what it draws is the whole screen:
 * a control that took a corner of a card cannot dim the rest of it from there.
 */
@Stable
class HoldOverlayState {
    internal val progress = Animatable(0f)
    internal var prompt by mutableStateOf<HoldPrompt?>(null)
}

/**
 * A default instance so a [HoldButton] outside the app's root still works — it simply holds
 * without anything to show for it.
 */
val LocalHoldOverlay = staticCompositionLocalOf { HoldOverlayState() }

private val RING_RADIUS = 56.dp
private val RING_STROKE = 6.dp

/**
 * While a finger is holding something down: everything else fades back and one ring fills in
 * the middle of the screen, with the icon and the verb inside it.
 *
 * In the middle, and not around the button, because that is the one place on a phone no thumb
 * is ever over. It draws only — no clickable, no pointer input — so the touch it is reporting
 * on carries on reaching the button underneath it.
 */
@Composable
fun HoldOverlay(state: HoldOverlayState = LocalHoldOverlay.current) {
    val prompt = state.prompt ?: return
    val scheme = MaterialTheme.colorScheme
    val progress = state.progress.value
    // The dimming leads the ring: it is at full by a third of the way in, so the screen has
    // answered the finger long before the ring has finished. Letting go runs it back down.
    val dim = (progress * 3f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.scrim.copy(alpha = 0.72f * dim)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
            modifier = Modifier.drawBehind {
                val radius = RING_RADIUS.toPx()
                val stroke = RING_STROKE.toPx()
                drawCircle(color = scheme.onSurface.copy(alpha = 0.25f * dim), radius = radius, style = Stroke(stroke))
                drawArc(
                    color = scheme.onSurface.copy(alpha = dim),
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            },
        ) {
            Icon(
                imageVector = prompt.icon,
                contentDescription = null,
                tint = scheme.onSurface.copy(alpha = dim),
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = prompt.label,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface.copy(alpha = dim),
            )
        }
    }
}
