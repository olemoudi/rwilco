package dev.rwilco.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The next few things this reminder will do, for reading back in the editor: the first is
 * exactly [nextFire], and each one after it is what the same question answers once that moment
 * is spent the way the firing spends it (`lastFiredAt`, which is what [searchFrom] reads).
 *
 * The walk stops where the next moment is not the model's to know: after a random draw (the
 * window is shown, never the draw, and the draw after it even less so), after a snooze, and
 * after the ring of an "all of them" set — the next round of that needs a "hecho" nobody has
 * given. A span counted from the "hecho" stops on its own for the same reason: with nothing
 * dealt with there is nothing to count from. A place is no moment at all, but a list that
 * would start with one *says so* (0.68.0): "suena al llegar a casa" is what will happen, and
 * a blank line under a place reminder read as the app having nothing to say about it.
 */
fun upcomingMoments(
    reminder: Reminder,
    now: Instant,
    zone: ZoneId,
    defaultTime: LocalTime,
    dayStart: LocalTime = DEFAULT_DAY_START,
    shape: DayShape = DayShape.DEFAULT,
    count: Int = 3,
): List<NextFire> {
    val out = ArrayList<NextFire>(count)
    var current = reminder
    while (out.size < count) {
        val next = nextFire(current, now, zone, defaultTime, dayStart, shape) ?: break
        if (next is NextFire.WhenAt) {
            if (out.isEmpty()) out += next
            break
        }
        out += next
        if (next is NextFire.Sometime) break
        if (next is NextFire.Scheduled && next.snoozed) break
        if (reminder.ruleMatch == RuleMatch.ALL && reminder.rulesCombine) break
        current = current.copy(lastFiredAt = next.moment ?: break)
    }
    return out
}
