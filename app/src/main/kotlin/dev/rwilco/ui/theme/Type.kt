package dev.rwilco.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import dev.rwilco.R

/*
 * Three faces, three jobs. Bricolage Grotesque carries the personality: it is what a reminder's
 * own words are set in, from an 18sp card to a 56sp alert. Manrope does the quiet work of labels
 * and body. JetBrains Mono is for anything that is a time, a date or a countdown, so a "21:30"
 * reads as an instrument reading wherever it appears.
 *
 * All three are variable fonts, and Compose does not synthesise weights from a variable file:
 * every weight used below is declared with its own axis setting, or it renders at the default.
 */

private fun bricolage(weight: FontWeight) = Font(
    resId = R.font.bricolage_grotesque,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        // Optical size pinned to the display end: the face is used big, and at 18sp the display
        // cut still looks intentional where the text cut would look like a body face.
        FontVariation.Setting("opsz", 96f),
    ),
)

private fun manrope(weight: FontWeight) = Font(
    resId = R.font.manrope,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private fun mono(weight: FontWeight) = Font(
    resId = R.font.jetbrains_mono,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Bricolage = FontFamily(bricolage(FontWeight.SemiBold), bricolage(FontWeight.Bold))
val Manrope = FontFamily(manrope(FontWeight.Normal), manrope(FontWeight.Medium), manrope(FontWeight.SemiBold))
val Mono = FontFamily(mono(FontWeight.Medium))

/** Big type gets its ascenders back: centred line height, nothing trimmed. */
private val displayLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun display(weight: FontWeight, size: Int, lineHeight: Int, tracking: Float = 0f) = TextStyle(
    fontFamily = Bricolage,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = displayLineHeight,
)

private fun body(weight: FontWeight, size: Int, lineHeight: Int, tracking: Float = 0f) = TextStyle(
    fontFamily = Manrope,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
)

private fun monoStyle(size: Int, lineHeight: Int) = TextStyle(
    fontFamily = Mono,
    fontWeight = FontWeight.Medium,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp,
    lineHeightStyle = displayLineHeight,
)

val RwilcoTypography = Typography(
    // displayLarge is what the Material time picker sets its digits in: mono, so the dial
    // matches every other time in the app for free.
    displayLarge = monoStyle(size = 57, lineHeight = 64),
    displayMedium = display(FontWeight.Bold, size = 44, lineHeight = 50, tracking = -0.5f),
    displaySmall = display(FontWeight.Bold, size = 36, lineHeight = 42, tracking = -0.25f),
    headlineLarge = display(FontWeight.Bold, size = 32, lineHeight = 38, tracking = -0.25f),
    headlineMedium = display(FontWeight.SemiBold, size = 28, lineHeight = 34),
    headlineSmall = display(FontWeight.SemiBold, size = 24, lineHeight = 30),
    titleLarge = display(FontWeight.SemiBold, size = 22, lineHeight = 28),
    titleMedium = display(FontWeight.SemiBold, size = 18, lineHeight = 24),
    titleSmall = body(FontWeight.SemiBold, size = 14, lineHeight = 20, tracking = 0.1f),
    bodyLarge = body(FontWeight.Normal, size = 16, lineHeight = 24, tracking = 0.15f),
    bodyMedium = body(FontWeight.Normal, size = 14, lineHeight = 20, tracking = 0.1f),
    bodySmall = body(FontWeight.Normal, size = 12, lineHeight = 16, tracking = 0.2f),
    labelLarge = body(FontWeight.SemiBold, size = 14, lineHeight = 20, tracking = 0.1f),
    labelMedium = body(FontWeight.Medium, size = 12, lineHeight = 16, tracking = 0.3f),
    labelSmall = body(FontWeight.Medium, size = 11, lineHeight = 16, tracking = 0.3f),
)

/** The instrument readings: not Material roles, so they never get restyled by a component. */
object MonoStyles {
    val date = monoStyle(size = 14, lineHeight = 20)
    val label = monoStyle(size = 16, lineHeight = 22)
    val time = monoStyle(size = 20, lineHeight = 24)
    val countdown = monoStyle(size = 32, lineHeight = 36)
}
