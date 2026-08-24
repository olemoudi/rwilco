package dev.rwilco.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.rwilco.model.ThemeMode

/** Whether this preference renders dark right now (SYSTEM follows the device). */
@Composable
fun ThemeMode.resolvesToDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * The app's theme: hand-authored colour schemes, the three bundled faces, our shapes, plus the
 * tokens and haptics every screen reads. Plain `MaterialTheme` on purpose — in material3 1.4.0
 * the "expressive" theme and motion scheme are still internal (public only in the 1.5 alphas),
 * so the expressive character lives in our own tokens and components instead.
 */
@Composable
fun RwilcoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    haptics: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) RwilcoDarkColors else RwilcoLightColors
    val hapticFeedback = LocalHapticFeedback.current
    val hapticsController = remember(hapticFeedback, haptics) { Haptics(hapticFeedback, haptics) }
    CompositionLocalProvider(
        LocalSpacing provides Spacing(),
        LocalMotion provides Motion(),
        LocalSizes provides Sizes(),
        LocalDarkTheme provides darkTheme,
        LocalHaptics provides hapticsController,
    ) {
        MaterialTheme(
            colorScheme = colors,
            shapes = RwilcoShapes,
            typography = RwilcoTypography,
            content = content,
        )
    }
}
