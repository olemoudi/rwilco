package dev.rwilco.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.rwilco.model.MONTH_DAYS
import dev.rwilco.model.TriggerFamily
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.color
import dev.rwilco.ui.theme.onColor
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.temporal.WeekFields

/**
 * Seven round toggles in the locale's week order, 44dp+ each. A day that is on is a solid disc
 * of the time family's colour — a fill, not a tint, because a week is read as a pattern of
 * lit and unlit and a pale ring never lit.
 */
@Composable
fun DayToggles(selected: Set<DayOfWeek>, onToggle: (DayOfWeek) -> Unit, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    val days = remember(locale) { List(7) { WeekFields.of(locale).firstDayOfWeek.plus(it.toLong()) } }
    val haptics = Tokens.haptics
    val family = TriggerFamily.TIME
    val motion = Tokens.motion
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
        for (day in days) {
            val on = day in selected
            val fullName = day.getDisplayName(TextStyle.FULL, locale)
            val fill by animateColorAsState(
                targetValue = if (on) family.color() else MaterialTheme.colorScheme.surfaceContainerLow,
                animationSpec = tween(motion.fast),
                label = "dayFill",
            )
            val ink by animateColorAsState(
                targetValue = if (on) family.onColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(motion.fast),
                label = "dayInk",
            )
            Surface(
                onClick = {
                    haptics.perform(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    onToggle(day)
                },
                shape = CircleShape,
                color = fill,
                border = if (on) null else BorderStroke(Tokens.strokes.control, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                    .aspectRatio(1f)
                    // The tint is the only visible state; a screen reader needs it said.
                    .semantics {
                        contentDescription = fullName
                        this.selected = on
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = TimeText.dayInitial(day, locale),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                        color = ink,
                    )
                }
            }
        }
    }
}

/**
 * The days of the month, as a grid of the same round toggles: as many to a row as the width
 * takes, each one stretched to share it.
 *
 * How many that is belongs to the layout and not to a constant here — the discs are the size
 * they need to be for a thumb, and a column count written down is one that is wrong on the
 * next screen width. The number is the label, so unlike [DayToggles] there is nothing to
 * describe on top of it: what a screen reader reads is the day itself, plus whether it is on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonthDayToggles(selected: Set<Int>, onToggle: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = Tokens.haptics
    val family = TriggerFamily.TIME
    val motion = Tokens.motion
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
    ) {
        for (day in MONTH_DAYS) {
            val on = day in selected
            val fill by animateColorAsState(
                targetValue = if (on) family.color() else MaterialTheme.colorScheme.surfaceContainerLow,
                animationSpec = tween(motion.fast),
                label = "monthDayFill",
            )
            val ink by animateColorAsState(
                targetValue = if (on) family.onColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(motion.fast),
                label = "monthDayInk",
            )
            Surface(
                onClick = {
                    haptics.perform(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    onToggle(day)
                },
                shape = CircleShape,
                color = fill,
                border = if (on) null else BorderStroke(Tokens.strokes.control, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .weight(1f)
                    .sizeIn(minWidth = 40.dp, minHeight = 40.dp)
                    .aspectRatio(1f)
                    .semantics { this.selected = on },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "$day",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
                        color = ink,
                    )
                }
            }
        }
    }
}
