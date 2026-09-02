package dev.rwilco.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.rwilco.R

/**
 * The one sentence a fresh install says before Android's own notification dialog (0.68.0):
 * the first thing the app ever showed used to be a system prompt with no word from the app
 * about why. Not dismissable around: the answer is the dialog's, and "Entendido" is the only
 * way there.
 */
@Composable
fun NotificationsRationaleDialog(onContinue: () -> Unit) {
    AlertDialog(
        onDismissRequest = onContinue,
        title = { Text(stringResource(R.string.perm_notifications_rationale_title)) },
        text = { Text(stringResource(R.string.perm_notifications_rationale_body), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.common_got_it)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
    )
}
