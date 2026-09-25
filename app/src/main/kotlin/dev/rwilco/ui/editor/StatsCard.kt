package dev.rwilco.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.ReminderStats
import dev.rwilco.model.RoundMark
import dev.rwilco.model.RoundShape
import dev.rwilco.ui.components.RoundStrip
import dev.rwilco.ui.format.Words
import dev.rwilco.ui.format.durationText
import dev.rwilco.ui.format.rememberWords
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import java.time.Duration

/**
 * What this reminder's history comes to (0.149.0): how many in a row, the best run, the last
 * rounds at a glance, how many hechos and how far apart, and how many of them were done the first
 * time of asking.
 *
 * A routine's "first time" is "on time" — nothing asks it twice, it goes overdue. A contact, or a
 * reminder that asks for nothing, only has its hechos: there is no streak to keep where nothing is
 * owed. The numbers are "since the oldest line kept", which the section's note says.
 */
@Composable
fun StatsCard(stats: ReminderStats, contact: Boolean) {
    val words = rememberWords()
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    Column {
        if (stats.shape != RoundShape.QUIET) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = stats.currentStreak.toString(), style = MonoStyles.countdown, color = scheme.onSurface)
                Spacer(Modifier.width(spacing.sm))
                Text(
                    text = pluralStringResource(R.plurals.stats_streak_unit, stats.currentStreak),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = spacing.xs).weight(1f),
                )
                // Only when it is a different number: "12 seguidas · mejor 12" says one thing twice.
                if (stats.bestStreak > stats.currentStreak) {
                    Text(
                        text = stringResource(R.string.stats_best, stats.bestStreak),
                        style = MonoStyles.date,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = spacing.xs),
                    )
                }
            }
            if (stats.recent.isNotEmpty()) {
                Spacer(Modifier.height(spacing.md))
                RoundStrip(marks = stats.recent, label = stripLabel(stats.recent))
            }
            Spacer(Modifier.height(spacing.md))
        }
        val count = if (contact) pluralStringResource(R.plurals.stats_talked, stats.done, stats.done)
        else pluralStringResource(R.plurals.stats_done, stats.done, stats.done)
        val gap = stats.meanGap?.let { stringResource(R.string.history_routine_gap, gapText(words, it)) }
        Text(
            text = listOfNotNull(count, gap).joinToString(stringResource(R.string.common_separator)),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
        )
        if (stats.shape != RoundShape.QUIET) {
            val answers = listOfNotNull(
                if (stats.done > 0) {
                    stringResource(if (stats.shape == RoundShape.ROUTINE) R.string.stats_on_time else R.string.stats_first_time, stats.firstTime, stats.done)
                } else null,
                if (stats.snoozes > 0) pluralStringResource(R.plurals.stats_snoozed, stats.snoozes, stats.snoozes) else null,
                if (stats.notDone > 0) pluralStringResource(R.plurals.stats_not_done, stats.notDone, stats.notDone) else null,
            )
            if (answers.isNotEmpty()) {
                Spacer(Modifier.height(spacing.xs))
                Text(
                    text = answers.joinToString(stringResource(R.string.common_separator)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A mean gap said as coarsely as it is true: past two days the hours are noise ("cada 7 d", not
 * "cada 7 d 3 h"), past an hour the minutes are.
 */
private fun gapText(words: Words, gap: Duration): String {
    val minutes = gap.toMinutes()
    val rounded = when {
        minutes >= 2 * MINUTES_PER_DAY -> Math.round(minutes / MINUTES_PER_DAY.toDouble()) * MINUTES_PER_DAY
        minutes >= 60 -> Math.round(minutes / 60.0) * 60
        else -> minutes.coerceAtLeast(1)
    }
    return durationText(words, rounded.toInt())
}

private const val MINUTES_PER_DAY = 24 * 60L

@Composable
private fun stripLabel(marks: List<RoundMark>): String {
    val first = marks.count { it == RoundMark.FIRST_TIME }
    val later = marks.count { it == RoundMark.DONE || it == RoundMark.LATE }
    val missed = marks.count { it == RoundMark.NOT_DONE }
    return stringResource(R.string.stats_strip_label, marks.size, first, later, missed)
}
