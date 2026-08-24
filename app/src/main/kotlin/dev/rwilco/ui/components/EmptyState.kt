package dev.rwilco.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.rwilco.ui.theme.Tokens

/** An empty screen is an invitation, not a mood. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.spacing.xl, vertical = Tokens.spacing.xxl * 2),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Start)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
