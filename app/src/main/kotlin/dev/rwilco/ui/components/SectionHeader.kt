package dev.rwilco.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.MonoStyles

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
            // A heading, at a heading's size. It used to be titleSmall in the muted grey, which
            // on a screen of full-contrast cards read as a caption of the card above it rather
            // than as the name of the band below: "Vencidos 1" was the least visible thing on
            // Home and it is the one somebody is looking for.
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (info != null) InfoBadge(info, title = title)
        if (trailing != null) {
            // The count in a pill of its own, so it reads as a tally rather than as a word that
            // got left at the end of the heading.
            Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(
                    text = trailing,
                    // A number, so mono: it is a tally somebody scans down the screen.
                    style = MonoStyles.tally,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Tokens.spacing.sm, vertical = 2.dp),
                )
            }
        }
    }
}
