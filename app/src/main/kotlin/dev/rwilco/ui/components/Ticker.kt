package dev.rwilco.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.Instant

/**
 * The current instant, refreshed every [periodMs] and aligned to it (a one-second ticker fires on
 * the second), only while the screen is resumed. Read it as close to the text that needs it as
 * possible: whatever reads this state recomposes on every tick.
 */
@Composable
fun rememberNow(periodMs: Long, clock: Clock): State<Instant> {
    val lifecycleOwner = LocalLifecycleOwner.current
    return produceState(initialValue = clock.instant(), lifecycleOwner, periodMs) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = clock.instant()
                delay(periodMs - Math.floorMod(clock.millis(), periodMs))
            }
        }
    }
}
