package dev.rwilco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.rwilco.R
import dev.rwilco.ui.format.rememberIs24h
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalTime
import java.util.Locale

private val ITEM_HEIGHT = 56.dp
private const val VISIBLE_ITEMS = 5

/** A row of the wheel: grows with the person's font size, so 32sp digits never clip at "huge". */
@Composable
private fun wheelItemHeight(): Dp = ITEM_HEIGHT * LocalDensity.current.fontScale.coerceAtLeast(1f)

/**
 * Picking a time by rolling it past a line, not by aiming at it.
 *
 * The dial this replaces put 13–23 on an inner ring barely a thumb wide, and hitting 19 with the
 * hand that is holding the phone is a small act of surgery. A wheel needs no aim at all: flick
 * anywhere down the column and it snaps, so the accurate thing and the easy thing are the same
 * gesture. It also keeps 24-hour time honest — one column of 0–23, no rings inside rings.
 *
 * The panel sits at the bottom of the screen because that is where the thumb is.
 */
@Composable
fun TimePickerDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val is24h = rememberIs24h()
    var hour by rememberSaveable { mutableIntStateOf(initial.hour) }
    var minute by rememberSaveable { mutableIntStateOf(initial.minute) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // The dialog's own "tap outside" cannot see through a full-size child, so the empty
        // space above the panel gets its own dismiss.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    // Taps on the panel are the panel's business.
                    .pointerInput(Unit) { detectTapGestures {} },
            ) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = Tokens.spacing.screen)
                        .padding(top = Tokens.spacing.xl, bottom = Tokens.spacing.md),
                ) {
                    Wheels(
                        hour = hour,
                        minute = minute,
                        is24h = is24h,
                        onHour = { hour = it },
                        onMinute = { minute = it },
                    )
                    Spacer(Modifier.height(Tokens.spacing.lg))
                    QuickMinutes(minute = minute, onPick = { minute = it })
                    Spacer(Modifier.height(Tokens.spacing.lg))
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
                        TextButton(
                            onClick = onDismiss,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.heightIn(min = Tokens.sizes.control),
                        ) {
                            Text(stringResource(R.string.sheet_cancel))
                        }
                        Button(
                            onClick = { onConfirm(LocalTime.of(hour, minute)) },
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = Tokens.sizes.control),
                        ) {
                            Text(stringResource(R.string.sheet_done), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Wheels(
    hour: Int,
    minute: Int,
    is24h: Boolean,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
) {
    val hours = remember(is24h) { if (is24h) (0..23).toList() else (1..12).toList() }
    val displayedHour = if (is24h) hour else ((hour + 11) % 12) + 1
    val afternoon = hour >= 12
    val itemHeight = wheelItemHeight()

    Box(contentAlignment = Alignment.Center) {
        // One band across both columns: the line the numbers roll past.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            NumberWheel(
                values = hours,
                selected = displayedHour,
                onSelect = { picked ->
                    onHour(if (is24h) picked else to24Hour(picked, afternoon))
                },
                label = stringResource(R.string.time_hours),
                modifier = Modifier.width(96.dp),
            )
            Text(
                text = ":",
                style = MonoStyles.countdown,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Tokens.spacing.sm),
            )
            NumberWheel(
                values = remember { (0..59).toList() },
                selected = minute,
                onSelect = onMinute,
                label = stringResource(R.string.time_minutes),
                modifier = Modifier.width(96.dp),
            )
            if (!is24h) {
                Spacer(Modifier.width(Tokens.spacing.md))
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.xs)) {
                    for (pm in listOf(false, true)) {
                        PeriodButton(
                            label = if (pm) "PM" else "AM",
                            selected = afternoon == pm,
                            onClick = { onHour(to24Hour(displayedHour, pm)) },
                        )
                    }
                }
            }
        }
    }
}

private fun to24Hour(hour12: Int, afternoon: Boolean): Int {
    val base = hour12 % 12
    return if (afternoon) base + 12 else base
}

@Composable
private fun PeriodButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val haptics = Tokens.haptics
    Surface(
        onClick = {
            haptics.perform(HapticFeedbackType.SegmentTick)
            onClick()
        },
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.semantics { this.selected = selected },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Tokens.spacing.md, vertical = Tokens.spacing.sm),
        )
    }
}

/**
 * One column of numbers that snaps to whichever is on the line.
 *
 * The list is the values repeated many times over and started in the middle, which is the cheap
 * way to make it endless in both directions: 23 rolls into 0 without a wall, and nobody has to
 * scroll back up the way they came.
 */
@Composable
private fun NumberWheel(
    values: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val haptics = Tokens.haptics
    val itemHeight = wheelItemHeight()
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }
    val loops = 400
    val middleStart = remember(values) { values.size * (loops / 2) }
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = middleStart + values.indexOf(selected).coerceAtLeast(0),
    )
    val flingBehavior = rememberSnapFlingBehavior(state)

    // Which index is on the line right now: the top visible one, unless it has already rolled
    // more than half a row past it.
    val centreIndex by remember {
        derivedStateOf {
            val index = state.firstVisibleItemIndex
            if (state.firstVisibleItemScrollOffset > itemHeightPx / 2) index + 1 else index
        }
    }

    // A tick as each number crosses the line is most of what makes a wheel feel like one.
    LaunchedEffect(state) {
        snapshotFlow { centreIndex }
            .distinctUntilChanged()
            .collect { haptics.perform(HapticFeedbackType.SegmentTick) }
    }
    // Commit only once it has come to rest: reporting every number it rolls past would set the
    // time forty times on one flick.
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) {
                    val value = values[Math.floorMod(centreIndex, values.size)]
                    if (value != selected) onSelect(value)
                }
            }
    }
    // Somebody else set the time (a preset, the other column's AM/PM): roll to it.
    LaunchedEffect(selected) {
        if (state.isScrollInProgress) return@LaunchedEffect
        if (values[Math.floorMod(centreIndex, values.size)] == selected) return@LaunchedEffect
        val target = centreIndex - Math.floorMod(centreIndex, values.size) + values.indexOf(selected)
        state.animateScrollToItem(target.coerceAtLeast(0))
    }

    LazyColumn(
        state = state,
        flingBehavior = flingBehavior,
        contentPadding = PaddingValues(vertical = itemHeight * (VISIBLE_ITEMS / 2)),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .height(itemHeight * VISIBLE_ITEMS)
            .semantics { contentDescription = label },
    ) {
        items(count = values.size * loops) { index ->
            val distance = kotlin.math.abs(index - centreIndex)
            val onLine = distance == 0
            Box(
                modifier = Modifier
                    .height(itemHeight)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "%02d".format(Locale.ROOT, values[Math.floorMod(index, values.size)]),
                    style = MonoStyles.countdown.copy(fontWeight = if (onLine) FontWeight.Bold else FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    // Fading with distance is what tells the eye which one the line is holding.
                    modifier = Modifier.alpha(
                        when (distance) {
                            0 -> 1f
                            1 -> 0.45f
                            else -> 0.2f
                        },
                    ),
                )
            }
        }
    }
}

/** The four minutes anybody actually asks for, one tap away from wherever the wheel is. */
@Composable
private fun QuickMinutes(minute: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm), modifier = Modifier.fillMaxWidth()) {
        for (quick in listOf(0, 15, 30, 45)) {
            PresetChip(
                label = ":%02d".format(Locale.ROOT, quick),
                selected = minute == quick,
                onClick = { onPick(quick) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
