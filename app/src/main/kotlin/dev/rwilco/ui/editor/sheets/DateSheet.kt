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
import dev.rwilco.model.Trigger
import dev.rwilco.model.awakeOn
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
    var atRandom by rememberSaveable { mutableStateOf(initial is Trigger.DayRandom) }
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
            onConfirm(if (atRandom) Trigger.DayRandom(date) else Trigger.AtDateTime(LocalDateTime.of(date, time)))
        },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            for ((presetDate, presetTime) in presets) {
                val label = dayWord(presetDate, today, locale) + " " + TimeText.time(presetTime, is24h, locale)
                PresetChip(
                    label = label.replaceFirstChar { it.titlecase(locale) },
                    selected = !atRandom && date == presetDate && time == presetTime,
                    onClick = {
                        date = presetDate
                        time = presetTime
                        atRandom = false
                    },
                )
            }
        }
        MonthCalendar(selected = date, today = today, onSelect = { date = it }, minDate = today)
        WhenInTheDay(
            atRandom = atRandom,
            onAtRandom = { atRandom = it },
            time = time,
            onTime = { time = it },
            date = date,
            shape = shape,
        )
    }
}

/**
 * The two answers to "when in the day": an hour, or none of your business.
 *
 * Shared by the date tile and the recurrence tile, because it is the same question and has to
 * be the same control. The hint under the random half is the day's own window, worked out for
 * whichever day is selected — a Saturday and a Tuesday say different things, which is the whole
 * point of having asked for the hours in the first place.
 */
@Composable
fun WhenInTheDay(
    atRandom: Boolean,
    onAtRandom: (Boolean) -> Unit,
    time: LocalTime,
    onTime: (LocalTime) -> Unit,
    date: java.time.LocalDate,
    shape: DayShape,
) {
    val is24h = rememberIs24h()
    val locale = currentLocale()
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
        SegmentedChoice(
            options = listOf(
                stringResource(R.string.sheet_at_this_time),
                stringResource(R.string.sheet_random_in_day),
            ),
            selectedIndex = if (atRandom) 1 else 0,
            onSelect = { onAtRandom(it == 1) },
        )
        if (atRandom) {
            val window = shape.awakeOn(date)
            Text(
                text = stringResource(
                    R.string.sheet_random_in_day_hint,
                    TimeText.time(window.from.toLocalTime(), is24h, locale),
                    TimeText.time(window.to.toLocalTime(), is24h, locale),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TimeField(
                time = time,
                onChange = onTime,
                label = stringResource(R.string.sheet_time),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
