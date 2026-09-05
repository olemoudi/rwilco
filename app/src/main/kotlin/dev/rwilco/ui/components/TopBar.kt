package dev.rwilco.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.rwilco.R
import dev.rwilco.ui.theme.Tokens

/**
 * The top row of every screen under Home: Back, the title, and at most one action.
 *
 * One composable (0.94.0) where there were five copies, two of which set the title without
 * `weight(1f)` — so at a large font scale "Copia de seguridad" ran off the row with no ellipsis
 * while the other three trimmed theirs. The title is a heading to a screen reader, which the
 * group headings inside Settings already were: walking by headings landed on "Avisos" with no
 * "Ajustes" above it.
 */
@Composable
fun RwilcoTopBar(title: String, onBack: () -> Unit, action: (@Composable () -> Unit)? = null) {
    val spacing = Tokens.spacing
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = spacing.sm)
                .heightIn(min = Tokens.sizes.control),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = spacing.sm)
                    .semantics { heading() },
            )
            action?.invoke()
        }
    }
}
