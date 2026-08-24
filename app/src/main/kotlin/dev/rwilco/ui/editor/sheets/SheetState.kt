package dev.rwilco.ui.editor.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import java.time.LocalDate
import java.time.LocalTime

/** java.time values in saveable state, so a rotation mid-sheet keeps what was picked. */
private val localDateSaver = Saver<MutableState<LocalDate>, Long>(
    save = { it.value.toEpochDay() },
    restore = { mutableStateOf(LocalDate.ofEpochDay(it)) },
)

private val localTimeSaver = Saver<MutableState<LocalTime>, Int>(
    save = { it.value.toSecondOfDay() },
    restore = { mutableStateOf(LocalTime.ofSecondOfDay(it.toLong())) },
)

@Composable
fun rememberDate(initial: LocalDate): MutableState<LocalDate> =
    rememberSaveable(saver = localDateSaver) { mutableStateOf(initial) }

@Composable
fun rememberTime(initial: LocalTime): MutableState<LocalTime> =
    rememberSaveable(saver = localTimeSaver) { mutableStateOf(initial) }

/** Five minutes past the next full hour: a reasonable first guess for "when". */
fun nextRoundHour(now: LocalTime): LocalTime = now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
