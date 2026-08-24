package dev.rwilco.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.TagLabel
import dev.rwilco.ui.theme.Tokens

/** Lets the instrumented tour type into the search field. */
const val HOME_SEARCH_TAG = "homeSearch"

/**
 * The field that replaces the Home header while searching. It takes the focus on open — here a
 * keyboard is exactly what was asked for, unlike in the editor.
 */
@Composable
fun SearchField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.home_search_hint)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.home_search_clear))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.outline,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier
                .weight(1f)
                .padding(start = Tokens.spacing.xs)
                .focusRequester(focusRequester)
                .testTag(HOME_SEARCH_TAG),
        )
    }
}

/**
 * One result. What it is — a reminder or a tag — is said twice on purpose: by the icon, and by
 * the word in the corner, because the two do different things when tapped.
 */
@Composable
fun SearchResultRow(
    hit: SearchHitUi,
    onOpen: (String) -> Unit,
    onFilterByTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (hit) {
        is SearchHitUi.OfReminder -> ResultCard(
            icon = { Icon(Icons.AutoMirrored.Outlined.Notes, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
            title = hit.text,
            subtitle = hit.tags.take(3).joinToString(" · ").takeIf { it.isNotEmpty() },
            kind = stringResource(R.string.home_search_kind_reminder),
            onClick = { onOpen(hit.id) },
            modifier = modifier,
        )
        is SearchHitUi.OfTag -> ResultCard(
            icon = { Icon(Icons.Outlined.LocalOffer, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
            title = hit.tag,
            subtitle = pluralStringResource(R.plurals.home_search_tag_count, hit.count, hit.count),
            kind = stringResource(R.string.home_search_kind_tag),
            onClick = { onFilterByTag(hit.tag) },
            modifier = modifier,
        )
    }
}

@Composable
private fun ResultCard(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    kind: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RwilcoCard(onClick = onClick, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Tokens.spacing.md, vertical = Tokens.spacing.md),
        ) {
            icon()
            Spacer(Modifier.width(Tokens.spacing.md))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.spacing.xs)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(Tokens.spacing.sm))
            TagLabel(kind)
        }
    }
}
