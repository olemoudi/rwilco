package dev.rwilco.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.rwilco.R
import dev.rwilco.ui.components.TagNameDialog
import dev.rwilco.ui.components.scrollFade
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.tagColor
import dev.rwilco.ui.theme.tagWash

/**
 * The one place tags are administered, behind the "+" at the end of Home's row of chips.
 *
 * The same door the presets have, in the same place, doing the same four things: it says which
 * tags lead the row, it renames one, it removes one, and it writes down a new one. Renaming and
 * removing reach every reminder carrying the tag, which is why removing asks first — and
 * renaming does not: rename it back.
 *
 * A tag written down here wears nothing yet, so it gets no chip on Home (a filter that finds
 * nothing is not a filter). It is offered by the editor from the moment it exists, which is the
 * whole point of writing one down before the reminder that needs it.
 */
@Composable
fun TagsPanel(
    tags: List<TagRow>,
    onTogglePin: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var naming by rememberSaveable { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = scheme.surfaceContainer,
            border = BorderStroke(Tokens.strokes.edge, scheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = Tokens.sizes.dialogMax),
        ) {
            Column(modifier = Modifier.padding(spacing.lg)) {
                Text(stringResource(R.string.curate_tags_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(spacing.sm))
                Text(
                    text = stringResource(R.string.home_tags_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(spacing.md))
                if (tags.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_tags_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(spacing.md))
                }
                // The same edge the presets' panel wears: rows that end flush with the bottom
                // read as a list that finishes there. See Modifier.scrollFade.
                val scroll = rememberScrollState()
                Column(
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .scrollFade(scroll, scheme.surfaceContainer)
                        .verticalScroll(scroll),
                ) {
                    for (tag in tags) {
                        if (editing == tag.name) {
                            RenameTagRow(
                                initial = tag.name,
                                onCancel = { editing = null },
                                onConfirm = { renamed ->
                                    editing = null
                                    if (renamed.isNotBlank() && renamed != tag.name) onRename(tag.name, renamed)
                                },
                            )
                        } else {
                            TagPanelRow(
                                tag = tag,
                                onTogglePin = { onTogglePin(tag.name) },
                                onEdit = { editing = tag.name },
                                onRemove = { removing = tag.name },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { naming = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurface),
                        modifier = Modifier.heightIn(min = Tokens.sizes.touch),
                    ) {
                        Icon(Icons.Outlined.LocalOffer, contentDescription = null, modifier = Modifier.size(Tokens.sizes.glyphSmall))
                        Spacer(Modifier.width(spacing.sm))
                        Text(stringResource(R.string.editor_new_tag))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurface),
                        modifier = Modifier.heightIn(min = Tokens.sizes.touch),
                    ) { Text(stringResource(R.string.common_close)) }
                }
            }
        }
    }
    if (naming) {
        TagNameDialog(
            onConfirm = { name ->
                naming = false
                onCreate(name)
            },
            onDismiss = { naming = false },
        )
    }
    removing?.let { tag ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.curate_tag_remove_title, tag)) },
            text = { Text(stringResource(R.string.curate_tag_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    onDelete(tag)
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

/**
 * A tag's row: its own colour, its name, and the two rarer things you can do to it.
 *
 * The row itself is the pin, inverted when on the way every other "on" in the app is — and
 * said as well as shown, because an inversion is not a word: a checkbox to a screen reader,
 * and a check glyph for anybody the colour alone does not reach.
 */
@Composable
private fun TagPanelRow(tag: TagRow, onTogglePin: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    val color = tagColor(tag.name)
    Surface(
        onClick = onTogglePin,
        shape = MaterialTheme.shapes.small,
        color = if (tag.pinned) scheme.onSurface else tagWash(tag.name),
        border = if (tag.pinned) null else BorderStroke(Tokens.strokes.control, color.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.sizes.touch)
            .semantics {
                contentDescription = tag.name
                role = Role.Checkbox
                toggleableState = if (tag.pinned) ToggleableState.On else ToggleableState.Off
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = spacing.md, top = spacing.xs, bottom = spacing.xs),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tag.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (tag.pinned) scheme.surface else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Why this one is here and not in the row of chips outside. Without the line it
                // is a tag that ignores being pinned and never appears, which reads as a fault.
                if (!tag.onHome) {
                    Text(
                        text = stringResource(R.string.home_tags_done_only),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (tag.pinned) scheme.surface.copy(alpha = 0.75f) else scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (tag.pinned) {
                Spacer(Modifier.width(spacing.sm))
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = scheme.surface,
                    modifier = Modifier.size(Tokens.sizes.glyph),
                )
            }
            val ink = if (tag.pinned) scheme.surface else scheme.onSurfaceVariant
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.curate_rename), tint = ink)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.curate_tag_remove), tint = ink)
            }
        }
    }
}

/** The row turned into a field, in place: renaming is a small act and does not deserve a screen. */
@Composable
private fun RenameTagRow(initial: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    // Focused and selected whole on arrival: a rename is a retype.
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
