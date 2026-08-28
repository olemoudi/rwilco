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
 * There are no chips over the calendar any more. Three guesses at a date ("mañana a las 9",
 * "el sábado a las 10") sat above a control that answers the same question completely, and each
 * of them also quietly picked the hour — so tapping one undid the choice below it and the two
 * halves of the sheet disagreed about what you had said.
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
