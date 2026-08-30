package dev.rwilco.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * −/+ around a mono reading; each step ticks.
 *
 * **Held, it keeps going.** A step is one unit on purpose (a countdown of three minutes has to
 * be sayable), and the price of that was seventeen taps from thirty to forty-seven. So a button
 * held past [Motion.holdRepeatDelay] repeats, quickening to [Motion.holdRepeatFloor], and the
 * tap that lets go is not counted again: the click Material fires on release is swallowed when
 * the hold has already stepped. Every repeat ticks like a tap, so the thumb can count.
 */
@Composable
fun Stepper(
    valueLabel: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
    decrementEnabled: Boolean = true,
    incrementEnabled: Boolean = true,
) {
    val colors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        RepeatingButton(onStep = onDecrement, enabled = decrementEnabled, colors = colors) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.stepper_less))
        }
        Text(
            text = valueLabel,
            style = MonoStyles.time,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        RepeatingButton(onStep = onIncrement, enabled = incrementEnabled, colors = colors) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.stepper_more))
        }
    }
}

/** One of the two: a tap steps once, a hold steps until it lets go. */
@Composable
private fun RepeatingButton(
    onStep: () -> Unit,
    enabled: Boolean,
    colors: androidx.compose.material3.IconButtonColors,
    content: @Composable () -> Unit,
) {
    val haptics = Tokens.haptics
    val motion = Tokens.motion
    val scope = rememberCoroutineScope()
    val step by rememberUpdatedState(onStep)
    val held = remember { HeldState() }
    FilledTonalIconButton(
        onClick = {
            // The release of a hold arrives here as a click. It has already stepped.
            if (held.repeated) {
                held.repeated = false
                return@FilledTonalIconButton
            }
            haptics.perform(HapticFeedbackType.SegmentTick)
            step()
        },
        enabled = enabled,
        colors = colors,
        modifier = Modifier
            .size(Tokens.sizes.touch)
            // Outside the button's own click handling in the chain, so it sees the press first
            // and the release last; nothing here is consumed, so the ripple and the click still
            // happen — the click is only ignored when the hold has done the stepping.
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    held.repeated = false
                    val repeating: Job = scope.launch {
                        delay(motion.holdRepeatDelay.toLong())
                        var interval = motion.holdRepeatStart.toFloat()
                        while (true) {
                            held.repeated = true
                            haptics.perform(HapticFeedbackType.SegmentTick)
                            step()
                            delay(interval.toLong())
                            interval = (interval * motion.holdRepeatQuicken).coerceAtLeast(motion.holdRepeatFloor.toFloat())
                        }
                    }
                    waitForUpOrCancellation()
                    repeating.cancel()
                }
            },
    ) { content() }
}

/** Whether the hold in progress has stepped on its own; a plain field, read once at the click. */
private class HeldState {
    var repeated: Boolean = false
}
