package dev.rwilco.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.rwilco.model.PRESET_COLORS

/*
 * The colours a preset can wear.
 *
 * This is the app's third and last colour job, and it is a different one from the other two:
 * amber says what fires next, the family colours say what kind of "when" a trigger is, and
 * these say nothing at all — they are there to be recognised. "The green one" is how a hand
 * finds the usual thing on a screen of usual things without reading any of them.
 *
 * Eight, because past that they stop being tellable apart at a glance, and none of them amber:
 * that one is spoken for. They are only ever shown on the preset buttons, where no trigger
 * colour is present to be confused with.
 */
private val PresetDark = listOf(
    Color(0xFF5DB7FF), // sky
    Color(0xFF5CD08A), // green
    Color(0xFFB39DFF), // violet
    Color(0xFFFF8FA3), // rose
    Color(0xFF4FD1C5), // teal
    Color(0xFFF2A65A), // clay
    Color(0xFFC3D96B), // lime
    Color(0xFFFF9CE8), // magenta
)

private val PresetLight = listOf(
    Color(0xFF0B6BCB),
    Color(0xFF1B7F4B),
    Color(0xFF6A4FD8),
    Color(0xFFC2185B),
    Color(0xFF00796B),
    Color(0xFFB35A1F),
    Color(0xFF5F7B14),
    Color(0xFFA3238E),
)

/** The colour itself, for a line or a glyph. Out-of-range indices wrap rather than crash. */
@Composable
fun presetColor(index: Int): Color {
    val palette = if (LocalDarkTheme.current) PresetDark else PresetLight
    return palette[Math.floorMod(index, PRESET_COLORS).coerceIn(palette.indices)]
}

/** The wash a preset button is filled with: the colour, quietly, so its name stays readable. */
@Composable
fun presetWash(index: Int): Color =
    presetColor(index).copy(alpha = if (LocalDarkTheme.current) 0.16f else 0.12f)

/** Text on that wash: the ordinary ink, because a coloured name on a coloured field is neither. */
@Composable
fun presetInk(): Color = MaterialTheme.colorScheme.onSurface
