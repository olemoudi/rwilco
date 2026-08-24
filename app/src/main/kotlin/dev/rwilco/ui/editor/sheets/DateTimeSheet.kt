package dev.rwilco.ui.editor.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.model.Trigger
import dev.rwilco.ui.components.PresetChip
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

/** A day on the calendar and a time; three shortcuts for the moments people actually pick. */
@Composable
fun DateTimeSheet(
    initial: Trigger.AtDateTime?,
    now: ZonedDateTime,
    onConfirm: (Trigger.AtDateTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val today = now.toLocalDate()
    var date by rememberDate(initial?.at?.toLocalDate() ?: today)
    var time by rememberTime(initial?.at?.toLocalTime() ?: nextRoundHour(now.toLocalTime()))
    val locale = currentLocale()
    val is24h = rememberIs24h()

    val presets = buildList {
        val tonight = LocalTime.of(20, 0)
        if (now.toLocalTime().isBefore(tonight)) add(today to tonight)
        add(today.plusDays(1) to LocalTime.of(9, 0))
        add(today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY)) to LocalTime.of(10, 0))
    }

    SheetScaffold(
        title = stringResource(R.string.kind_date_time),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(Trigger.AtDateTime(LocalDateTime.of(date, time))) },
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
                    selected = date == presetDate && time == presetTime,
                    onClick = {
                        date = presetDate
                        time = presetTime
                    },
                )
            }
        }
        MonthCalendar(selected = date, today = today, onSelect = { date = it }, minDate = today)
        TimeField(time = time, onChange = { time = it }, label = stringResource(R.string.sheet_time), modifier = Modifier.fillMaxWidth())
    }
}
