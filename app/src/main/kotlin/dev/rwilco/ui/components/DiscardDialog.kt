package dev.rwilco.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.rwilco.R

@Composable
fun DiscardDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.editor_discard_title)) },
        text = { Text(stringResource(R.string.editor_discard_body)) },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.editor_discard_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text(stringResource(R.string.editor_discard_keep)) }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
    )
}
