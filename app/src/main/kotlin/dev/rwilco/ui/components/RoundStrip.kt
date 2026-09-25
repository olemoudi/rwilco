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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rwilco.model.RECENT_ROUNDS
import dev.rwilco.model.RoundMark

/**
 * A reminder's last rounds, one mark each, oldest first — the shape of a streak at a glance.
 *
 * The same ink as [DayBars] and for the same reason: there is no family and no tag in this, and
 * amber means the one thing it always means. A round done the first time it was asked is a solid
 * square; done after a snooze, the square's outline; let pass, a dash on the floor; not done, the
 * error colour — the only colour here, because it is the only mark that is news. A routine done
 * after going overdue is the outline in that colour: done, and the streak broken.
 *
 * Every slot is a twentieth of the width whether or not there are twenty rounds yet, so a new
 * reminder's three marks sit at the start of a row that fills up, instead of three huge squares.
 * [label] is what a screen reader hears.
 */
@Composable
fun RoundStrip(marks: List<RoundMark>, label: String, modifier: Modifier = Modifier) {
    if (marks.isEmpty()) return
    val ink = MaterialTheme.colorScheme.onSurface
    val faint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val miss = MaterialTheme.colorScheme.error
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(HEIGHT)
            .semantics { contentDescription = label },
    ) {
        val slot = size.width / RECENT_ROUNDS
        val side = minOf(slot - GAP.toPx(), size.height).coerceAtLeast(1f)
        val top = (size.height - side) / 2f
        val radius = CornerRadius(side / 4f, side / 4f)
        val line = STROKE.toPx()
        marks.forEachIndexed { index, mark ->
            val x = index * slot + (slot - side) / 2f
            when (mark) {
                RoundMark.FIRST_TIME -> drawRoundRect(ink, Offset(x, top), Size(side, side), radius)
                RoundMark.NOT_DONE -> drawRoundRect(miss, Offset(x, top), Size(side, side), radius)
                // The outline drawn inside the square's own bounds, so it is the same size as a
                // solid one beside it rather than a stroke's width bigger.
                RoundMark.DONE, RoundMark.LATE -> drawRoundRect(
                    color = if (mark == RoundMark.LATE) miss else ink,
                    topLeft = Offset(x + line / 2f, top + line / 2f),
                    size = Size(side - line, side - line),
                    cornerRadius = radius,
                    style = Stroke(width = line),
                )
                RoundMark.SKIPPED -> drawRoundRect(faint, Offset(x, size.height / 2f - line / 2f), Size(side, line), CornerRadius(line / 2f, line / 2f))
            }
        }
    }
}

private val HEIGHT = 14.dp
private val GAP = 4.dp
private val STROKE = 2.dp
