package dev.rwilco.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.ui.theme.Tokens

/**
 * The explanation, folded away.
 *
 * A settings screen where every row carries two lines of grey prose reads as a manual, and the
 * prose is needed once — the first time. So it lives behind an (i) beside the title, one tap
 * from anybody who wants it and invisible to everybody who does not.
 */
@Composable
fun InfoBadge(text: String, modifier: Modifier = Modifier, title: String? = null) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(
        onClick = { open = true },
        modifier = modifier.size(Tokens.sizes.touch),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
            // "¿Qué es «Vibración»?", not the same "¿Qué es esto?" on every row of the screen.
            contentDescription = if (title != null) stringResource(R.string.common_what_is_this_named, title) else stringResource(R.string.common_what_is_this),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Tokens.sizes.glyphSmall),
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = title?.let { { Text(it) } },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_got_it)) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge,
        )
    }
}
