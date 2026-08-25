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
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.model.Recurrence
import dev.rwilco.model.TriggerFamily
import dev.rwilco.model.kind
import dev.rwilco.ui.components.HoldButton
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.TriggerKeycap
import dev.rwilco.ui.editor.recurrenceLabel
import dev.rwilco.ui.editor.titleRes
import dev.rwilco.ui.format.conditionLabel
import dev.rwilco.ui.format.triggerLine
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.icon
import java.time.LocalDate
import java.time.LocalTime

/**
 * One reminder at a glance. [modifier] is where Home hangs the accessibility actions for the
 * swipes: a gesture is not a thing a screen reader can do, so Done and Delete are offered as
 * actions on the card itself.
 */
@Composable
fun ReminderCard(
    card: ReminderCardUi,
    today: LocalDate,
    defaultTime: LocalTime,
    onClick: () -> Unit,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = Tokens.spacing
    val textColor = if (card.paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    RwilcoCard(onClick = onClick, modifier = modifier) {
        // Tight on purpose: a card is a glance, not a page. Two lines of text with the one
        // control beside them, the triggers under it, and the read-only footer at the foot.
        Column(
            modifier = Modifier.padding(
                start = spacing.md,
                top = spacing.md,
                end = spacing.md,
                bottom = spacing.md,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.text,
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(spacing.sm))
                // The card's one control, up here with the words rather than lost among the
                // action glyphs below — which say what will happen and cannot be pressed.
                HoldButton(
                    icon = if (card.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    label = stringResource(if (card.paused) R.string.card_resume else R.string.card_pause),
                    onHoldComplete = onTogglePause,
                )
            }
            Spacer(Modifier.height(spacing.sm))
            if (card.matchAll) {
                Text(
                    text = stringResource(R.string.card_match_all),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = spacing.xs),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                for (row in card.triggers) TriggerRow(row, today, defaultTime, muted = card.paused)
                // Last, because that is the order the two answer in: the triggers say when it
                // rings the first time and the recurrence says when it comes back.
                card.recurrence?.let { RecurrenceRow(it, muted = card.paused) }
            }
            Spacer(Modifier.height(spacing.sm))
            CardFooter(
                tags = card.tags,
                actions = card.actions,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The recurrence as a row of its own, in the same language as the triggers above it.
 *
 * A reminder whose only arrangement is "cada 6 h" carries no trigger at all, so without this its
 * card said nothing about when it rings — the shape was real, armed and invisible. The second
 * line is the part people get wrong about it: the clock starts at the "hecho", not at the ring.
 */
@Composable
fun RecurrenceRow(recurrence: Recurrence, muted: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TriggerKeycap(
            family = TriggerFamily.TIME,
            icon = Icons.Outlined.Autorenew,
            contentDescription = stringResource(R.string.card_recurrence),
            size = Tokens.sizes.badge,
        )
        Spacer(Modifier.width(Tokens.spacing.sm))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = recurrenceLabel(recurrence),
                style = MaterialTheme.typography.titleSmall,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.card_recurrence_from_done),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun TriggerRow(row: TriggerRowUi, today: LocalDate, defaultTime: LocalTime, muted: Boolean = false) {
    val line = triggerLine(row.trigger, today, defaultTime)
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The keycap says which kind of "when" this is; sighted by colour and glyph, spoken by name.
        TriggerKeycap(
            family = row.family,
            icon = row.trigger.kind.icon,
            contentDescription = stringResource(row.trigger.kind.titleRes),
            size = Tokens.sizes.badge,
        )
        Spacer(Modifier.width(Tokens.spacing.sm))
        Column(modifier = Modifier.weight(1f, fill = false)) {
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
        // Under "all of them", the ones already behind us: what is left is what it is waiting for.
        if (row.fired) {
            Spacer(Modifier.width(Tokens.spacing.sm))
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = stringResource(R.string.card_rule_happened),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
