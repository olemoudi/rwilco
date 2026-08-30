package dev.rwilco.model

/** A hard limit of 100 geofences per app: past that Play Services refuses the whole batch. */
const val MAX_GEOFENCES = 100

/**
 * The circles worth a geofence right now, as (geofence id, circle) pairs — the whole of what
 * `GeofenceManager.sync` registers, and nothing of how. Kept here because which places deserve
 * one of the hundred fences is arithmetic on the rules, and arithmetic is what a JVM test can
 * hold still; the manager keeps the radios.
 */
fun geofenceChoices(reminders: List<Reminder>): List<Pair<String, Trigger.Location>> =
    reminders
        .filter { it.status == Status.ACTIVE }
        .flatMap { reminder ->
            // Put off until a place: that circle is the reminder's only one until it rings,
            // and the rules' own are outranked exactly as they are by a clock snooze.
            reminder.snoozedToPlace?.let { door -> return@flatMap listOf(GeofenceIds.encodeSnooze(reminder.id, door) to door) }
            // Only the rules still waiting to happen. Under "todos" a place that has already
            // been ticked off has nothing left to report, and a geofence is not free: a
            // hundred is the app's whole allowance and Play Services watches every one of
            // them. The circles named by *conditions* are not here at all — a geofence
            // reports a crossing and a condition has none; PlaceWatcher tracks their state
            // instead.
            val pending = reminder.pendingRules().toSet()
            val accumulates = reminder.ruleMatch == RuleMatch.ALL && reminder.rulesCombine
            reminder.rules.mapIndexedNotNull { index, rule ->
                val place = rule.trigger as? Trigger.Location ?: return@mapIndexedNotNull null
                // Except a place under "todos", which is a state: ticked off, it is still
                // watched for the crossing back, and the system is the eye that sees it first.
                val ticked = accumulates && index in reminder.firedRules
                if (index !in pending && !ticked) return@mapIndexedNotNull null
                GeofenceIds.encode(reminder.id, index, place) to place
            }
        }
        // Past the hard limit the newest places are the ones that get watched rather than
        // nothing at all — and the circle a snooze waits at is never among the ones cut,
        // whatever the age of its reminder: it is the whole of that alarm, with nothing on
        // the clock behind it.
        .let { all ->
            val (waited, rest) = all.partition { GeofenceIds.isSnooze(it.first) }
            rest.takeLast((MAX_GEOFENCES - waited.size).coerceAtLeast(0)) + waited.takeLast(MAX_GEOFENCES)
        }
