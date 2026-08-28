package dev.rwilco.system

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The system clock, in whatever zone the phone is in *now*.
 *
 * `Clock.systemDefaultZone()` looks like the same thing and is not: it copies the default zone
 * once, when it is built, and answers with that copy for the life of the process. The process
 * lives a long time — the place watch starts it every few minutes — so a phone that landed in
 * another zone re-armed every wall-clock moment in the zone it took off from, until something
 * happened to kill the app. `ACTION_TIMEZONE_CHANGED` does reach a live process (the framework
 * resets its default `TimeZone`), which is exactly what makes reading the zone live correct and
 * the cached copy stale. See [SystemEventsReceiver], which answers the same broadcast.
 */
class SystemZoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = Clock.system(zone)
    override fun instant(): Instant = Instant.now()
}
