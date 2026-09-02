package dev.rwilco.ui.editor.sheets

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.DayShape
import dev.rwilco.model.DayTiming
import dev.rwilco.model.DayWindow
import dev.rwilco.model.MAX_EVERY
import dev.rwilco.model.MAX_TIMES
import dev.rwilco.model.MIN_EVERY
import dev.rwilco.model.MIN_TIMES
import dev.rwilco.model.MonthlyOn
import dev.rwilco.model.RepeatEnd
import dev.rwilco.model.RepeatUnit
import dev.rwilco.model.SavedWindow
import dev.rwilco.model.Trigger
import dev.rwilco.model.window
import dev.rwilco.ui.components.DayToggles
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.components.SheetScaffold
import dev.rwilco.ui.components.Stepper
import dev.rwilco.ui.components.calendar.MonthCalendar
import dev.rwilco.ui.format.rememberWords
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.ordinalRes
import dev.rwilco.ui.format.repeatSummary
import dev.rwilco.ui.theme.Tokens
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.LocalDate
import java.time.LocalTime

/**
 * The calendar behind "Vuelve": every so many days, weeks, months or years, from a day, until it
 * stops.
 *
 * It opens from the recurrence card and nowhere else. It used to be a *trigger* tile, which put
 * "cada semana" on one card and "vuelve cada semana" on another with nothing to tell them apart;
 * a repeat is an answer to "¿y vuelve?", so this is where it is asked. The order is the order
 * the question is asked in — how often, what inside that, when in the day, from when, until when
 * — and the line at the bottom reads the whole thing back in the same words the card will use,
 * because a calendar built out of five controls is a thing you want to see before you agree to
 * it.
 *
 * A new one opens on the hour the rules above it already name ([suggested]). "El 26 a las 20:00,
 * y vuelve cada mes" is one sentence, and asking for 20:00 twice — once in the trigger, once
 * here, three rows apart — is asking somebody to notice that the second control exists and then
 * to agree with themselves. Only ever a starting point, and only ever for a calendar that does
 * not exist yet.
 */
@Composable
fun CalendarSheet(
    initial: Trigger.Repeat?,
    today: LocalDate,
    defaultTime: LocalTime,
    shape: DayShape,
    savedWindows: List<SavedWindow>,
    /**
     * What the rules above already said about the time of day ([dayTimingOf]), for a calendar
     * being created. Ignored when there is an [initial]: an answer somebody has given is not
     * something a trigger may reach back and change.
     */
    suggested: DayTiming?,
    onConfirm: (Trigger.Repeat) -> Unit,
    onDismiss: () -> Unit,
) {
    val existing = initial
    // The rules' answer, or none, which is what a calendar with nothing above it starts from.
    val seed = if (existing == null) suggested else null

    var every by rememberSaveable { mutableIntStateOf(existing?.every ?: 1) }
    var unitName by rememberSaveable { mutableStateOf((existing?.unit ?: RepeatUnit.WEEK).name) }
    val unit = RepeatUnit.valueOf(unitName)
    var startsOn by rememberDate(existing?.startsOn ?: today)
    var days by rememberSaveable {
        mutableStateOf((existing?.days ?: setOf(today.dayOfWeek)).map { it.name }.toSet())
    }
    val selectedDays = days.map(DayOfWeek::valueOf).toSet()
    var kindName by rememberSaveable {
        mutableStateOf(
            when {
                existing?.window != null -> WhenKind.IN_WINDOW
                existing != null && existing.time == null -> WhenKind.ANY_TIME
                existing != null -> WhenKind.AT_TIME
                seed is DayTiming.At -> WhenKind.AT_TIME
                seed is DayTiming.In -> WhenKind.IN_WINDOW
                else -> WhenKind.ANY_TIME
            }.name,
        )
    }
    val kind = WhenKind.valueOf(kindName)
    var time by rememberTime(existing?.time ?: (seed as? DayTiming.At)?.time ?: defaultTime)
    val seededWindow = (seed as? DayTiming.In)?.window
    var windowFrom by rememberTime(existing?.window?.from ?: seededWindow?.from ?: LocalTime.of(14, 0))
    var windowTo by rememberTime(existing?.window?.to ?: seededWindow?.to ?: LocalTime.of(16, 0))
    val window = DayWindow(windowFrom, windowTo)

    val startingNth = existing?.monthly as? MonthlyOn.Nth
    var byWeekday by rememberSaveable { mutableStateOf(startingNth != null) }
    var ordinal by rememberSaveable { mutableIntStateOf(startingNth?.ordinal ?: ordinalOf(startsOn)) }
    var weekdayName by rememberSaveable { mutableStateOf((startingNth?.day ?: startsOn.dayOfWeek).name) }

    val startingEnds = existing?.ends ?: RepeatEnd.Never
    var endMode by rememberSaveable {
        mutableIntStateOf(
            when (startingEnds) {
                RepeatEnd.Never -> 0
                is RepeatEnd.On -> 1
                is RepeatEnd.After -> 2
            },
        )
    }
    var endDate by rememberDate((startingEnds as? RepeatEnd.On)?.date ?: today.plusMonths(1))
    var endTimes by rememberSaveable { mutableIntStateOf((startingEnds as? RepeatEnd.After)?.times ?: 30) }

    val monthly = if (byWeekday) MonthlyOn.Nth(ordinal, DayOfWeek.valueOf(weekdayName)) else MonthlyOn.Day(startsOn.dayOfMonth)
    val built = Trigger.Repeat(
        startsOn = startsOn,
        every = every,
        unit = unit,
        time = if (kind == WhenKind.AT_TIME) time else null,
        window = if (kind == WhenKind.IN_WINDOW) window else null,
        days = if (unit == RepeatUnit.WEEK) selectedDays else emptySet(),
        // A year names a month as well as a day, so it takes the same rule a month does.
        monthly = if (unit == RepeatUnit.MONTH || unit == RepeatUnit.YEAR) monthly else null,
        ends = when (endMode) {
            1 -> RepeatEnd.On(endDate)
            2 -> RepeatEnd.After(endTimes)
            else -> RepeatEnd.Never
        },
    )

    SheetScaffold(
        title = stringResource(R.string.recur_calendar),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(built) },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
        // A week with no day ticked is a week that never comes round, a stretch with no
        // width has no moment in it, and a series told to stop before it starts is empty.
        confirmEnabled = (unit != RepeatUnit.WEEK || selectedDays.isNotEmpty()) &&
            (kind != WhenKind.IN_WINDOW || windowFrom != windowTo) &&
            (endMode != 1 || endDate >= startsOn),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            Label(stringResource(R.string.sheet_repeat_every))
            Stepper(
                valueLabel = stringResource(R.string.sheet_repeat_every_value, every),
                onDecrement = { every = (every - 1).coerceAtLeast(MIN_EVERY) },
                onIncrement = { every = (every + 1).coerceAtMost(MAX_EVERY) },
                decrementEnabled = every > MIN_EVERY,
                incrementEnabled = every < MAX_EVERY,
            )
            SegmentedChoice(
                options = listOf(
                    stringResource(R.string.sheet_repeat_unit_days),
                    stringResource(R.string.sheet_repeat_unit_weeks),
                    stringResource(R.string.sheet_repeat_unit_months),
                    stringResource(R.string.sheet_repeat_unit_years),
                ),
                selectedIndex = RepeatUnit.entries.indexOf(unit),
                onSelect = { unitName = RepeatUnit.entries[it].name },
            )
        }

        if (unit == RepeatUnit.WEEK) {
            DayToggles(
                selected = selectedDays,
                onToggle = { day -> days = if (day.name in days) days - day.name else days + day.name },
            )
            // Said, not only greyed out: "Añadir" going quiet with no word beside it reads as
            // the sheet being broken.
            if (selectedDays.isEmpty()) {
                Text(
                    text = stringResource(R.string.calendar_days_error),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // The same two answers for a month and for a year: the day, or a weekday of it. A
        // yearly says the month too, because "el día 6" of an unnamed month is not an answer.
        if (unit == RepeatUnit.MONTH || unit == RepeatUnit.YEAR) {
            val monthName = startsOn.month.getDisplayName(TextStyle.FULL, currentLocale())
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
                SegmentedChoice(
                    options = listOf(
                        if (unit == RepeatUnit.YEAR) {
                            stringResource(R.string.sheet_repeat_yearly_day, startsOn.dayOfMonth, monthName)
                        } else {
                            stringResource(R.string.sheet_repeat_monthly_day, startsOn.dayOfMonth)
                        },
                        stringResource(R.string.sheet_repeat_monthly_nth),
                    ),
                    selectedIndex = if (byWeekday) 1 else 0,
                    onSelect = { byWeekday = it == 1 },
                )
                if (byWeekday) {
                    val locale = currentLocale()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        for (which in ORDINALS) {
                            PresetChip(
                                label = stringResource(ordinalRes(which)).replaceFirstChar { it.titlecase(locale) },
                                selected = ordinal == which,
                                onClick = { ordinal = which },
                            )
                        }
                    }
                    DayToggles(
                        selected = setOf(DayOfWeek.valueOf(weekdayName)),
                        onToggle = { day -> weekdayName = day.name },
                    )
                }
            }
        }

        WhenInTheDay(
            kind = kind,
            onKind = { kindName = it.name },
            time = time,
            onTime = { time = it },
            window = window,
            onWindow = { windowFrom = it.from; windowTo = it.to },
            date = startsOn,
            shape = shape,
            savedWindows = savedWindows,
        )

        Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            Label(stringResource(R.string.sheet_repeat_starts))
            // The anchor, not a formality: every block is counted from the day this falls on,
            // so moving it moves the whole series — which is why it is shown and not assumed.
            // The end follows the start (0.67.0): only the end grid was fenced by the start,
            // so moving the start past a chosen end built a series the save then refused
            // with nothing on the sheet to point at. The same coupling DateRangeSheet has.
            MonthCalendar(
                selected = startsOn,
                today = today,
                onSelect = { startsOn = it; if (endDate < it) endDate = it },
                minDate = null,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            Label(stringResource(R.string.sheet_repeat_ends))
            SegmentedChoice(
                options = listOf(
                    stringResource(R.string.sheet_repeat_ends_never),
                    stringResource(R.string.sheet_repeat_ends_on),
                    stringResource(R.string.sheet_repeat_ends_after),
                ),
                selectedIndex = endMode,
                // The default end (a month out) can already sit before a start moved past it.
                onSelect = { endMode = it; if (it == 1 && endDate < startsOn) endDate = startsOn },
            )
            when (endMode) {
                1 -> MonthCalendar(selected = endDate, today = today, onSelect = { endDate = it }, minDate = startsOn)
                2 -> Stepper(
                    valueLabel = stringResource(R.string.sheet_repeat_every_value, endTimes),
                    onDecrement = { endTimes = (endTimes - 1).coerceAtLeast(MIN_TIMES) },
                    onIncrement = { endTimes = (endTimes + 1).coerceAtMost(MAX_TIMES) },
                    decrementEnabled = endTimes > MIN_TIMES,
                    incrementEnabled = endTimes < MAX_TIMES,
                )
            }
        }

        // The whole thing read back, in the words the card will use.
        Text(
            text = repeatSummary(rememberWords(), built, today),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A control's own line. Shared with the sheets beside this one; same package, same shape. */
@Composable
internal fun Label(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** First to fourth and last: every month has all five, which is why there is no fifth. */
private val ORDINALS = listOf(1, 2, 3, 4, -1)

/**
 * Which "fourth Wednesday" a date is, for the first offer the sheet makes. A date in the fifth
 * week of its month is offered as the last one, because that is the only reading of it that
 * every month can honour.
 */
private fun ordinalOf(date: LocalDate): Int {
    val nth = (date.dayOfMonth - 1) / 7 + 1
    return if (nth > 4) -1 else nth
}

/** The day sets the preset chips offer, shared by the sheets that ask for days. */
internal val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
internal val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
internal val EVERY_DAY = DayOfWeek.entries.toSet()
