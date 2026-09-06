package dev.rwilco.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.rwilco.R
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.theme.Tokens

/**
 * Home's one line about the routines, and the door to them.
 *
 * The routines live on their own screen (see `Routines.kt`), and the owner asked for the way
 * there to be a row a thumb finds, not an icon in the header. So it is always here, under the
 * chips and over the hero, one row tall: with a routine whose span is up it turns the error
 * wash and names it — "todavía no has hecho lo siguiente: mover el coche" — and with nothing
 * owed it is the quiet neutral line that says where the routines are. Amber is never used:
 * it means what fires next, and this is a list of what has not been done.
 */
@Composable
fun RoutinesLine(line: RoutinesLineUi, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    val overdue = line.overdue.isNotEmpty()
    val ink = if (overdue) scheme.onErrorContainer else scheme.onSurfaceVariant
    val opens = stringResource(R.string.home_routines_open)
    RwilcoCard(
        onClick = onOpen,
        color = if (overdue) scheme.errorContainer else scheme.surfaceContainer,
        modifier = modifier.semantics { contentDescription = opens },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.sizes.touch)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            Icon(Icons.Outlined.Autorenew, contentDescription = null, tint = ink)
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                if (overdue) {
                    Text(
                        text = stringResource(R.string.home_routines_overdue_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = ink,
                    )
                    // Up to three by name, in the routine's own words and type, then a count:
                    // a row that lists eight is a section, and this is a line.
                    val named = line.overdue.take(NAMED)
                    val more = line.overdue.size - named.size
                    Text(
                        text = named.joinToString(stringResource(R.string.common_separator)) { it.text } +
                            if (more > 0) " " + pluralStringResource(R.plurals.home_routines_more, more, more) else "",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onErrorContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = stringResource(if (line.total == 0) R.string.home_routines_title else R.string.home_routines_ok),
                        style = MaterialTheme.typography.titleMedium,
                        color = ink,
                    )
                }
            }
            Spacer(Modifier.width(spacing.sm))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = ink)
        }
    }
}

/** How many overdue routines the line names before it counts the rest. */
private const val NAMED = 3
