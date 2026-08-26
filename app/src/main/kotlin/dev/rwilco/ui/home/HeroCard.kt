package dev.rwilco.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.rwilco.R
import dev.rwilco.model.TriggerFamily
import dev.rwilco.model.kind
import dev.rwilco.model.partsBetween
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.TriggerKeycap
import dev.rwilco.ui.components.lampGlow
import dev.rwilco.ui.components.rememberNow
import dev.rwilco.ui.editor.titleRes
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.countdownText
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.format.rememberIs24h
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.icon
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The one card that glows: the next definite moment. The lamp brightens as it nears, and the
 * countdown ticks — inside [LiveCountdown] only, so the rest of the card (and the list) is
 * untouched by the ticking.
 */
@Composable
fun HeroCard(
    hero: HeroUi,
    clock: Clock,
    today: LocalDate,
    onClick: () -> Unit,
) {
    val spacing = Tokens.spacing
    val amber = MaterialTheme.colorScheme.primary
    val locale = currentLocale()
    val is24h = rememberIs24h()
    val minuteNow by rememberNow(60_000, clock)
    val minutesLeft = Duration.between(minuteNow, hero.at).toMinutes()
    val intensity = when {
        minutesLeft <= 5 -> 1f
        minutesLeft <= 60 -> 0.7f
        else -> 0.45f
    }
    val at = hero.at.atZone(clock.zone)
    // The row whose moment this is; failing that, the first trigger — unless the moment is
    // the recurrence's own (no row matches and one is in charge), which gets its own badge.
    val nextTrigger = hero.card.triggers.firstOrNull { it.nextAt == hero.at }
        ?: hero.card.triggers.firstOrNull().takeIf { hero.card.recurrence == null || hero.snoozed }

    RwilcoCard(onClick = onClick, shape = MaterialTheme.shapes.extraLarge) {
        Column(
            modifier = Modifier
                .lampGlow(amber, intensity)
                .padding(spacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // A postponed reminder is here because somebody pushed it away, not because
                    // its own moment is near, and a countdown that does not say so is a puzzle.
                    text = stringResource(if (hero.snoozed) R.string.home_next_up_snoozed else R.string.home_next_up).uppercase(locale),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold),
                    color = amber,
                    modifier = Modifier.weight(1f),
                )
                if (nextTrigger != null) {
                    TriggerKeycap(
                        family = nextTrigger.family,
                        icon = nextTrigger.trigger.kind.icon,
                        contentDescription = stringResource(nextTrigger.trigger.kind.titleRes),
                    )
                } else if (hero.card.recurrence != null) {
                    // Nothing but a recurrence: the moment being counted down to is its doing,
                    // so the badge is its own rather than absent.
                    TriggerKeycap(
                        family = TriggerFamily.TIME,
                        icon = Icons.Outlined.Autorenew,
                        contentDescription = stringResource(R.string.card_recurrence),
                    )
                }
            }
            Spacer(Modifier.height(spacing.sm))
            LiveCountdown(at = hero.at, clock = clock, style = MonoStyles.countdown, color = amber)
            Text(
                text = dayWord(at.toLocalDate(), today, locale) + " · " + TimeText.time(at.toLocalTime(), is24h, locale),
                style = MonoStyles.date,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.md))
            Text(
                text = hero.card.text,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (hero.card.tags.isNotEmpty() || hero.card.actions.isNotEmpty()) {
                Spacer(Modifier.height(spacing.sm))
                CardFooter(tags = hero.card.tags, actions = hero.card.actions, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * The ticking text and nothing else — and it only ticks as fast as it reads.
 *
 * [countdownText] shows seconds under the hour and not a moment before, so a moment three days
 * out spends 3,599 recompositions an hour rewriting the same six characters. A minute ticker
 * decides when the second one is worth starting, and hands over an hour before it is needed.
 */
@Composable
fun LiveCountdown(at: Instant, clock: Clock, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val coarse by rememberNow(60_000, clock)
    val ticking = Duration.between(coarse, at).abs() <= Duration.ofHours(1)
    val now by rememberNow(if (ticking) 1_000 else 60_000, clock)
    Text(text = countdownText(partsBetween(now, at)), style = style, color = color, modifier = modifier)
}

/** Tags on the left, action glyphs on the right; shared by the hero and the plain cards. */
@Composable
fun CardFooter(
    tags: List<String>,
    actions: Set<dev.rwilco.model.Action>,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.xs),
        ) {
            for (tag in tags.take(3)) dev.rwilco.ui.components.TagLabel(tag)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            for (action in dev.rwilco.model.Action.entries) {
                if (action in actions) {
                    dev.rwilco.ui.components.ActionGlyph(icon = action.icon, contentDescription = stringResource(action.labelRes))
                }
            }
            trailing()
        }
    }
}

val dev.rwilco.model.Action.labelRes: Int
    get() = when (this) {
        dev.rwilco.model.Action.FULL_SCREEN -> R.string.action_full_screen
        dev.rwilco.model.Action.NOTIFICATION -> R.string.action_notification
        dev.rwilco.model.Action.SOUND -> R.string.action_sound
        dev.rwilco.model.Action.SOUND_UNTIL_ANSWERED -> R.string.action_sound_until_answered
        dev.rwilco.model.Action.VIBRATE -> R.string.action_vibrate
    }
