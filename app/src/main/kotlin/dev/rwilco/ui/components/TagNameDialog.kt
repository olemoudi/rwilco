package dev.rwilco.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.rwilco.R
import dev.rwilco.model.tagsLike
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.tagColor

/** The field a new tag is typed into, for the instrumented tests. */
const val TAG_NAME_FIELD_TAG = "tagNameField"

/**
 * One question, asked outright: what is this tag called?
 *
 * A dialog rather than a field that unfolds where the "+" was, which is what this used to be.
 * The field had to be shut again by whatever the person did next — and a half-typed word left
 * in it was a tag lost on the way to "Guardar" (see the commit that took `pendingTag` out).
 * A dialog has one way in and two ways out, and neither of them can be taken by accident.
 *
 * **And it says what already exists while the name is typed** (0.133.0, [tagsLike]). The field
 * was bare, so "Compras" typed beside an existing "Compra" made a second tag for the same thing
 * with nobody deciding to — the spelling is only reused when it matches whole. The tags it is
 * turning into, or has just gone past, sit under the field in their own colours; [onPickExisting]
 * takes one instead of making another.
 */
@Composable
fun TagNameDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Every tag there is, most used first; empty where there is nothing to offer. */
    existing: List<String> = emptyList(),
    onPickExisting: (String) -> Unit = {},
) {
    var name by rememberSaveable { mutableStateOf("") }
    val alike = remember(existing, name) { tagsLike(existing, name) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    fun commit() {
        if (name.isNotBlank()) onConfirm(name.trim())
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = scheme.surfaceContainer,
            border = BorderStroke(Tokens.strokes.edge, scheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(modifier = Modifier.padding(spacing.lg)) {
                Text(stringResource(R.string.editor_new_tag), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(spacing.md))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(R.string.editor_new_tag_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    shape = MaterialTheme.shapes.small,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = scheme.outline,
                        unfocusedBorderColor = scheme.outlineVariant,
                        cursorColor = scheme.primary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_NAME_FIELD_TAG)
                        .focusRequester(focusRequester),
                )
                if (alike.isNotEmpty()) {
                    Spacer(Modifier.height(spacing.sm))
                    Text(
                        text = stringResource(R.string.editor_new_tag_exists),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(spacing.xs))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        for (tag in alike) {
                            TagChip(label = tag, selected = false, onClick = { onPickExisting(tag) }, tint = tagColor(tag))
                        }
                    }
                }
                Spacer(Modifier.height(spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurfaceVariant),
                        modifier = Modifier.heightIn(min = Tokens.sizes.control),
                    ) { Text(stringResource(R.string.sheet_cancel)) }
                    Button(
                        onClick = { commit() },
                        enabled = name.isNotBlank(),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.onSurface,
                            contentColor = scheme.surface,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Tokens.sizes.control),
                    ) { Text(stringResource(R.string.sheet_add), style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}
