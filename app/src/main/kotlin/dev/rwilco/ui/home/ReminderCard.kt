package dev.rwilco.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.model.RuleStanding
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
import dev.rwilco.ui.theme.LocalDarkTheme
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.familyColor
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
            if (card.matchLabel != null) {
                Text(
                    text = stringResource(card.matchLabel),
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
        // The keycap says which kind of "when" this is; sighted by colour and glyph, spoken by
        // name — and wearing, in its corner, where this rule stands in its set.
        Box {
            TriggerKeycap(
                family = row.family,
                icon = row.trigger.kind.icon,
                contentDescription = stringResource(row.trigger.kind.titleRes),
                size = Tokens.sizes.badge,
            )
            row.standing?.let { standing ->
                StandingDot(
                    standing = standing,
                    muted = muted,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = DOT_OUT, y = -DOT_OUT),
                )
            }
        }
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
    }
}

/**
 * Where a rule stands, worn in the corner of its own keycap.
 *
 * Small enough to be read as a property of the icon rather than as a thing of its own, so what
 * carries the meaning is fill: solid for a rule that is met, hollow for one that is not. The
 * ring of card colour around it is what keeps it from smudging into the keycap.
 *
 * The one that is neither is the exception, and it is the only one that gets a shape of its
 * own: a rule nobody has been able to check yet — a place with no fix behind it — wears a
 * pause. Two bars are legible at this size where a glyph is not, they are the one thing on a
 * card that says "waiting" without saying yes or no, and being the odd shape out is the point:
 * it is the odd state out.
 */
@Composable
private fun StandingDot(standing: RuleStanding, muted: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(standing.labelRes)
    val met = standing == RuleStanding.DONE || standing == RuleStanding.HOLDING
    val ink = when {
        met && !muted -> familyColor(TriggerFamily.PLACE, LocalDarkTheme.current)
        met -> scheme.onSurfaceVariant
        // onSurfaceVariant, not outline: on the dark scheme a hairline in the outline colour
        // over a dark keycap is a smudge, and the mark has to be readable to mean anything.
        else -> scheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .size(DOT + HALO * 2)
            .background(scheme.surfaceContainer, CircleShape)
            .padding(HALO),
        contentAlignment = Alignment.Center,
    ) {
        if (standing == RuleStanding.UNKNOWN) {
            Canvas(
                modifier = Modifier
                    .size(DOT)
                    .semantics { contentDescription = label },
            ) {
                // Drawn rather than an icon: the Material pause is two hairlines inside a 24dp
                // box, and a third of that is a smudge. These bars are a third of the width
                // each, which is what makes them read at three millimetres.
                val bar = size.width * 0.32f
                val gap = size.width * 0.16f
                val left = (size.width - (bar * 2 + gap)) / 2f
                val top = size.height * 0.06f
                val tall = size.height * 0.88f
                val round = CornerRadius(bar * 0.4f, bar * 0.4f)
                drawRoundRect(ink, Offset(left, top), Size(bar, tall), round)
                drawRoundRect(ink, Offset(left + bar + gap, top), Size(bar, tall), round)
            }
        } else {
            Box(
                modifier = Modifier
                    .size(DOT)
                    // Card colour rather than nothing behind the hollow ones: over a coloured
                    // keycap a transparent middle shows the blue through it and the ring loses
                    // its edge, which on the dark scheme is most of what made these hard to read.
                    .background(if (met) ink else scheme.surfaceContainer, CircleShape)
                    .border(STROKE, ink, CircleShape)
                    .semantics { contentDescription = label },
            )
        }
    }
}

/**
 * The mark, its own line, the ring of card colour that separates it from the keycap, and how far
 * the whole thing sits outside the corner. Bigger and brighter than it started: at seven across
 * with a hairline it was there and not quite readable, which is the worst size for a mark whose
 * whole job is to be read at a glance.
 */
private val DOT = 9.dp
private val STROKE = 2.dp
private val HALO = 2.dp
private val DOT_OUT = 3.dp

/** What each mark means, said out loud for a screen reader. */
private val RuleStanding.labelRes: Int
    get() = when (this) {
        RuleStanding.DONE -> R.string.card_rule_happened
        RuleStanding.PENDING -> R.string.card_rule_pending
        RuleStanding.HOLDING -> R.string.card_rule_holding
        RuleStanding.NOT_HOLDING -> R.string.card_rule_not_holding
        RuleStanding.UNKNOWN -> R.string.card_rule_unknown
    }
