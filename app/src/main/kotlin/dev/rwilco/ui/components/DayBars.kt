package dev.rwilco.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A day per bar, oldest first, today last: the shape of a fortnight.
 *
 * It answers a question a list cannot — "how has this been going?" — and it answers it in the
 * time it takes to look. Deliberately plain: the bars are ink, not colour, because there is no
 * family and no tag in this and amber means the one thing it always means.
 *
 * A day with nothing in it is a dash on the floor rather than a gap, so the row reads as
 * fourteen days of which some were empty, instead of as a handful of bars floating in space.
 * Nothing here animates: it is a fact, not an event.
 *
 * [label] is what a screen reader hears — the whole row is one thing to it, since fourteen
 * unlabelled bars are fourteen ways to say nothing.
 */
@Composable
fun DayBars(counts: List<Int>, label: String, modifier: Modifier = Modifier) {
    if (counts.isEmpty()) return
    val ink = MaterialTheme.colorScheme.onSurface
    // Quiet enough that the days with something in them are what the eye lands on: a fortnight
    // of dashes at full strength reads as the chart, and the bars as the exception.
    val empty = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    // Scaled to the busiest day, but never to a busiest day of one: without the floor a week
    // with a single "hecho" in it drew a full-height bar, which says "this was the big one"
    // about a Tuesday somebody answered one reminder on.
    val top = maxOf(counts.max(), FLOOR_SCALE)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(HEIGHT)
            .semantics { contentDescription = label },
    ) {
        val slot = size.width / counts.size
        val bar = (slot - GAP.toPx()).coerceAtLeast(1f)
        val floor = FLOOR.toPx()
        val radius = CornerRadius(bar / 3f, bar / 3f)
        counts.forEachIndexed { index, count ->
            val x = index * slot + (slot - bar) / 2f
            // An empty day is the same dash whatever the tallest bar is; a day with anything in
            // it is at least a square, so "one" is visible next to a day that had nine.
            val tall = if (count == 0) floor else (size.height * count / top).coerceAtLeast(bar)
            drawRoundRect(
                color = if (count == 0) empty else ink,
                topLeft = Offset(x, size.height - tall),
                size = Size(bar, tall),
                cornerRadius = radius,
            )
        }
    }
}

/** Tall enough for a fortnight to have a shape, short enough not to be the screen. */
private val HEIGHT = 56.dp

/** The dash an empty day leaves on the floor, and the air between two bars. */
private val FLOOR = 3.dp
private val GAP = 6.dp

/** The height a bar is measured against when the busiest day was quieter than this. */
private const val FLOOR_SCALE = 3
