package dev.rwilco.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.JoinInner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.model.RuleMatch
import dev.rwilco.ui.theme.Tokens

/**
 * A set of rules drawn as what it is: a root that says how the set is read, and a branch per
 * rule hanging off it.
 *
 * A card with three rows used to be three rows. Nothing said they were one arrangement, and
 * nothing said which of the three arrangements it was except a small grey word above them that
 * was easy to miss and absent altogether on the commonest one — so "al llegar a casa" and "a las
 * nueve" on the same card read as a list, and a list reads as an OR whatever it means.
 *
 * The trunk's line is the first half of the answer: **dashed for "cualquiera"**, because those
 * rows are alternatives and the set is notional — any one of them on its own is the whole thing
 * — and **solid for "todos" and "a la vez"**, where the rows are bound and none of them means
 * anything alone. The glyph at the root is the second half, and it is the one that separates the
 * two bound readings: a list being ticked off (`Checklist`) for the one that accumulates over
 * days, two circles overlapping (`JoinInner`) for the one that has to be true at a single
 * instant. A fork in the road (`AltRoute`) for the loose one.
 *
 * The word beside the glyph is still only said for the two that need saying — "todos", "a la
 * vez" — because "cualquiera" is what a list already looks like and naming the default on every
 * card is noise. The glyph carries it for a screen reader.
 *
 * Only ever drawn for more than one rule: a tree with one branch is not a tree, and a lone rule
 * has no arrangement to be in.
 */
@Composable
fun RuleTree(
    match: RuleMatch,
    count: Int,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    row: @Composable (Int) -> Unit,
) {
    // No spacing: each branch carries its own, so the trunk runs unbroken through the gaps
    // instead of stopping at every row and starting again under it.
    Column(modifier = modifier) {
        RuleRoot(match, muted)
        for (index in 0 until count) {
            RuleBranch(match = match, last = index == count - 1, muted = muted) { row(index) }
        }
    }
}

/** The glyph the trunk hangs from, and the word for it where there is one to say. */
@Composable
private fun RuleRoot(match: RuleMatch, muted: Boolean) {
    val word = match.wordRes
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(GUTTER), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = match.glyph,
                // Said out loud only where it is not written beside it, or a reader hears the
                // word twice.
                contentDescription = if (word == null) stringResource(R.string.editor_match_any) else null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(GLYPH),
            )
        }
        if (word != null) {
            Spacer(Modifier.width(Tokens.spacing.xs))
            Text(
                text = stringResource(word),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One branch: the trunk through this row's height, and the arm out to the rule itself. */
@Composable
private fun RuleBranch(match: RuleMatch, last: Boolean, muted: Boolean, content: @Composable () -> Unit) {
    val line = if (muted) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outline
    val dashed = match == RuleMatch.ANY
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Canvas(modifier = Modifier.width(GUTTER).fillMaxHeight()) {
            val x = size.width / 2f
            val middle = size.height / 2f
            val stroke = STROKE.toPx()
            val dash = DASH.toPx()
            // The trunk stops at the last branch: below it there is nothing left to hold.
            drawLine(
                color = line,
                start = Offset(x, 0f),
                end = Offset(x, if (last) middle else size.height),
                strokeWidth = stroke,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(dash, dash)) else null,
            )
            // The arm is always solid: what is loose about "cualquiera" is the set, not the rule.
            drawLine(
                color = line,
                start = Offset(x, middle),
                end = Offset(size.width, middle),
                strokeWidth = stroke,
                cap = StrokeCap.Square,
            )
        }
        // Inside the row's height rather than between the rows, so the trunk covers it.
        Box(modifier = Modifier.padding(vertical = Tokens.spacing.xs)) { content() }
    }
}

/** What each reading looks like at the root of its own tree. */
private val RuleMatch.glyph: ImageVector
    get() = when (this) {
        // A road that splits: take either.
        RuleMatch.ANY -> Icons.AutoMirrored.Outlined.AltRoute
        // A list with its ticks: they happen in any order, over days, and are counted off.
        RuleMatch.ALL -> Icons.Outlined.Checklist
        // Two circles and the piece they share: the one moment all of them are true.
        RuleMatch.TOGETHER -> Icons.Outlined.JoinInner
    }

/** Null where the shape says it already: a list of alternatives is what a list looks like. */
private val RuleMatch.wordRes: Int?
    get() = when (this) {
        RuleMatch.ANY -> null
        RuleMatch.ALL -> R.string.card_match_all
        RuleMatch.TOGETHER -> R.string.card_match_together
    }

/** The column the trunk and the arms live in, and the glyph that sits at the top of it. */
private val GUTTER = 20.dp
private val GLYPH = 16.dp
private val STROKE = 1.dp
private val DASH = 3.dp
