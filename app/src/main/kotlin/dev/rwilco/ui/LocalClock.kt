package dev.rwilco.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import java.time.Clock
import java.time.LocalDate

/**
 * The app's own clock, for the few composables that need the time of day and have no ViewModel
 * to ask (the time picker's "Ahora"). Everything else in the app takes a `Clock` as a parameter
 * — the zone is read live from it (`SystemZoneClock`) — and this keeps that one answer whole
 * rather than letting a corner of the UI call `LocalTime.now()` on the system default zone.
 */
val LocalClock = staticCompositionLocalOf<Clock> { Clock.systemDefaultZone() }

/** Today by the app's clock, for a reading deep in a screen that has no `today` handed down. */
@Composable
fun localToday(): LocalDate {
    val clock = LocalClock.current
    return clock.instant().atZone(clock.zone).toLocalDate()
}
