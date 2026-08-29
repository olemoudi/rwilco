package dev.rwilco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import dev.rwilco.ui.theme.Tokens

/**
 * An empty screen is an invitation, not a mood. The [icon], when there is one, sits in a big
 * keycap above the words, so the screen has a shape before it has a sentence. An [actionLabel]
 * puts the invitation on a button under the words, so it can be taken from where it is read.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val sizes = Tokens.sizes
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.spacing.xl, vertical = Tokens.spacing.xxl * 2),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(sizes.control)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(sizes.control / 3)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(sizes.control / 2),
                )
            }
            Spacer(Modifier.height(Tokens.spacing.sm))
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Start)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Tokens.spacing.md))
            Button(
                onClick = onAction,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.heightIn(min = sizes.control),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
