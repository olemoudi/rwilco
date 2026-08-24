package dev.rwilco.ui.components.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields
import kotlinx.coroutines.launch

/**
 * A month at a time, swiped or stepped with the arrows. Six fixed rows so the sheet never
 * jumps; the selected day is the amber disc, today a ring. Past days are dimmed and inert
 * when [minDate] says so.
 */
@Composable
fun MonthCalendar(
    selected: LocalDate?,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    minDate: LocalDate? = today,
) {
    val locale = currentLocale()
    val firstDayOfWeek = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val base = remember(today) { YearMonth.from(today) }
    val startMonth = remember(selected) { YearMonth.from(selected ?: today) }
    val pager = rememberPagerState(initialPage = MonthGrid.pageOf(startMonth, base)) { MonthGrid.PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val haptics = Tokens.haptics
    val month = MonthGrid.monthAt(pager.currentPage, base)

    LaunchedEffect(pager.currentPage) { haptics.perform(HapticFeedbackType.SegmentTick) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = TimeText.monthYear(month, locale),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = stringResource(R.string.calendar_previous_month))
            }
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = stringResource(R.string.calendar_next_month))
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            for (day in MonthGrid.weekdays(firstDayOfWeek)) {
                Text(
                    text = TimeText.dayInitial(day, locale),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        HorizontalPager(state = pager, beyondViewportPageCount = 1) { page ->
            val pageMonth = MonthGrid.monthAt(page, base)
            val cells = remember(pageMonth, firstDayOfWeek) { MonthGrid.cells(pageMonth, firstDayOfWeek) }
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (row in 0 until MonthGrid.CELLS / MonthGrid.COLUMNS) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (column in 0 until MonthGrid.COLUMNS) {
                            val date = cells[row * MonthGrid.COLUMNS + column]
                            DayCell(
                                date = date,
                                selected = date != null && date == selected,
                                isToday = date == today,
                                enabled = date != null && (minDate == null || !date.isBefore(minDate)),
                                onSelect = { date?.let(onSelect) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate?,
    selected: Boolean,
    isToday: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = Tokens.haptics
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(scheme.primary) else Modifier)
            .then(if (isToday && !selected) Modifier.border(1.dp, scheme.primary, CircleShape) else Modifier)
            .then(
                if (date != null) {
                    Modifier.selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = {
                            haptics.perform(HapticFeedbackType.SegmentTick)
                            onSelect()
                        },
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (date != null) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MonoStyles.label.copy(fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Medium),
                color = when {
                    selected -> scheme.onPrimary
                    !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.45f)
                    else -> scheme.onSurface
                },
            )
        }
    }
}
