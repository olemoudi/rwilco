package dev.rwilco.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.rwilco.model.Action
import dev.rwilco.model.TriggerFamily
import dev.rwilco.model.TriggerKind

/**
 * The trigger families' colours live outside the Material scheme: they are recognition colours,
 * assigned by meaning and reused on every screen, not roles a component could restyle.
 */
fun familyColor(family: TriggerFamily, dark: Boolean): Color = when (family) {
    TriggerFamily.TIME -> if (dark) Color(0xFF5DB7FF) else Color(0xFF0B6BCB)
    TriggerFamily.PLACE -> if (dark) Color(0xFF5CD08A) else Color(0xFF1B7F4B)
    TriggerFamily.CHANCE -> if (dark) Color(0xFFB39DFF) else Color(0xFF6A4FD8)
}

@Composable
fun TriggerFamily.color(): Color = familyColor(this, LocalDarkTheme.current)

/**
 * The same colour as a wash, for the keycap behind a trigger's icon. Stronger on the dark
 * scheme, and not as a matter of taste: the same alpha over a near-black surface lands a
 * fraction of the contrast it lands over white.
 */
@Composable
fun TriggerFamily.tint(): Color = color().copy(alpha = if (LocalDarkTheme.current) 0.22f else 0.14f)

/**
 * Text or a glyph on a solid family fill (a day toggle that is on). The family colours are
 * light on the dark scheme and dark on the light one, so the lowest surface — near-black
 * there, white here — is the one that reads on both.
 */
@Composable
fun TriggerFamily.onColor(): Color = MaterialTheme.colorScheme.surfaceContainerLowest

/**
 * The wash behind a trigger's own row in the editor, and the line around it: enough colour to
 * say which family the row belongs to from across the room, not enough to fight the words.
 */
@Composable
fun TriggerFamily.wash(): Color = color().copy(alpha = if (LocalDarkTheme.current) 0.10f else 0.07f)

@Composable
fun TriggerFamily.edge(): Color = color().copy(alpha = if (LocalDarkTheme.current) 0.55f else 0.45f)

val TriggerKind.icon: ImageVector
    get() = when (this) {
        TriggerKind.DATE_TIME -> Icons.Outlined.Event
        TriggerKind.DATE -> Icons.Outlined.CalendarToday
        TriggerKind.REPEAT_TIME -> Icons.Outlined.Repeat
        TriggerKind.INTERVAL -> Icons.Outlined.HourglassEmpty
        TriggerKind.COUNTDOWN -> Icons.Outlined.HourglassTop
        TriggerKind.PLACE -> Icons.Outlined.Place
        TriggerKind.RANDOM -> Icons.Outlined.Casino
    }

val Action.icon: ImageVector
    get() = when (this) {
        Action.FULL_SCREEN -> Icons.Outlined.OpenInFull
        // Two stacked banners, not a bell: what this action does is drop a card down from the
        // top of the screen, and the bell was saying "an alarm" — which is every action here.
        Action.NOTIFICATION -> Icons.Outlined.ViewAgenda
        Action.SOUND -> Icons.AutoMirrored.Outlined.VolumeUp
        Action.VIBRATE -> Icons.Outlined.Vibration
    }
