package dev.rwilco.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.rwilco.R
import dev.rwilco.ui.components.holdable
import dev.rwilco.ui.components.rememberHoldState
import dev.rwilco.ui.theme.Tokens

/**
 * The door to saying a reminder (0.176.0): the whole width at the top of Home, a mic and the
 * words, at the height of the screen's primary action — the owner asked for a row a thumb finds,
 * as with the routines, and not an icon somewhere.
 *
 * **Held, not tapped**, for exactly as long as "hecho" ([dev.rwilco.ui.components.HOLD_MILLIS],
 * through the same ring): it opens a microphone, and a thumb brushing the top of a list on its
 * way to scrolling it must not. A finger that moves is a scroll and not a hold ([holdable]). A
 * screen reader gets an ordinary click, as every hold in the app gives it.
 *
 * Neutral, never amber: amber is what fires next.
 */
@Composable
fun VoiceDoor(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.home_dictate)
    val hold = rememberHoldState()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
        border = BorderStroke(Tokens.strokes.edge, scheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .holdable(Icons.Outlined.Mic, label, onOpen, hold)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
                onClick {
                    onOpen()
                    true
                }
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = Tokens.sizes.primary)
                .padding(horizontal = spacing.lg, vertical = spacing.md),
        ) {
            Icon(Icons.Outlined.Mic, contentDescription = null, tint = scheme.onSurface)
            Spacer(Modifier.width(spacing.md))
            // The verb, and under it how: a title that wrapped onto two lines read as a paragraph.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.shortcut_dictate_short),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.home_dictate_hold),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}
