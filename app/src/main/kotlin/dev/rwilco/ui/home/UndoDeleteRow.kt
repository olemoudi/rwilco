package dev.rwilco.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.rwilco.R
import dev.rwilco.ui.theme.Tokens

/**
 * "Eliminado: <words> · Deshacer", at the top of the list for a minute after a delete
 * ([HomeViewModel.pendingDelete]). The snackbar says the same thing for four seconds and is
 * replaced by the next one; this is the same undo where the list is, for as long as a mistake
 * takes to notice. Quiet — a card's surface and line, no colour of its own — because it is a
 * door held open, not an alarm.
 */
@Composable
fun UndoDeleteRow(text: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(Tokens.strokes.edge, scheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = Tokens.sizes.control)
                .padding(start = Tokens.spacing.md, end = Tokens.spacing.xs),
        ) {
            Icon(
                imageVector = Icons.Outlined.DeleteOutline,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(Tokens.sizes.glyph),
            )
            Spacer(Modifier.width(Tokens.spacing.sm))
            Text(
                text = stringResource(R.string.home_deleted_row, text),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUndo) {
                Text(stringResource(R.string.common_undo))
            }
        }
    }
}
