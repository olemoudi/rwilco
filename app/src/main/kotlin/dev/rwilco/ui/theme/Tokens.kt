package dev.rwilco.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Single spacing scale; screens never use loose magic dp values. */
data class Spacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val screen: Dp = 20.dp,
)

/**
 * Motion tokens for the app's own transitions. Short and purposeful: nothing exceeds ~250ms.
 * Material components animate with the theme's expressive MotionScheme instead.
 */
data class Motion(
    val fast: Int = 140,
    val medium: Int = 220,
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    /** A held stepper waits this long before it starts repeating: long enough that a tap is a tap. */
    val holdRepeatDelay: Int = 400,
    /** The first repeat's interval; every one after is [holdRepeatQuicken] of the last, to [holdRepeatFloor]. */
    val holdRepeatStart: Int = 220,
    val holdRepeatFloor: Int = 60,
    val holdRepeatQuicken: Float = 0.82f,
)

/**
 * Line weights. Two, because they say different things: [edge] is what makes a card a card, and
 * [control] is what says "this responds to a thumb". A single hairline for both is what makes a
 * screen read as flat — every surface equally quiet, nothing asking to be pressed.
 */
data class Strokes(
    val edge: Dp = 1.dp,
    val control: Dp = 1.5.dp,
    /** A control that is on, or otherwise wants to be the loudest line on the screen. */
    val strong: Dp = 2.dp,
)

/**
 * Touch sizes. [touch] is the Material floor; [control] is what a control a thumb reaches for
 * gets; [primary] is the one button a screen is about (Save, Done).
 */
data class Sizes(
    val touch: Dp = 48.dp,
    val control: Dp = 56.dp,
    val primary: Dp = 64.dp,
    /**
     * The map in the place sheet: this share of the window's height, and never less than
     * [mapMinHeight]. It was a fixed 260dp, which on a tall phone is a letterbox the circle
     * has to be aimed through; a share of the screen is what "generous" means on every phone.
     */
    val mapShare: Float = 0.42f,
    val mapMinHeight: Dp = 260.dp,
    val keycap: Dp = 36.dp,
    /** The small square that carries a section's icon. */
    val badge: Dp = 28.dp,
    /**
     * The cog beside the wordmark on Home: the one glyph on the screen drawn larger than the
     * ordinary 24dp, because it and the name are a single control and it has to look like the
     * way in rather than like the fourth icon in a row of four.
     */
    val cog: Dp = 32.dp,
    /** The clock column down the left of the location log, so every row's numbers line up. */
    val logTime: Dp = 52.dp,
)

/**
 * Haptics behind one switch. Every toggle, tick and confirm in the app goes through here so the
 * "vibration on touch" setting is honoured without each call site checking it.
 */
class Haptics(private val delegate: HapticFeedback, val enabled: Boolean) {
    fun perform(type: HapticFeedbackType) {
        if (enabled) delegate.performHapticFeedback(type)
    }
}

val LocalSpacing = staticCompositionLocalOf { Spacing() }
val LocalMotion = staticCompositionLocalOf { Motion() }
val LocalSizes = staticCompositionLocalOf { Sizes() }
val LocalStrokes = staticCompositionLocalOf { Strokes() }

/**
 * Whether the app is rendering dark right now. The colour scheme cannot answer this: the trigger
 * family colours are not Material roles, and `isSystemInDarkTheme()` at the point of use would
 * ignore the person's own Light/Dark choice.
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

val LocalHaptics = staticCompositionLocalOf<Haptics> {
    error("No Haptics in scope: the composable is not inside RwilcoTheme")
}

/** Convenient token access from any composable. */
object Tokens {
    val spacing: Spacing
        @Composable get() = LocalSpacing.current
    val motion: Motion
        @Composable get() = LocalMotion.current
    val sizes: Sizes
        @Composable get() = LocalSizes.current
    val strokes: Strokes
        @Composable get() = LocalStrokes.current
    val haptics: Haptics
        @Composable get() = LocalHaptics.current
}
