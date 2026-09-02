package dev.rwilco.ui.components.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowDropUp
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import java.time.format.DateTimeFormatter
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import java.time.format.TextStyle
import java.util.Locale
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
    // A day picked from outside the grid (a shortcut chip) may be on another page: turn to it.
    // A tap on the grid itself is on the page already, so this never fights a swipe.
    LaunchedEffect(selected) {
        val page = MonthGrid.pageOf(YearMonth.from(selected ?: today), base)
        if (page != pager.currentPage && !pager.isScrollInProgress) pager.animateScrollToPage(page)
    }

    // The month's name is a door: a date a year out was twelve swipes, one per month, with
    // nothing between that and typing. Tapped, the grid gives way to the year and its twelve
    // months; a month tapped there turns the pager to it and the grid comes back.
    var jumping by rememberSaveable { mutableStateOf(false) }
    var jumpYear by rememberSaveable(month.year) { mutableIntStateOf(month.year) }
    val jumpRange = remember(base) { MonthGrid.monthAt(0, base).year..MonthGrid.monthAt(MonthGrid.PAGE_COUNT - 1, base).year }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.calendar_jump),
                        onClick = {
                            haptics.perform(HapticFeedbackType.SegmentTick)
                            jumpYear = month.year
                            jumping = !jumping
                        },
                    )
                    .heightIn(min = Tokens.sizes.touch),
            ) {
                Text(text = TimeText.monthYear(month, locale), style = MaterialTheme.typography.titleMedium)
                Icon(
                    imageVector = if (jumping) Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = stringResource(R.string.calendar_previous_month))
            }
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = stringResource(R.string.calendar_next_month))
            }
        }
        if (jumping) {
            MonthJump(
                year = jumpYear,
                yearRange = jumpRange,
                current = month,
                locale = locale,
                onYear = { jumpYear = it },
                onMonth = { picked ->
                    jumping = false
                    scope.launch { pager.animateScrollToPage(MonthGrid.pageOf(picked, base)) }
                },
            )
            return@Column
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

/**
 * The year with its twelve months, in the grid's place: three rows of four, the month on
 * screen inverted, the year stepped with the same arrows the months use.
 */
@Composable
private fun MonthJump(
    year: Int,
    yearRange: IntRange,
    current: YearMonth,
    locale: Locale,
    onYear: (Int) -> Unit,
    onMonth: (YearMonth) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = Tokens.haptics
    val spacing = Tokens.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onYear(year - 1) }, enabled = year - 1 >= yearRange.first) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = stringResource(R.string.calendar_previous_year))
            }
            Text(
                text = year.toString(),
                style = MonoStyles.time,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onYear(year + 1) }, enabled = year + 1 <= yearRange.last) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = stringResource(R.string.calendar_next_year))
            }
        }
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs), modifier = Modifier.fillMaxWidth()) {
                for (column in 0 until 4) {
                    val ym = YearMonth.of(year, row * 4 + column + 1)
                    val selected = ym == current
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Tokens.sizes.touch)
                            .clip(MaterialTheme.shapes.small)
                            .background(if (selected) scheme.onSurface else scheme.surfaceContainerHigh)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = {
                                    haptics.perform(HapticFeedbackType.SegmentTick)
                                    onMonth(ym)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = ym.month.getDisplayName(TextStyle.SHORT, locale).replace(".", "").replaceFirstChar { it.titlecase(locale) },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) scheme.surface else scheme.onSurface,
                        )
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
    // "martes 14 de octubre, hoy" to a screen reader, not a bare "14" (0.69.0): the grid read
    // as a run of numbers with no month, and "today" was a ring nobody hears.
    val locale = currentLocale()
    val todayWord = stringResource(R.string.relative_today)
    val spoken = date?.let { day ->
        day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", locale)) + if (isToday) ", $todayWord" else ""
    }
    // **The whole cell is the target, and the disc inside it is only what you see.** Seven
    // columns across a padded sheet leave about 46dp each on a 360dp phone — under the 48dp
    // floor, and the one place in the app where that is arithmetic rather than an oversight —
    // so every dp of it counts. The tap used to be taken *after* the 2dp inset, giving away
    // four of them on the densest grid there is; the inset now only moves the paint.
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .then(
                if (date != null) {
                    Modifier
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = {
                                haptics.perform(HapticFeedbackType.SegmentTick)
                                onSelect()
                            },
                        )
                        .semantics { if (spoken != null) contentDescription = spoken }
                } else {
                    Modifier
                },
            )
            .padding(2.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(scheme.primary) else Modifier)
            .then(if (isToday && !selected) Modifier.border(1.dp, scheme.primary, CircleShape) else Modifier),
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
