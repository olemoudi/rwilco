package dev.rwilco.ui.settings

import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.rwilco.R

/**
 * "Empty this?" — for the two logs, which have no undo: what a log said is gone once it is
 * cleared, and a bin that sits next to Back in a top bar is one slip from the report somebody
 * came to copy. The same dialog the done list asks with before it is purged.
 */
@Composable
fun ClearDialog(
    @StringRes titleRes: Int,
    @StringRes bodyRes: Int,
    @StringRes confirmRes: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(stringResource(bodyRes)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(confirmRes), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.sheet_cancel)) }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
    )
}
