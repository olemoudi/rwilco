package dev.rwilco.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.model.kind
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.TriggerKeycap
import dev.rwilco.ui.format.conditionLabel
import dev.rwilco.ui.format.triggerLine
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.icon
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun ReminderCard(
    card: ReminderCardUi,
    today: LocalDate,
    defaultTime: LocalTime,
    onClick: () -> Unit,
    onTogglePause: () -> Unit,
) {
    val spacing = Tokens.spacing
    val textColor = if (card.paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    RwilcoCard(onClick = onClick) {
        // Tight on purpose: a card is a glance, not a page. Two lines of text, the triggers
        // under it, and the footer riding on the pause button's own touch target.
        Column(modifier = Modifier.padding(start = spacing.md, top = spacing.md, end = spacing.xs)) {
            Text(
                text = card.text,
                style = MaterialTheme.typography.titleMedium,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = spacing.md),
            )
            Spacer(Modifier.height(spacing.sm))
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                for (row in card.triggers) TriggerRow(row, today, defaultTime, muted = card.paused)
            }
            CardFooter(
                tags = card.tags,
                actions = card.actions,
                modifier = Modifier.fillMaxWidth(),
                trailing = {
                    IconButton(onClick = onTogglePause, modifier = Modifier.size(Tokens.sizes.touch)) {
                        Icon(
                            imageVector = if (card.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                            contentDescription = stringResource(if (card.paused) R.string.card_resume else R.string.card_pause),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }
}

@Composable
fun TriggerRow(row: TriggerRowUi, today: LocalDate, defaultTime: LocalTime, muted: Boolean = false) {
    val line = triggerLine(row.trigger, today, defaultTime)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TriggerKeycap(family = row.family, icon = row.trigger.kind.icon, contentDescription = null, size = 28.dp)
        Spacer(Modifier.width(Tokens.spacing.sm))
        Column {
            Text(
                text = line.primary,
                style = if (line.primaryMono) MonoStyles.label else MaterialTheme.typography.titleSmall,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // An empty second line would still cost its line height on every card.
            if (line.secondary.isNotEmpty()) {
                Text(
                    text = line.secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.conditions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.editor_only_if_prefix, row.conditions.map { conditionLabel(it) }.joinToString(" · ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
