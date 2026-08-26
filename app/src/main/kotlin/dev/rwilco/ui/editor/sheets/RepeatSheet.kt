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
import dev.rwilco.model.MAX_EVERY
import dev.rwilco.model.MAX_TIMES
import dev.rwilco.model.MIN_EVERY
import dev.rwilco.model.MIN_TIMES
import dev.rwilco.model.MonthlyOn
import dev.rwilco.model.RepeatEnd
import dev.rwilco.model.RepeatUnit
import dev.rwilco.model.Trigger
import dev.rwilco.ui.components.DayToggles
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.components.SheetScaffold
import dev.rwilco.ui.components.Stepper
import dev.rwilco.ui.components.calendar.MonthCalendar
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.ordinalRes
import dev.rwilco.ui.format.repeatSummary
import dev.rwilco.ui.theme.Tokens
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * A recurrence with a shape: every so many days, weeks, months or years, from a day, until it
 * stops.
 *
 * The tile used to ask for a time and a set of weekdays, which is one of the four shapes people
 * keep in a reminders app and not the interesting one. The order here is the order the question
 * is asked in — how often, what inside that, when in the day, from when, until when — and the
 * line at the bottom reads the whole thing back in the same words the card will use, because a
 * recurrence built out of five controls is a thing you want to see before you agree to it.
 */
@Composable
fun RepeatSheet(
    initial: Trigger?,
    today: LocalDate,
    defaultTime: LocalTime,
    shape: DayShape,
    onConfirm: (Trigger) -> Unit,
    onDismiss: () -> Unit,
) {
    val existing = initial as? Trigger.Repeat
    // A weekly trigger from before this sheet existed opens as what it always was.
    val legacy = initial as? Trigger.AtTime

    var every by rememberSaveable { mutableIntStateOf(existing?.every ?: 1) }
    var unitName by rememberSaveable { mutableStateOf((existing?.unit ?: RepeatUnit.WEEK).name) }
    val unit = RepeatUnit.valueOf(unitName)
    var startsOn by rememberDate(existing?.startsOn ?: today)
    var days by rememberSaveable {
        mutableStateOf((existing?.days ?: legacy?.days ?: setOf(today.dayOfWeek)).map { it.name }.toSet())
    }
    val selectedDays = days.map(DayOfWeek::valueOf).toSet()
    var atRandom by rememberSaveable { mutableStateOf(existing != null && existing.time == null) }
    var time by rememberTime(existing?.time ?: legacy?.time ?: defaultTime)

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
        time = if (atRandom) null else time,
        days = if (unit == RepeatUnit.WEEK) selectedDays else emptySet(),
        monthly = if (unit == RepeatUnit.MONTH) monthly else null,
        ends = when (endMode) {
            1 -> RepeatEnd.On(endDate)
            2 -> RepeatEnd.After(endTimes)
            else -> RepeatEnd.Never
        },
    )

    SheetScaffold(
        title = stringResource(R.string.kind_repeat_time),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(built) },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
        // A week with no day ticked is a week that never comes round.
        confirmEnabled = unit != RepeatUnit.WEEK || selectedDays.isNotEmpty(),
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
        }

        if (unit == RepeatUnit.MONTH) {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
                SegmentedChoice(
                    options = listOf(
                        stringResource(R.string.sheet_repeat_monthly_day, startsOn.dayOfMonth),
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
            atRandom = atRandom,
            onAtRandom = { atRandom = it },
            time = time,
            onTime = { time = it },
            date = startsOn,
            shape = shape,
        )

        Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            Label(stringResource(R.string.sheet_repeat_starts))
            // The anchor, not a formality: every block is counted from the day this falls on,
            // so moving it moves the whole series — which is why it is shown and not assumed.
            MonthCalendar(selected = startsOn, today = today, onSelect = { startsOn = it }, minDate = null)
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
                onSelect = { endMode = it },
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
            text = repeatSummary(built, today, currentLocale()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Label(text: String) {
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
