package dev.rwilco.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.annotation.StringRes
import dev.rwilco.R
import dev.rwilco.ui.theme.Tokens

/**
 * Mending the lists the editor offers back.
 *
 * The tags and the phrases on offer are read off everything ever written, so they inherit
 * every typo and every abandoned wording. This is where those get fixed: rename one and it is
 * renamed on the reminders that carry it; remove one and it stops being offered.
 *
 * Reached by holding one of the chips, because it is the rare thing: the everyday act is
 * tapping one to use it, and that must stay a tap.
 */
@Composable
fun CuratePanel(
    title: String,
    items: List<String>,
    removeLabel: String,
    /** The question asked before a removal, with the item in it, and the sentence under it. */
    @StringRes removeTitleRes: Int,
    @StringRes removeBodyRes: Int,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    // Removing rewrites every reminder that carries a tag, and a phrase hidden has no door back
    // through: neither has an undo, so both ask. A rename does not — rename it back.
    var removing by rememberSaveable { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = scheme.surfaceContainer,
            border = BorderStroke(Tokens.strokes.edge, scheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(spacing.lg)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(spacing.md))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    items(items, key = { it }) { item ->
                        if (editing == item) {
                            RenameRow(
                                initial = item,
                                onCancel = { editing = null },
                                onConfirm = { renamed ->
                                    editing = null
                                    if (renamed.isNotBlank() && renamed != item) onRename(item, renamed)
                                },
                            )
                        } else {
                            CurateRow(
                                item = item,
                                removeLabel = removeLabel,
                                onEdit = { editing = item },
                                onRemove = { removing = item },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(spacing.sm))
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurface),
                    modifier = Modifier
                        .align(Alignment.End)
                        .heightIn(min = Tokens.sizes.touch),
                ) { Text(stringResource(R.string.common_done)) }
            }
        }
    }
    removing?.let { item ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(removeTitleRes, item)) },
            text = { Text(stringResource(removeBodyRes)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    onRemove(item)
                }) { Text(stringResource(R.string.curate_remove_confirm), color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(R.string.sheet_cancel)) }
            },
            containerColor = scheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge,
        )
    }
}

@Composable
private fun CurateRow(item: String, removeLabel: String, onEdit: () -> Unit, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = item,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.curate_rename), tint = scheme.onSurfaceVariant)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = removeLabel, tint = scheme.onSurfaceVariant)
        }
    }
}

/** The row turned into a field, in place: renaming is a small act and does not deserve a screen. */
@Composable
private fun RenameRow(initial: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    // Focused and selected whole on arrival (0.68.0): a rename is a retype, and the field
    // used to open with the keyboard down and the cursor nowhere.
    var text by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = scheme.outline,
                unfocusedBorderColor = scheme.outlineVariant,
                cursorColor = scheme.primary,
            ),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
        )
        IconButton(onClick = { onConfirm(text.text.trim()) }) {
            Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.curate_rename_confirm), tint = scheme.onSurface)
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.sheet_cancel)) }
    }
}
