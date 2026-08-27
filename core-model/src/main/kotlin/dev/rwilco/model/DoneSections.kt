package dev.rwilco.model

import java.time.Instant
import java.time.ZoneId

/**
 * How the history reads: today, the week behind it, and everything before that.
 *
 * A flat list of everything ever finished answers one question — "did I do it?" — and the answer
 * is almost always about something recent. Three bands is what makes that answer findable
 * without a scroll: what got done today, what got done this week, and the rest, which is a place
 * to look rather than a place to read. Declaration order is display order.
 */
enum class DoneSection { TODAY, LAST_WEEK, EARLIER }

/**
 * How far back the history goes.
 *
 * Three months is long enough to answer "when did I last change the filter?" and short enough
 * that the list never becomes an archive nobody asked to keep. Past it a reminder is swept
 * ([expiredDone]); the vault's copy goes with it, because the backup is a copy of the database
 * and not a second, longer memory.
 */
const val DONE_KEPT_MONTHS = 3L

/**
 * The moment a finished reminder is counted from: when it was dealt with, or — for a row filed
 * before that moment was written down — when it was last touched.
 *
 * Never null, which is what stops a row being unsortable in one direction and immortal in the
 * other: something with no [Reminder.doneAt] would have no band to sit in and no age to be swept
 * at.
 */
fun Reminder.finishedAt(): Instant = doneAt ?: updatedAt

/**
 * The history in its three bands, newest first inside each. Only the bands with something in
 * them, in [DoneSection] order.
 *
 * The bands are days and not spans of hours: something finished at one in the morning was
 * finished *today*, and something finished eight days ago is not "this week" because it happens
 * to be within 168 hours.
 */
fun groupDone(
    reminders: List<Reminder>,
    now: Instant,
    zone: ZoneId,
): Map<DoneSection, List<Reminder>> {
    val today = now.atZone(zone).toLocalDate()
    val weekAgo = today.minusDays(6)
    return reminders
        .sortedByDescending { it.finishedAt() }
        .groupBy { reminder ->
            val day = reminder.finishedAt().atZone(zone).toLocalDate()
            when {
                !day.isBefore(today) -> DoneSection.TODAY
                !day.isBefore(weekAgo) -> DoneSection.LAST_WEEK
                else -> DoneSection.EARLIER
            }
        }
        .toSortedMap(compareBy { it.ordinal })
}

/** The moment before which a finished reminder is not kept. See [DONE_KEPT_MONTHS]. */
fun doneCutoff(now: Instant, zone: ZoneId): Instant =
    now.atZone(zone).minusMonths(DONE_KEPT_MONTHS).toInstant()

/**
 * The ids of the finished reminders too old to keep, for the sweep that runs beside the re-arm.
 *
 * Only [Status.DONE] rows: pausing something for four months is a decision, and forgetting it
 * would be the app overruling it.
 */
fun expiredDone(reminders: List<Reminder>, now: Instant, zone: ZoneId): List<String> {
    val cutoff = doneCutoff(now, zone)
    return reminders.filter { it.status == Status.DONE && it.finishedAt() < cutoff }.map { it.id }
}
