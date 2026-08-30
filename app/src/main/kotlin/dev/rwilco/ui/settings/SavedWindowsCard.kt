package dev.rwilco.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rwilco.ui.components.LocalSnackbar
import dev.rwilco.R
import dev.rwilco.model.SavedWindow
import dev.rwilco.model.TriggerFamily
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.TimeField
import dev.rwilco.ui.components.TriggerKeycap
import dev.rwilco.ui.editor.sheets.rememberTime
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.rememberIs24h
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.edge
import dev.rwilco.ui.theme.wash
import java.time.LocalTime

/**
 * The stretches of the day kept by name: "a la hora de comer", "por la tarde", "de noche".
 *
 * The same shape as the saved places and for the same reason — a thing you answer over and over
 * is worth answering once — and the same rule about what it is worth: nothing that uses one
 * keeps a reference to it, so renaming one here never reaches back into a reminder written with
 * it. Tap a row to change it, the bin to forget it.
 */
@Composable
fun SavedWindowsCard(
    windows: List<SavedWindow>,
    onSave: (Int?, SavedWindow) -> Unit,
    onRemove: (Int) -> Unit,
    /** The undo: the window back at the index it was removed from. */
    onRestore: (Int, SavedWindow) -> Unit,
) {
    val spacing = Tokens.spacing
    val family = TriggerFamily.TIME
    val snackbar = LocalSnackbar.current
    val removedMessage = stringResource(R.string.settings_window_removed)
    val undoLabel = stringResource(R.string.common_undo)
    val is24h = rememberIs24h()
    val locale = currentLocale()
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    RwilcoCard {
        Column(Modifier.padding(spacing.lg)) {
            Text(
                text = stringResource(R.string.settings_windows_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.md))
            if (windows.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_windows_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(spacing.md))
            }
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                windows.forEachIndexed { index, saved ->
                    // The same wash and edge a time rule wears in the editor: it is the same thing.
                    Surface(
                        onClick = { editing = index },
                        shape = MaterialTheme.shapes.medium,
                        color = family.wash(),
                        border = BorderStroke(Tokens.strokes.control, family.edge()),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = spacing.md, top = spacing.sm, bottom = spacing.sm),
                        ) {
                            TriggerKeycap(family = family, icon = Icons.Outlined.Timelapse, contentDescription = null)
                            Spacer(Modifier.width(spacing.md))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = saved.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = TimeText.window(saved.from, saved.to, is24h, locale),
                                    style = MonoStyles.date,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                onRemove(index)
                                snackbar.show(removedMessage, undoLabel) { onRestore(index, saved) }
                            }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.settings_remove_window),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (windows.isNotEmpty()) Spacer(Modifier.height(spacing.md))
            OutlinedButton(
                onClick = { adding = true },
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(Tokens.strokes.control, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                contentPadding = PaddingValues(horizontal = spacing.lg),
                modifier = Modifier.heightIn(min = Tokens.sizes.touch),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(spacing.sm))
                Text(stringResource(R.string.settings_add_window), style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    val index = editing
    if (adding || index != null) {
        WindowDialog(
            initial = index?.let { windows.getOrNull(it) },
            onDismiss = { adding = false; editing = null },
            onSave = {
                onSave(index, it)
                adding = false
                editing = null
            },
        )
    }
}

/** A name and two times, which is the whole of what a stretch of the day is. */
@Composable
private fun WindowDialog(initial: SavedWindow?, onDismiss: () -> Unit, onSave: (SavedWindow) -> Unit) {
    var label by rememberSaveable { mutableStateOf(initial?.label ?: "") }
    var from by rememberTime(initial?.from ?: LocalTime.of(14, 0))
    var to by rememberTime(initial?.to ?: LocalTime.of(16, 0))
    val spacing = Tokens.spacing
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_windows)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(NAME_MAX) },
                    label = { Text(stringResource(R.string.settings_window_name)) },
                    placeholder = { Text(stringResource(R.string.settings_window_name_hint)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    TimeField(time = from, onChange = { from = it }, label = stringResource(R.string.random_from), modifier = Modifier.weight(1f))
                    TimeField(time = to, onChange = { to = it }, label = stringResource(R.string.random_to), modifier = Modifier.weight(1f))
                }
                if (from == to) {
                    Text(
                        text = stringResource(R.string.condition_window_error),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(SavedWindow(label.trim(), from, to)) },
                enabled = label.isNotBlank() && from != to,
            ) { Text(stringResource(R.string.sheet_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sheet_cancel)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
    )
}

/** A name long enough to say what it is and short enough to fit on a chip. */
private const val NAME_MAX = 24
