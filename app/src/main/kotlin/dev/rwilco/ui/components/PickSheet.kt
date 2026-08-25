package dev.rwilco.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.ui.theme.Tokens
import java.util.Locale

/** How many of anything reusable a row shows before the rest go behind the dots. */
const val VISIBLE_SUGGESTIONS = 5

/**
 * The dots at the end of a row of offers: everything that did not fit, behind one tap.
 *
 * A row that grows with every reminder ever written stops being a shortcut somewhere around the
 * sixth chip — it becomes a wall to read past on the way to the keyboard. Five is what a glance
 * takes; the rest are still there, with a search box, for the day you want the one from March.
 */
@Composable
fun MoreChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = Tokens.haptics
    val scheme = MaterialTheme.colorScheme
    AssistChip(
        onClick = {
            haptics.perform(HapticFeedbackType.Confirm)
            onClick()
        },
        label = {
            Icon(
                imageVector = Icons.Outlined.MoreHoriz,
                contentDescription = stringResource(R.string.reuse_more),
                modifier = Modifier.size(20.dp),
            )
        },
        shape = MaterialTheme.shapes.small,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = scheme.surfaceContainerHigh,
            labelColor = scheme.onSurface,
        ),
        border = BorderStroke(Tokens.strokes.control, scheme.outline),
        modifier = modifier.heightIn(min = 44.dp),
    )
}

/**
 * Everything reusable of one sort, searchable, one tap each.
 *
 * The search is plain contains, case- and accent-blind enough for a list of one person's own
 * words: what is being looked for here is a thing they wrote themselves and half remember.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickSheet(
    title: String,
    items: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    selected: Set<String> = emptySet(),
) {
    val spacing = Tokens.spacing
    val haptics = Tokens.haptics
    var query by rememberSaveable { mutableStateOf("") }
    val matching = remember(items, query) {
        val needle = query.trim().lowercase(Locale.getDefault())
        if (needle.isEmpty()) items else items.filter { it.lowercase(Locale.getDefault()).contains(needle) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = spacing.screen)
                .navigationBarsPadding()
                .padding(bottom = spacing.xl),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(spacing.md))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.reuse_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.md))
            if (matching.isEmpty()) {
                Text(
                    text = stringResource(R.string.reuse_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = spacing.lg),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    // Tall enough to be a list, short enough that the search box stays put.
                    modifier = Modifier.heightIn(max = 420.dp),
                ) {
                    items(matching, key = { it }) { item ->
                        val on = item in selected
                        Surface(
                            onClick = {
                                haptics.perform(HapticFeedbackType.Confirm)
                                onPick(item)
                            },
                            shape = MaterialTheme.shapes.small,
                            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                            border = if (on) null else BorderStroke(Tokens.strokes.control, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Box(
                                contentAlignment = Alignment.CenterStart,
                                modifier = Modifier
                                    .heightIn(min = Tokens.sizes.touch)
                                    .padding(horizontal = spacing.lg, vertical = spacing.sm),
                            ) {
                                Text(
                                    text = item,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
