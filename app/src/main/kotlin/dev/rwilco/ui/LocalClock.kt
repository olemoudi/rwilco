package dev.rwilco.ui

import androidx.compose.runtime.staticCompositionLocalOf
import java.time.Clock

/**
 * The app's own clock, for the few composables that need the time of day and have no ViewModel
 * to ask (the time picker's "Ahora"). Everything else in the app takes a `Clock` as a parameter
 * — the zone is read live from it (`SystemZoneClock`) — and this keeps that one answer whole
 * rather than letting a corner of the UI call `LocalTime.now()` on the system default zone.
 */
val LocalClock = staticCompositionLocalOf<Clock> { Clock.systemDefaultZone() }
