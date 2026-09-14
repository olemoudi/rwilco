package dev.rwilco.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.rwilco.ui.theme.Tokens

/**
 * A missing permission named in plain words, with the one button that fixes it. [quiet] for
 * the ones that change how a reminder appears rather than whether it arrives: the same row in
 * the ordinary ink, because refusing one of those is a choice and not a fault.
 *
 * The button sits under the words, at the end, the way a banner carries its action — never
 * beside them. Beside them, a long label ("sonar incluso en silencio total") took most of the
 * width and left the sentence in a column nine lines tall next to a row that read in three.
 */
@Composable
fun PermissionFixRow(text: String, action: String, quiet: Boolean = false, onFix: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Tokens.spacing.md),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onFix) { Text(action) }
        }
    }
}
