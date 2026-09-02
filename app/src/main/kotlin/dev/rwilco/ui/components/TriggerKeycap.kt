package dev.rwilco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
        modifier = modifier.size(Tokens.sizes.glyph),
    )
}
