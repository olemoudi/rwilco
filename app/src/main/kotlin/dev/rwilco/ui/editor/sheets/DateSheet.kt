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
import dev.rwilco.ui.format.dayWord
import dev.rwilco.ui.format.rememberIs24h
import dev.rwilco.ui.theme.Tokens
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * A day, and then the question of when in it.
 *
 * One tile, where there were two. "Fecha" and "fecha y hora" asked the same thing and differed
 * only in whether the hour came from the settings, which is not a decision anybody wants to
 * make before picking a day — and picking the wrong tile meant going back and starting again.
 * So the hour is here, filled in with the usual one, and the other answer to "when in it" is
 * beside it: leave it to the day, which draws a moment from the hours this person is up.
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
            // What the old date-only trigger always meant, now written down where it can be seen.
            else -> defaultTime
        },
    )
    val startingWindow = (initial as? Trigger.DayRandom)?.window
    var kindName by rememberSaveable {
        mutableStateOf(
            when {
                startingWindow != null -> WhenKind.IN_WINDOW
                initial is Trigger.DayRandom -> WhenKind.ANY_TIME
                else -> WhenKind.AT_TIME
            }.name,
        )
    }
    val kind = WhenKind.valueOf(kindName)
    var windowFrom by rememberTime(startingWindow?.from ?: LocalTime.of(14, 0))
    var windowTo by rememberTime(startingWindow?.to ?: LocalTime.of(16, 0))
    val window = DayWindow(windowFrom, windowTo)
    val locale = currentLocale()
    val is24h = rememberIs24h()

    val presets = buildList {
        val tonight = LocalTime.of(20, 0)
        if (now.toLocalTime().isBefore(tonight)) add(today to tonight)
        add(today.plusDays(1) to LocalTime.of(9, 0))
        add(today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY)) to LocalTime.of(10, 0))
    }

    SheetScaffold(
        title = stringResource(R.string.kind_date),
        onDismiss = onDismiss,
        onConfirm = {
            onConfirm(
                when (kind) {
                    WhenKind.AT_TIME -> Trigger.AtDateTime(LocalDateTime.of(date, time))
                    WhenKind.IN_WINDOW -> Trigger.DayRandom(date, window)
                    WhenKind.ANY_TIME -> Trigger.DayRandom(date)
                },
            )
        },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
        // A stretch with no width has no moment in it.
        confirmEnabled = kind != WhenKind.IN_WINDOW || windowFrom != windowTo,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            for ((presetDate, presetTime) in presets) {
                val label = dayWord(presetDate, today, locale) + " " + TimeText.time(presetTime, is24h, locale)
                PresetChip(
                    label = label.replaceFirstChar { it.titlecase(locale) },
                    selected = kind == WhenKind.AT_TIME && date == presetDate && time == presetTime,
                    onClick = {
                        date = presetDate
                        time = presetTime
                        kindName = WhenKind.AT_TIME.name
                    },
                )
            }
        }
        MonthCalendar(selected = date, today = today, onSelect = { date = it }, minDate = today)
        WhenInTheDay(
            kind = kind,
            onKind = { kindName = it.name },
            time = time,
            onTime = { time = it },
            window = window,
            onWindow = { windowFrom = it.from; windowTo = it.to },
            date = date,
            shape = shape,
            savedWindows = savedWindows,
        )
    }
}

/** The three answers to "when in the day", narrowing from an hour to the whole of it. */
enum class WhenKind { AT_TIME, IN_WINDOW, ANY_TIME }

/**
 * When in the day: an hour I pick, somewhere inside a stretch I name, or none of your business.
 *
 * Shared by the date tile and the recurrence tile, because it is the same question and has to be
 * the same control. The three narrow in order — a moment, a stretch, the day — and the middle
 * one is the one that needed a name for a stretch to be worth having: "a la hora de comer" is a
 * thing people say and "entre las 14:00 y las 16:00" is a thing they have to think about. The
 * chips are those names ([SavedWindow], kept in the settings); the two fields under them are
 * always there, so a stretch nobody has named is still one tap further than a chip and not a
 * trip to the settings.
 *
 * The hint under the last one is the day's own window, worked out for whichever day is selected
 * — a Saturday and a Tuesday say different things, which is the whole point of having asked for
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
                stringResource(R.string.sheet_at_this_time),
                stringResource(R.string.sheet_in_window),
                stringResource(R.string.sheet_random_in_day),
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
                        R.string.sheet_random_in_day_hint,
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
