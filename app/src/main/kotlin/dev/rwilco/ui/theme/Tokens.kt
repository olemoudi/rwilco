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
)

/**
 * Touch sizes. [touch] is the Material floor; [control] is what a control a thumb reaches for
 * gets; [primary] is the one button a screen is about (Save, Done).
 */
data class Sizes(
    val touch: Dp = 48.dp,
    val control: Dp = 56.dp,
    val primary: Dp = 64.dp,
    val keycap: Dp = 36.dp,
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
    val haptics: Haptics
        @Composable get() = LocalHaptics.current
}
