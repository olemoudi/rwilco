package dev.rwilco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rwilco.model.TriggerFamily
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.color
import dev.rwilco.ui.theme.tint

/** The family-coloured square behind a trigger's icon: the thing a row is recognised by. */
@Composable
fun TriggerKeycap(
    family: TriggerFamily,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = Tokens.sizes.keycap,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(family.tint(), RoundedCornerShape(size / 3)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = family.color(),
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** A quieter keycap for the action glyphs: neutral, smaller. */
@Composable
fun ActionGlyph(icon: ImageVector, contentDescription: String, modifier: Modifier = Modifier) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(16.dp),
    )
}

/**
 * The safety net's mark: a filled disc in the theme's error red, with a glyph on it.
 *
 * **Filled and red on purpose.** It has to be found at a glance on a list of cards, and the
 * app's own bright colour is spoken for — amber means "this is what fires next" and nothing
 * else, so a net wearing it would make every card carrying one lie about what is due. Red is
 * the colour a thing that catches you is painted in everywhere else: the handle, the
 * extinguisher, the cord over the seat. Contrast is above 4.5:1 against the card in both
 * schemes (6.2:1 dark, 4.6:1 light) and the same again for the glyph on the disc, so it is
 * legible as a shape rather than only as a blob of colour.
 */
@Composable
fun SafetyNetMark(modifier: Modifier = Modifier) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(18.dp)
            .background(scheme.error, androidx.compose.foundation.shape.CircleShape),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.HealthAndSafety,
            contentDescription = androidx.compose.ui.res.stringResource(dev.rwilco.R.string.card_safety_net),
            tint = scheme.onError,
            modifier = Modifier.size(12.dp),
        )
    }
}
