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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.DayShape
import dev.rwilco.model.DayWindow
import dev.rwilco.model.SavedWindow
import dev.rwilco.model.Trigger
import dev.rwilco.model.awakeOn
import dev.rwilco.model.window
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.components.SheetScaffold
import dev.rwilco.ui.components.TimeField
import dev.rwilco.ui.components.calendar.MonthCalendar
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.rememberIs24h
import dev.rwilco.ui.theme.Tokens
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import dev.rwilco.model.DateShortcut
import androidx.compose.foundation.layout.padding
import java.time.LocalDate
import androidx.compose.runtime.mutableIntStateOf
import dev.rwilco.model.RELATIVE_AMOUNT
import dev.rwilco.model.RelativeDay
import dev.rwilco.model.RelativeUnit
import dev.rwilco.ui.components.DayToggles
import dev.rwilco.ui.components.Stepper
import dev.rwilco.ui.format.relativeDayText
import dev.rwilco.ui.format.rememberWords
import java.time.DayOfWeek
import dev.rwilco.model.on

/**
 * A day, and then the question of when in it.
 *
 * One tile, where there were two. "Fecha" and "fecha y hora" asked the same thing and differed
 * only in whether the hour came from the settings, which is not a decision anybody wants to
 * make before picking a day — and picking the wrong tile meant going back and starting again.
 * So the hour is here, and beside it the two answers that do not name one.
 *
 * It opens on the laxest of the three, because that is the one that needs no thinking about: a
 * day is a thing you know, an hour is a thing you have to decide, and a sheet that opens on the
 * decision makes you take it before you have picked the day. Narrowing from there is one tap.
 *
 * The chips over the calendar name a day and nothing else ([DateShortcut]). The first set of
 * chips was taken out because each of them ("mañana a las 9", "el sábado a las 10") also
 * quietly picked the hour, so tapping one undid the choice below it and the two halves of the
 * sheet disagreed about what you had said. These four touch only the date: the calendar turns
 * to the day they picked, and "when in the day" stays whatever it was.
 */
@Composable
fun DateSheet(
    initial: Trigger?,
    now: ZonedDateTime,
    defaultTime: LocalTime,
    shape: DayShape,
    savedWindows: List<SavedWindow>,
    onConfirm: (Trigger) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = now.toLocalDate()
    val startDate = when (initial) {
        is Trigger.AtDateTime -> initial.at.toLocalDate()
        is Trigger.OnDate -> initial.date
        is Trigger.DayRandom -> initial.date
        else -> today.plusDays(1)
    }
    var date by rememberDate(startDate)
    var time by rememberTime(
        when (initial) {
            is Trigger.AtDateTime -> initial.at.toLocalTime()
            is Trigger.RelativeDate -> initial.time ?: defaultTime
            // What the old date-only trigger always meant, now written down where it can be seen.
            else -> defaultTime
        },
    )
    // Counted from the day it is used rather than pointed at on a calendar. It is the same
    // question — which day, and when in it — so it lives here rather than on a tile of its own;
    // what changes is only how the day is said.
    val startingRelative = initial as? Trigger.RelativeDate
    var relative by rememberSaveable { mutableStateOf(startingRelative != null) }
    var amount by rememberSaveable { mutableIntStateOf((startingRelative?.day as? RelativeDay.In)?.amount ?: 1) }
    var unitName by rememberSaveable {
        mutableStateOf(((startingRelative?.day as? RelativeDay.In)?.unit ?: RelativeUnit.DAYS).name)
    }
    val unit = RelativeUnit.valueOf(unitName)
    var weekdayName by rememberSaveable {
        mutableStateOf((startingRelative?.day as? RelativeDay.NextWeekday)?.day?.name)
    }
    val relativeDay: RelativeDay = weekdayName?.let { RelativeDay.NextWeekday(DayOfWeek.valueOf(it)) }
        ?: RelativeDay.In(amount, unit)
    val startingWindow = (initial as? Trigger.DayRandom)?.window ?: startingRelative?.window
    var kindName by rememberSaveable {
        mutableStateOf(
            when {
                startingWindow != null -> WhenKind.IN_WINDOW
                startingRelative != null -> if (startingRelative.time != null) WhenKind.AT_TIME else WhenKind.ANY_TIME
                initial is Trigger.DayRandom -> WhenKind.ANY_TIME
                // An hour on disk is one somebody typed (or, for the old date-only shape, the
                // one it has always meant); a sheet with nothing behind it opens on the answer
                // that asks for nothing.
                initial != null -> WhenKind.AT_TIME
                else -> WhenKind.ANY_TIME
            }.name,
        )
    }
    val kind = WhenKind.valueOf(kindName)
    var windowFrom by rememberTime(startingWindow?.from ?: LocalTime.of(14, 0))
    var windowTo by rememberTime(startingWindow?.to ?: LocalTime.of(16, 0))
    val window = DayWindow(windowFrom, windowTo)

    SheetScaffold(
        title = stringResource(R.string.kind_date),
        onDismiss = onDismiss,
        onConfirm = {
            onConfirm(
                when {
                    relative -> Trigger.RelativeDate(
                        day = relativeDay,
                        time = time.takeIf { kind == WhenKind.AT_TIME },
                        window = window.takeIf { kind == WhenKind.IN_WINDOW },
                    )
                    kind == WhenKind.AT_TIME -> Trigger.AtDateTime(LocalDateTime.of(date, time))
                    kind == WhenKind.IN_WINDOW -> Trigger.DayRandom(date, window)
                    else -> Trigger.DayRandom(date)
                },
            )
        },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
        // A stretch with no width has no moment in it.
        confirmEnabled = kind != WhenKind.IN_WINDOW || windowFrom != windowTo,
    ) {
        SegmentedChoice(
            options = listOf(stringResource(R.string.sheet_date_fixed), stringResource(R.string.sheet_date_relative)),
            selectedIndex = if (relative) 1 else 0,
            onSelect = { relative = it == 1 },
            modifier = Modifier.padding(bottom = Tokens.spacing.md),
        )
        if (relative) {
            RelativeDayPicker(
                amount = amount,
                unit = unit,
                weekday = weekdayName?.let { DayOfWeek.valueOf(it) },
                onIn = { newAmount, newUnit ->
                    amount = newAmount
                    unitName = newUnit.name
                    weekdayName = null
                },
                onWeekday = { weekdayName = it.name },
            )
        } else {
            DateShortcuts(today = today, selected = date, onPick = { date = it })
            MonthCalendar(selected = date, today = today, onSelect = { date = it }, minDate = today)
        }
        WhenInTheDay(
            kind = kind,
            onKind = { kindName = it.name },
            time = time,
            onTime = { time = it },
            window = window,
            onWindow = { windowFrom = it.from; windowTo = it.to },
            // The day this sheet is about, which in the relative mode is not the one on the
            // calendar behind it: "a cualquier hora" quotes that day's waking hours, and a
            // leftover Saturday would quote the weekend's for a reminder landing on a Friday.
            date = if (relative) relativeDay.on(today) else date,
            shape = shape,
            savedWindows = savedWindows,
        )
    }
}

/**
 * How a day is said when it is counted rather than pointed at: the four everybody names, a
 * span to count, or the next Monday there is.
 *
 * The two ways are one segmented control because they are two different questions — "dentro de
 * una semana" is seven days, "el próximo lunes" is however many days that turns out to be — and
 * a person means one or the other, never both.
 */
@Composable
private fun RelativeDayPicker(
    amount: Int,
    unit: RelativeUnit,
    weekday: DayOfWeek?,
    onIn: (Int, RelativeUnit) -> Unit,
    onWeekday: (DayOfWeek) -> Unit,
) {
    val spacing = Tokens.spacing
    val words = rememberWords()
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md), modifier = Modifier.padding(bottom = spacing.md)) {
        Text(
            text = stringResource(R.string.sheet_relative_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            for (shortcut in RELATIVE_SHORTCUTS) {
                PresetChip(
                    label = relativeDayText(words, shortcut).replaceFirstChar { it.titlecase(words.locale) },
                    selected = weekday == null && shortcut == RelativeDay.In(amount, unit),
                    onClick = { onIn(shortcut.amount, shortcut.unit) },
                )
            }
        }
        SegmentedChoice(
            options = listOf(stringResource(R.string.sheet_relative_in), stringResource(R.string.sheet_relative_next)),
            selectedIndex = if (weekday == null) 0 else 1,
            onSelect = { index -> if (index == 0) onIn(amount, unit) else onWeekday(weekday ?: DayOfWeek.MONDAY) },
        )
        if (weekday == null) {
            // The unit on its own line: beside the stepper the three words had a third of a
            // phone between them and "semanas" broke in half.
            Stepper(
                valueLabel = amount.toString(),
                onDecrement = { onIn(amount - 1, unit) },
                onIncrement = { onIn(amount + 1, unit) },
                decrementEnabled = amount > RELATIVE_AMOUNT.first,
                incrementEnabled = amount < RELATIVE_AMOUNT.last,
            )
            SegmentedChoice(
                options = listOf(
                    stringResource(R.string.sheet_relative_days),
                    stringResource(R.string.sheet_relative_weeks),
                    stringResource(R.string.sheet_relative_months),
                ),
                selectedIndex = RelativeUnit.entries.indexOf(unit),
                onSelect = { onIn(amount, RelativeUnit.entries[it]) },
            )
        } else {
            DayToggles(selected = setOf(weekday), onToggle = onWeekday)
        }
    }
}

/** The four everybody names, in the order they come. */
private val RELATIVE_SHORTCUTS = listOf(
    RelativeDay.In(1, RelativeUnit.DAYS),
    RelativeDay.In(2, RelativeUnit.DAYS),
    RelativeDay.In(1, RelativeUnit.WEEKS),
    RelativeDay.In(1, RelativeUnit.MONTHS),
)

/** The four days people name without looking: one tap each, date only. */
@Composable
private fun DateShortcuts(today: LocalDate, selected: LocalDate, onPick: (LocalDate) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = Tokens.spacing.md),
    ) {
        for (shortcut in DateShortcut.entries) {
            val day = shortcut.on(today)
            PresetChip(
                label = stringResource(
                    when (shortcut) {
                        DateShortcut.TODAY -> R.string.relative_today
                        DateShortcut.TOMORROW -> R.string.relative_tomorrow
                        DateShortcut.NEXT_MONDAY -> R.string.date_shortcut_next_monday
                        DateShortcut.WEEKEND -> R.string.date_shortcut_weekend
                    },
                ).replaceFirstChar { it.titlecase() },
                selected = selected == day,
                onClick = { onPick(day) },
            )
        }
    }
}

/**
 * The three answers to "when in the day", widening from the whole of it to an hour.
 *
 * In that order because that is the order they are decided in: not caring is where everybody
 * starts, and each step to the right is somebody choosing to say more. The first is also what a
 * new sheet opens on, so the common answer costs no taps at all.
 */
enum class WhenKind { ANY_TIME, IN_WINDOW, AT_TIME }

/**
 * When in the day: an hour I pick, somewhere inside a stretch I name, or none of your business.
 *
 * Shared by the date tile and the calendar behind "Vuelve", because it is the same question and
 * has to be the same control — which is also why the calendar opens on whatever the rules above
 * it already answered (`dayTimingOf`). The three widen in order — the day, a stretch, a moment —
 * and the middle one is the one that needed a name for a stretch to be worth having: "a la hora
 * de comer" is a thing people say and "entre las 14:00 y las 16:00" is a thing they have to
 * think about. The chips are those names ([SavedWindow], kept in the settings); the two fields
 * under them are always there, so a stretch nobody has named is still one tap further than a
 * chip and not a trip to the settings.
 *
 * **Neither of the first two is a lottery.** They open when their stretch opens and stay true
 * until it closes ([openingOf]) — the hints say so in as many words — because a moment nobody
 * can predict is a thing to ask for on purpose, and there is a tile for it.
 *
 * The hint under the first is the day's own window, worked out for whichever day is selected —
 * a Saturday and a Tuesday say different things, which is the whole point of having asked for
 * the hours in the first place.
 */
@Composable
fun WhenInTheDay(
    kind: WhenKind,
    onKind: (WhenKind) -> Unit,
    time: LocalTime,
    onTime: (LocalTime) -> Unit,
    window: DayWindow,
    onWindow: (DayWindow) -> Unit,
    date: java.time.LocalDate,
    shape: DayShape,
    savedWindows: List<SavedWindow> = emptyList(),
) {
    val is24h = rememberIs24h()
    val locale = currentLocale()
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
        SegmentedChoice(
            options = listOf(
                stringResource(R.string.sheet_any_time),
                stringResource(R.string.sheet_in_window),
                stringResource(R.string.sheet_at_this_time),
            ),
            selectedIndex = kind.ordinal,
            onSelect = { onKind(WhenKind.entries[it]) },
        )
        when (kind) {
            WhenKind.AT_TIME -> TimeField(
                time = time,
                onChange = onTime,
                label = stringResource(R.string.sheet_time),
                modifier = Modifier.fillMaxWidth(),
            )
            WhenKind.IN_WINDOW -> {
                if (savedWindows.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        for (saved in savedWindows) {
                            PresetChip(
                                label = saved.label,
                                selected = saved.window == window,
                                onClick = { onWindow(saved.window) },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    TimeField(
                        time = window.from,
                        onChange = { onWindow(window.copy(from = it)) },
                        label = stringResource(R.string.random_from),
                        modifier = Modifier.weight(1f),
                    )
                    TimeField(
                        time = window.to,
                        onChange = { onWindow(window.copy(to = it)) },
                        label = stringResource(R.string.random_to),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = stringResource(
                        if (window.from == window.to) R.string.condition_window_error else R.string.sheet_in_window_hint,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (window.from == window.to) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            WhenKind.ANY_TIME -> {
                val awake = shape.awakeOn(date)
                Text(
                    text = stringResource(
                        R.string.sheet_any_time_hint,
                        TimeText.time(awake.from.toLocalTime(), is24h, locale),
                        TimeText.time(awake.to.toLocalTime(), is24h, locale),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
