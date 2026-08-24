package dev.rwilco.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Dark first. The ground is a deep blue-black, not pure black, so hairlines and the lamp's glow
 * have something to sit on; surfaces step up from it in five even increments. Amber is the only
 * saturated colour the scheme owns and it means one thing: what fires next.
 *
 * The light scheme mirrors it on paper white: same amber family darkened to hold contrast on
 * white, same neutral secondary so chips stay quiet in both.
 */

private val Amber = Color(0xFFFFB454)
private val AmberDeep = Color(0xFFB86E00)
private val OnAmber = Color(0xFF2A1B00)
private val AmberContainerDark = Color(0xFF3D2A05)
private val AmberContainerLight = Color(0xFFFFE1B3)

val RwilcoDarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = OnAmber,
    primaryContainer = AmberContainerDark,
    onPrimaryContainer = AmberContainerLight,
    inversePrimary = AmberDeep,
    secondary = Color(0xFF98A2B3),
    onSecondary = Color(0xFF0C1117),
    // The same step as surfaceContainerHigh so a selected chip is a raised neutral, not a colour.
    secondaryContainer = Color(0xFF1C242E),
    onSecondaryContainer = Color(0xFFECEFF4),
    tertiary = Color(0xFF5DB7FF),
    onTertiary = Color(0xFF00243D),
    tertiaryContainer = Color(0xFF0E3A5C),
    onTertiaryContainer = Color(0xFFCFE9FF),
    background = Color(0xFF0C1117),
    onBackground = Color(0xFFECEFF4),
    surface = Color(0xFF0C1117),
    onSurface = Color(0xFFECEFF4),
    surfaceVariant = Color(0xFF1C242E),
    onSurfaceVariant = Color(0xFF98A2B3),
    surfaceTint = Amber,
    inverseSurface = Color(0xFFECEFF4),
    inverseOnSurface = Color(0xFF151B23),
    error = Color(0xFFFF6B66),
    onError = Color(0xFF3A0907),
    errorContainer = Color(0xFF5C1A17),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF3A4556),
    // Lighter than the surface it edges by a clear step: cards carry no shadow, only this line.
    outlineVariant = Color(0xFF2A3441),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2B3542),
    surfaceDim = Color(0xFF0C1117),
    surfaceContainerLowest = Color(0xFF080C10),
    surfaceContainerLow = Color(0xFF10161D),
    surfaceContainer = Color(0xFF151B23),
    surfaceContainerHigh = Color(0xFF1C242E),
    surfaceContainerHighest = Color(0xFF232C38),
)

val RwilcoLightColors = lightColorScheme(
    primary = AmberDeep,
    onPrimary = Color.White,
    primaryContainer = AmberContainerLight,
    onPrimaryContainer = OnAmber,
    inversePrimary = Amber,
    secondary = Color(0xFF5B6472),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9EDF3),
    onSecondaryContainer = Color(0xFF131820),
    tertiary = Color(0xFF0B6BCB),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6EAFF),
    onTertiaryContainer = Color(0xFF00243D),
    background = Color(0xFFF3F5F8),
    onBackground = Color(0xFF131820),
    surface = Color(0xFFF3F5F8),
    onSurface = Color(0xFF131820),
    surfaceVariant = Color(0xFFE9EDF3),
    onSurfaceVariant = Color(0xFF5B6472),
    surfaceTint = AmberDeep,
    inverseSurface = Color(0xFF2B3542),
    inverseOnSurface = Color(0xFFF3F5F8),
    error = Color(0xFFD3403B),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFFC3CAD4),
    outlineVariant = Color(0xFFDDE3EB),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDCE1E8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFCFD),
    // White cards on the paper-grey ground: the light scheme's raised step is "whiter", not darker.
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE9EDF3),
    surfaceContainerHighest = Color(0xFFE1E6ED),
)
