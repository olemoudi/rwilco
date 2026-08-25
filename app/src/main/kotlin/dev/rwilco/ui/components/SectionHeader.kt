package dev.rwilco.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.rwilco.ui.theme.Tokens

/**
 * A quiet label above a group of cards; [accent] when the group is a state (overdue). A heading
 * to a screen reader too, so a list can be walked section by section instead of card by card.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: String? = null,
    /** What this section is for, when it needs saying: folded behind an (i). */
    info: String? = null,
) {
    Row(
        modifier = modifier.padding(top = Tokens.spacing.xl, bottom = Tokens.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = accent,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (info != null) InfoBadge(info)
        if (trailing != null) {
            Text(text = trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
