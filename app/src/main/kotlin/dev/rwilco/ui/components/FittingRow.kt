package dev.rwilco.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * As many of [content] as fit on one line, and a mark on the end saying the rest are there.
 *
 * The row a card's tags go in once the card is a line tall. A plain `Row` that overflows draws
 * off the edge, and one that clips draws half a chip — which reads as a rendering fault rather
 * than as "there are more". Taking the first two and being done with it is the other easy
 * answer and it lies the other way: on a reminder with two tags it says nothing, and on one
 * with five it says the same nothing.
 *
 * So the children are measured in order and placed while there is room, and [more] goes on the
 * end the moment one has to be left out. The room for [more] is only ever taken from the last
 * child that would otherwise have fitted, so a row whose children all fit never pays for it —
 * which is what keeps the mark from turning up beside a gap it could have filled.
 *
 * The width is what was used rather than what was offered, so a caller can hang this off the
 * end of a `Row` and have it sit against the right edge.
 */
@Composable
fun FittingRow(
    gap: Dp,
    modifier: Modifier = Modifier,
    more: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Layout(contents = listOf(content, more), modifier = modifier) { (items, marks), constraints ->
        val gapPx = gap.roundToPx()
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val mark = marks.firstOrNull()?.measure(loose)
        val markRoom = mark?.let { it.width + gapPx } ?: 0

        val placed = ArrayList<Placeable>(items.size)
        var used = 0
        for ((index, item) in items.withIndex()) {
            val measured = item.measure(loose)
            val lead = if (placed.isEmpty()) 0 else gapPx
            // Room for the mark is only owed while something is still going to be left out
            // behind this one; the last child owes nothing.
            val tail = if (index == items.lastIndex) 0 else markRoom
            if (used + lead + measured.width + tail > constraints.maxWidth) break
            placed += measured
            used += lead + measured.width
        }
        val dropped = placed.size < items.size
        val width = (used + if (dropped) markRoom else 0).coerceAtMost(constraints.maxWidth)
        val height = (placed.maxOfOrNull { it.height } ?: 0)
            .coerceAtLeast(if (dropped) mark?.height ?: 0 else 0)

        layout(width, height) {
            var x = 0
            for (item in placed) {
                item.placeRelative(x, (height - item.height) / 2)
                x += item.width + gapPx
            }
            if (dropped && mark != null) mark.placeRelative(x, (height - mark.height) / 2)
        }
    }
}
