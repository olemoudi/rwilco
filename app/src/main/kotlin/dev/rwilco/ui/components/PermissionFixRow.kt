package dev.rwilco.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.rwilco.ui.theme.Tokens

/**
 * A missing permission named in plain words, with the one button that fixes it. [quiet] for
 * the ones that change how a reminder appears rather than whether it arrives: the same row in
 * the ordinary ink, because refusing one of those is a choice and not a fault.
 */
@Composable
fun PermissionFixRow(text: String, action: String, quiet: Boolean = false, onFix: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Tokens.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onFix) { Text(action) }
    }
}
