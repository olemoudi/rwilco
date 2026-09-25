package dev.rwilco.ui.done

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.rwilco.R
import dev.rwilco.cheer.CheerText
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.rwilco.model.AchievementFamily
import dev.rwilco.model.Goal
import dev.rwilco.model.Standing
import dev.rwilco.model.Unlocked
import dev.rwilco.model.shortSubject
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import java.time.LocalDate

/**
 * The Hechos screen's own numbers (0.150.0), between the fortnight and the list: the streaks under
 * way, the things never once left undone, and the milestones earned. Typographic, like the rest of
 * the screen: ink for what is said, no colour, because nothing here fires next and nothing here is
 * a family or a tag.
 */

/** The running streaks: the count in mono, the words, and the best run when it is a different number. Tap opens the reminder. */
@Composable
fun StreaksCard(streaks: List<Standing>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    StandingsCard(modifier) {
        streaks.forEachIndexed { index, standing ->
            if (index > 0) RowDivider()
            StandingRow(
                lead = standing.count.toString(),
                text = standing.subject.text,
                trailing = if (standing.best > standing.count) stringResource(R.string.stats_best, standing.best) else null,
                onClick = { onOpen(standing.subject.id) },
            )
        }
    }
}

/** What has never once been left undone, and how many times it was done. */
@Composable
fun NeverFailCard(standings: List<Standing>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    StandingsCard(modifier) {
        standings.forEachIndexed { index, standing ->
            if (index > 0) RowDivider()
            StandingRow(
                lead = null,
                text = standing.subject.text,
                trailing = pluralStringResource(R.plurals.stats_times, standing.count, standing.count),
                onClick = { onOpen(standing.subject.id) },
            )
        }
    }
}

/**
 * The milestones earned, newest first — the first few, and the rest behind "ver los N" — and the
 * nearest one still ahead, which is the line that gives somebody something to go for.
 */
@Composable
fun AchievementsCard(earned: List<Unlocked>, goal: Goal?, today: LocalDate, modifier: Modifier = Modifier) {
    val spacing = Tokens.spacing
    var all by rememberSaveable { mutableStateOf(false) }
    val shown = if (all) earned else earned.take(ACHIEVEMENTS_SHOWN)
    StandingsCard(modifier) {
        shown.forEachIndexed { index, one ->
            if (index > 0) RowDivider()
            AchievementRow(one, today)
        }
        if (earned.size > ACHIEVEMENTS_SHOWN) {
            // Ink, not the default primary: amber is what fires next, and this is a list.
            TextButton(
                onClick = { all = !all },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.padding(horizontal = spacing.sm),
            ) {
                Text(
                    text = if (all) stringResource(R.string.done_achievements_fewer) else stringResource(R.string.done_achievements_all, earned.size),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (goal != null) {
            if (shown.isNotEmpty()) RowDivider()
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.touch).padding(horizontal = spacing.lg, vertical = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Tokens.sizes.glyph))
                Spacer(Modifier.width(spacing.md))
                Text(
                    text = goalText(goal),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** How many achievements show before "ver los N": a card, not a wall. */
private const val ACHIEVEMENTS_SHOWN = 5

@Composable
private fun AchievementRow(one: Unlocked, today: LocalDate) {
    val spacing = Tokens.spacing
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.sizes.touch).padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Tokens.sizes.glyph))
        Spacer(Modifier.width(spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = achievementTitle(one.family, one.tier), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            one.about?.let {
                Text(
                    text = stringResource(R.string.done_achievement_about, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(spacing.sm))
        Text(text = TimeText.dayDate(one.on, currentLocale(), today), style = MonoStyles.date, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "30 seguidas", "100 hechos", "4 semanas redondas": the same words the encouragement uses. */
@Composable
fun achievementTitle(family: AchievementFamily, tier: Int): String {
    // Read so a change of language redraws it, as stringResource does.
    LocalConfiguration.current
    return CheerText.achievementTitle(LocalContext.current.resources, family, tier)
}

@Composable
private fun goalText(goal: Goal): String {
    val subject = goal.subject
    return if (goal.family == AchievementFamily.STREAK && subject != null) {
        pluralStringResource(R.plurals.done_goal_streak, goal.remaining, goal.tier, shortSubject(subject.text), goal.remaining)
    } else {
        pluralStringResource(R.plurals.done_goal, goal.remaining, achievementTitle(goal.family, goal.tier), goal.remaining)
    }
}

@Composable
private fun StandingsCard(modifier: Modifier, content: @Composable () -> Unit) {
    RwilcoCard(modifier = modifier) {
        Column(modifier = Modifier.padding(vertical = Tokens.spacing.xs), verticalArrangement = Arrangement.Top) { content() }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = Tokens.spacing.lg), color = MaterialTheme.colorScheme.outlineVariant)
}

/** One line of a list of reminders with a number: tall enough to be a target, and the whole row is one. */
@Composable
private fun StandingRow(lead: String?, text: String, trailing: String?, onClick: () -> Unit) {
    val spacing = Tokens.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.stats_open), onClick = onClick)
            .heightIn(min = Tokens.sizes.touch)
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lead != null) {
            Text(text = lead, style = MonoStyles.time, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.widthIn(min = Tokens.sizes.keycap))
            Spacer(Modifier.width(spacing.sm))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Spacer(Modifier.width(spacing.sm))
            Text(text = trailing, style = MonoStyles.date, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
