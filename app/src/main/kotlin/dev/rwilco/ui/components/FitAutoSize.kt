package dev.rwilco.ui.components

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * [TextAutoSize.StepBased] that also minds the height.
 *
 * Compose's own, under [TextOverflow.Ellipsis], asks only whether the last line came out
 * ellipsized (foundation 1.12, `AutoSizeStepBased.didOverflowByEllipsize`). One line that is
 * narrow enough is never ellipsized, however tall it is, so it was taken at the largest size and
 * drawn cut through the middle: "Entrenar" and "Kagi news" on the stacked alert, 2026-11-04. Here
 * a size fits when nothing is ellipsized and nothing overflows either way. When no size does, the
 * smallest is used and the ellipsis does what it can, as before.
 *
 * Give it a style through [scalingLineHeight], or the height it measures is the wrong one.
 */
data class FitAutoSize(
    val minFontSize: TextUnit,
    val maxFontSize: TextUnit,
    val stepSize: TextUnit = 2.sp,
) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        fun size(step: Int) = (minFontSize.value + step * stepSize.value).sp
        val top = ((maxFontSize.value - minFontSize.value) / stepSize.value).toInt()
        return size(largestFitting(top) { step -> performLayout(constraints, text, size(step)).fits() })
    }
}

private fun TextLayoutResult.fits(): Boolean =
    !hasVisualOverflow && (lineCount == 0 || !isLineEllipsized(lineCount - 1))

/**
 * The style with its line height as a share of its font size, for text that is auto-sized.
 *
 * Auto-sizing swaps the font size and nothing else, so a line height in sp stays where the type
 * scale put it for the nominal size: a 36 sp title in a 34 sp line, and no size at all that fits
 * a slot shorter than 34. As a share it keeps the type scale's proportion at every size.
 */
fun TextStyle.scalingLineHeight(): TextStyle = copy(lineHeight = (lineHeight.value / fontSize.value).em)

/**
 * The largest step in 0..[top] that [fits], given that a step fitting means every smaller one
 * does — 0 when none does, so step 0 is never laid out. Halves rather than walks: eleven sizes cost
 * four layouts, and this runs on every measure of the text.
 */
internal fun largestFitting(top: Int, fits: (Int) -> Boolean): Int {
    var low = 0
    var high = top
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (fits(mid)) low = mid else high = mid - 1
    }
    return low
}
