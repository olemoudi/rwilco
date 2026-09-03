package dev.rwilco.diag

import dev.rwilco.model.dayShape
import dev.rwilco.data.FiringEvent
import dev.rwilco.model.AppSettings
import dev.rwilco.model.Condition
import dev.rwilco.model.DiagNote
import dev.rwilco.model.GeofenceIds
import dev.rwilco.model.NextFire
import dev.rwilco.model.Recurrence
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.model.RepeatEnd
import dev.rwilco.model.RepeatUnit
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.PlaceWatchState
import dev.rwilco.model.distanceMeters
import dev.rwilco.model.WatchLog
import dev.rwilco.model.WatchNote
import dev.rwilco.model.typicalAccuracyM
import dev.rwilco.model.watchedCircles
import dev.rwilco.model.monthlyRule
import dev.rwilco.model.weekDays
import dev.rwilco.model.nextFire
import dev.rwilco.model.nextWake
import dev.rwilco.model.pendingRules
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import dev.rwilco.model.snoozeDetail

/** The phone, the build, and the clock everything else is measured against. */
data class DiagEnv(
    val versionName: String,
    val versionCode: Int,
    val sdk: Int,
    val device: String,
    val locale: String,
    val zone: String,
    val now: Instant,
    /** How long this process has been alive: a firing that "never happened" often died with it. */
    val processUptime: Duration?,
)

/** Every way the phone can hold a reminder back, as it stands right now. */
data class DiagPermissions(
    val notifications: Boolean,
    val anyChannelMuted: Boolean,
    val fullScreenIntent: Boolean,
    val exactAlarms: Boolean,
    val overlay: Boolean,
    val usageAccess: Boolean,
    val ignoresBatteryOptimisation: Boolean,
    val backgroundRestricted: Boolean,
    /** ALL / PRIORITY / ALARMS / NONE, and whether the app may cross it. */
    val dnd: String,
    val alarmVolume: String,
    val location: String,
    val playServices: Boolean,
)

/** The backup, with nothing in it that opens anything. */
data class DiagVault(
    val enabled: Boolean,
    val repo: String,
    val cadence: String,
    val wifiOnly: Boolean,
    val lastRunAt: Instant?,
    val lastUploadedAt: Instant?,
    val lastUploadedBytes: Long?,
    val outcome: String?,
    val pending: Int,
)

/** Everything the report is made of, collected already. */
data class Diagnostics(
    val env: DiagEnv,
    val permissions: DiagPermissions,
    val settings: AppSettings,
    val reminders: List<Reminder>,
    val vault: DiagVault?,
    val notes: List<DiagNote>,
    val watch: List<WatchNote>,
    /** The newest few happenings of each reminder, by id: what rang, what was answered, when. */
    val events: Map<String, List<FiringEvent>> = emptyMap(),
    /** Which side of every circle the watch believes the phone is on, as it stands. */
    val watchState: PlaceWatchState = PlaceWatchState(),
    /**
     * The platform's own location providers that are switched on, which is a different door
     * from the one the watch uses (Play Services): the map's blue dot and the crosshair ask
     * these, and a phone where none of them answers is a phone with a working watch and no dot.
     */
    val locationProviders: List<String> = emptyList(),
)

/** How many happenings per reminder the report carries: the last few days of a daily one. */
const val DIAG_EVENTS_PER_REMINDER = 5

/** As many reminders as anybody will read; the rest are counted and named as missing. */
const val DIAG_REMINDERS = 30

/** As many log lines as fit in something somebody will paste into a conversation. */
const val DIAG_NOTES = 120

/** And the tail of the place watch's own account, which has a screen of its own for the whole of it. */
/**
 * Fifteen was about twenty minutes of a phone sitting still, and the one time this was used in
 * anger the thing worth seeing — a set of geofence crossings — was forty minutes back. A watch
 * note is one short line; twice as many is worth the paste.
 */
const val DIAG_WATCH_NOTES = 30

/**
 * The report, as one block of text to be copied and handed over.
 *
 * What is in it is what has actually been needed to fix something in this app: the moments a
 * reminder is armed for and rang at, what the rules are, what the phone allows, and the last
 * few dozen decisions. What is *not* in it is anything that would only identify the person:
 * no reminder text, no tag names, no place names, no token, no key. A place is its radius and
 * its circle rounded to a kilometre, which is enough to tell two of them apart and not enough
 * to find anybody's house.
 */
fun Diagnostics.report(): String = buildString {
    val zone = ZoneId.of(env.zone)
    val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    val short = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    fun stamp(at: Instant?): String = at?.atZone(zone)?.format(clock) ?: "-"

    appendLine("== rwilco diagnostics ==")
    appendLine("build ${env.versionName} (${env.versionCode})  android ${env.sdk}  ${env.device}  ${env.locale}")
    appendLine("zone ${env.zone}  now ${stamp(env.now)}  process up ${env.processUptime?.let { span(it) } ?: "-"}")
    appendLine("no reminder text, tags or place names here by design; circles are rounded to ~1 km")
    appendLine()

    appendLine("-- what the phone allows --")
    with(permissions) {
        appendLine("notifications=${yes(notifications)} channelMuted=${yes(anyChannelMuted)} fullScreenIntent=${yes(fullScreenIntent)} exactAlarms=${yes(exactAlarms)}")
        appendLine("overlay=${yes(overlay)} usageAccess=${yes(usageAccess)} battery=${if (ignoresBatteryOptimisation) "unrestricted" else "optimised"} background=${if (backgroundRestricted) "RESTRICTED" else "ok"}")
        appendLine("dnd=$dnd alarmVolume=$alarmVolume location=$location playServices=${yes(playServices)}")
    }
    // What this phone's positions actually come back at, which is the number that decides
    // whether a circle can ever be entered (see the places section), and which of the
    // platform's own providers are on, which is what the map's dot and crosshair ask.
    appendLine(
        "fixAcc=" + (WatchLog(watch).typicalAccuracyM()?.let { "~${it}m" } ?: "-") +
            " providers=" + locationProviders.joinToString("+").ifEmpty { "none" },
    )
    appendLine()

    appendLine("-- settings that decide when things ring --")
    with(settings) {
        appendLine("defaultTime=$defaultTime dayStart=$dayStart weekend=$weekendDay@$weekendTime")
        appendLine("actions=${defaultActions.joinToString("+") { it.name.take(2) }} sound=${alertSound.javaClass.simpleName}${insistentSound?.let { "/${it.javaClass.simpleName}" } ?: ""} plays=$soundPlays gap=${soundGapMinutes}m vibration=${vibration.strength}/${vibration.rhythm}")
        appendLine("stacking=$alertStacking updatesWifiOnly=${yes(updatesWifiOnly)} presets=${presets.size} places=${savedPlaces.size}")
    }
    appendLine()

    vault?.let {
        appendLine("-- backup --")
        appendLine("enabled=${yes(it.enabled)} repo=${it.repo} cadence=${it.cadence} wifiOnly=${yes(it.wifiOnly)} pending=${it.pending}")
        appendLine("lastRun=${stamp(it.lastRunAt)} lastUpload=${stamp(it.lastUploadedAt)} size=${it.lastUploadedBytes ?: "-"} outcome=${it.outcome ?: "-"}")
        appendLine()
    }

    val active = reminders.count { it.status == Status.ACTIVE }
    val paused = reminders.count { it.status == Status.PAUSED }
    val done = reminders.count { it.status == Status.DONE }
    // **A reminder the log talks about is never one of the ones left out.** The list is the
    // most recently edited thirty, which is the right cut for reading a phone at rest and the
    // wrong one for reading a bug: the reminder dropped at 20:00 was edited weeks ago, so the
    // one line that explained the drop named a reminder whose rules were not in the report.
    val mentioned = notes.take(DIAG_NOTES)
        .flatMapTo(HashSet()) { note -> MENTIONED.findAll(note.text).map { it.groupValues[1] } }
    val named = reminders.filter { it.id.take(8) in mentioned }
    val shown = (named + reminders.sortedByDescending { it.updatedAt }).distinct().take(maxOf(DIAG_REMINDERS, named.size))
    appendLine("-- reminders: ${reminders.size} ($active active, $paused paused, $done done) --")
    for (reminder in shown) {
        appendLine(reminder.identityLine())
        appendLine("    " + reminder.stateLine(env.now, zone, settings, stampOf = ::stamp))
        // One firing deep was all the row could say; this is the few before it.
        events[reminder.id]?.takeIf { it.isNotEmpty() }?.let { history ->
            appendLine("    hist=" + history.joinToString(" ") { "${it.at.atZone(zone).format(short)}:${it.kind.name.lowercase()}${it.ruleIndex?.let { r -> "/r$r" } ?: ""}" })
        }
    }
    if (reminders.size > shown.size) {
        appendLine("... ${reminders.size - shown.size} more not listed (oldest by last edit; every one the log names is above)")
    }
    appendLine()

    // **One line per circle, which the rest of the report could only ever say in pieces.**
    // A rule says what it asks for, a watch line says what one look saw, and neither says the
    // thing somebody actually wants to know about a place reminder that never rang: which side
    // the watch believes the phone is on, when it last said so, whether the circle is being
    // watched at all right now — and whether this phone can settle a circle that small in the
    // first place. Grouped by the circle rather than by the reminder ([GeofenceIds.tag]),
    // because the same doorway named by six rules is one place, and six lines about it is the
    // log telling the truth and saying nothing.
    val typical = WatchLog(watch).typicalAccuracyM()
    val circles = reminders
        .filter { it.status == Status.ACTIVE }
        .flatMap { reminder ->
            reminder.watchedCircles(env.now, zone, settings.defaultTime, settings.dayShape, settings.dayStart)
                .map { gated -> reminder to gated }
        }
    if (circles.isNotEmpty()) {
        appendLine("-- places being watched: ${circles.size} circles --")
        for ((tag, group) in circles.groupBy { (_, gated) -> GeofenceIds.tag(gated.place.lat, gated.place.lng, gated.place.radiusM) }) {
            val place = group.first().second.place
            // What the watch believes, per circle id — the same pin read as a doorway and as a
            // state are two ids on purpose, and they can honestly disagree.
            val sides = group.mapNotNull { (_, gated) -> watchState.inside[gated.place.id] }.distinct()
            val side = when {
                sides.isEmpty() -> "?"
                sides.size > 1 -> "y/n"
                else -> yes(sides.single())
            }
            // The last thing the log said about this circle, whichever look it was.
            val lastSeen = watch.firstOrNull { note ->
                note.lat != null && note.lng != null && note.radiusM != null &&
                    GeofenceIds.tag(note.lat!!, note.lng!!, note.radiusM!!) == tag
            }
            val opens = group.mapNotNull { (_, gated) -> gated.opensAt }
            val gate = when {
                group.any { (_, gated) -> gated.opensAt == null } -> "open"
                else -> "shut until ${stamp(opens.min())}"
            }
            // **How far the phone is from the middle of it, by the watch's own last fix.** With
            // the side beside it this is the hysteresis made visible: `150m in=y d=247m` says
            // in one line that the watch still holds the phone inside a circle it is well
            // outside the middle of, which is what leaving takes (radius + the fix's doubt) and
            // what nothing in the report could be asked before.
            val away = watchState.lastFix?.let { fix -> distanceMeters(fix.lat, fix.lng, place.lat, place.lng) }
            appendLine(
                "#$tag ${place.radiusM}m x${group.size} in=$side" +
                    (away?.let { " d=${it.toInt()}m" } ?: " d=-") +
                    (lastSeen?.let { " seen=${it.at.atZone(zone).format(short)}" } ?: " seen=-") +
                    " gate=$gate" +
                    " r=" + group.map { (reminder, _) -> reminder.id.take(8) }.distinct().joinToString(",") +
                    // The whole of yesterday's puzzle in one clause: arriving takes a fix at
                    // least as tight as the circle ([insideAfter]), so a circle under this
                    // phone's own doubt is one the watch can never see anybody arrive at.
                    (if (typical != null && typical > place.radiusM) "  UNDER FIX DOUBT (~${typical}m): no arrival can be seen" else ""),
            )
        }
        appendLine()
    }

    appendLine("-- log: last ${minOf(notes.size, DIAG_NOTES)} of ${notes.size} --")
    for (note in notes.take(DIAG_NOTES)) {
        appendLine("${note.at.atZone(zone).format(short)} ${note.tag.padEnd(6)} ${note.text}")
    }
    if (watch.isNotEmpty()) {
        appendLine()
        appendLine("-- place watch: last ${minOf(watch.size, DIAG_WATCH_NOTES)} of ${watch.size} --")
        for (note in watch.take(DIAG_WATCH_NOTES)) {
            appendLine(
                "${note.at.atZone(zone).format(short)} ${note.kind.name.padEnd(5)}" +
                    // Which circle, rounded to about a kilometre like every other one here:
                    // enough to tell four co-located circles apart, not enough to find a door.
                    // The circle, by the tag that joins it to a rule and to the places section
                    // above — and rounded to about a kilometre like every other one here.
                    (
                        note.lat?.let { lat ->
                            note.lng?.let { lng ->
                                " " + (note.radiusM?.let { "#${GeofenceIds.tag(lat, lng, it)} " } ?: "") +
                                    "@${fixed(lat, 2)},${fixed(lng, 2)}${note.radiusM?.let { "/${it}m" } ?: ""}"
                            }
                        } ?: ""
                        ) +
                    (note.gapM?.let { " gap=${it.toInt()}m" } ?: "") +
                    (note.accuracyM?.let { " acc=${it}m" } ?: "") +
                    (note.inside?.let { " inside=${yes(it)}" } ?: "") +
                    // What the system claimed and what came of it: an `inside` on its own cannot
                    // tell a crossing that rang the phone from one that fell on the floor, and on
                    // a strictly-held echo there is no `inside` at all to read the claim off.
                    (note.reported?.let { " said=${if (it) "in" else "out"}" } ?: "") +
                    (note.acted?.let { " acted=${yes(it)}" } ?: "") +
                    (note.speedMps?.let { " v=${fixed(it, 1)}" } ?: "") +
                    (note.waitS?.let { " next=${it / 60}m" } ?: "") +
                    (note.charge?.let { " bat=$it" } ?: "") +
                    (if (note.isPoll) " ${note.tier.name.lowercase()}" else ""),
            )
        }
    }
    appendLine("== end ==")
}

/** How the log names a reminder (`r=0f1e2d3c`), so the ones it talks about can be kept. */
private val MENTIONED = Regex("r=([0-9a-f]{8})")

/** `#0f1e2d3c ACTIVE ANY t=13 g=2 rules=[…] rec=…` — who it is and what it asks for. */
private fun Reminder.identityLine(): String = buildString {
    append("#${id.take(8)} $status $ruleMatch t=${text.length} g=${tags.size}")
    append(" rules=[").append(rules.joinToString(" | ") { it.describe() }).append("]")
    append(" rec=").append(recurrence.describe())
}

/** `armed=… fired=… dealt=… next=…` — every stamp the firing path decides from. */
private fun Reminder.stateLine(now: Instant, zone: ZoneId, settings: AppSettings, stampOf: (Instant?) -> String): String = buildString {
    append("armed=${stampOf(armedFor)}${armedRule?.let { "/r$it" } ?: ""}")
    append(" fired=${stampOf(lastFiredAt)}${lastFiredRule?.let { "/r$it" } ?: ""} dealt=${stampOf(lastDealtAt)} snooze=${stampOf(snoozedUntil)}${snoozedToPlace?.let { "/" + it.snoozeDetail().substringBefore(':') } ?: ""}")
    if (firedRules.isNotEmpty()) append(" fr=${firedRules.sorted()}")
    if (status == Status.ACTIVE) {
        val next = nextFire(this@stateLine, now, zone, settings.defaultTime, settings.dayStart, settings.dayShape)
        val wake = nextWake(this@stateLine, now, zone, settings.defaultTime, settings.dayStart, settings.dayShape)
        append(" next=").append(next.describe(stampOf))
        append(" wake=${stampOf(wake?.at)}${wake?.ruleIndex?.let { "/r$it" } ?: ""}")
        val pending = pendingRules()
        if (pending.size != rules.size) append(" pending=$pending")
    }
}

private fun NextFire?.describe(stampOf: (Instant?) -> String): String = when (this) {
    null -> "none"
    is NextFire.Scheduled -> "at ${stampOf(at)}${if (snoozed) " (snoozed)" else ""}"
    is NextFire.Sometime -> "sometime ${stampOf(windowStart)}..${stampOf(windowEnd)}"
    is NextFire.WhenAt -> "place ${trigger.describeCircle()}"
}

private fun TriggerRule.describe(): String =
    trigger.describe() + if (conditions.isEmpty()) "" else conditions.joinToString(",", prefix = " if(", postfix = ")") { it.diagLine() }

private fun Trigger.describe(): String = when (this) {
    is Trigger.AtDateTime -> "at $at"
    is Trigger.OnDate -> "on $date"
    is Trigger.AtTime -> "time $time ${days.describe()}"
    is Trigger.DayRandom -> "on $date at random"
    is Trigger.RelativeDate -> "relative $day" + (time?.let { " at $it" } ?: window?.let { " in $it" } ?: " left to the day")
    is Trigger.Repeat -> "repeat every $every ${unit.name.lowercase()}" +
        (time?.let { " at $it" } ?: " at random") +
        (if (unit == RepeatUnit.WEEK) " ${weekDays().describe()}" else "") +
        (if (unit == RepeatUnit.MONTH) " ${monthlyRule()}" else "") +
        " from $startsOn" + (if (ends == RepeatEnd.Never) "" else " until $ends")
    is Trigger.Interval -> "window $from-$to ${days.describe()}"
    is Trigger.DateRange -> "dates $from..$to"
    is Trigger.TimeOfDay -> "hour $time ${days.describe()}"
    is Trigger.Weekday -> "weekday ${days.describe()}"
    is Trigger.Countdown -> "countdown ${minutes}m started=${startedAt ?: "-"}"
    is Trigger.Location -> "place ${describeCircle()}"
    is Trigger.Random -> "random $timesPer/$period $from-$to ${days.describe()}"
}

/**
 * Rounded to two decimals — about a kilometre: two rules on one circle still match, and that is
 * all. The tag in front is what actually joins them ([GeofenceIds.tag]): the rounding put four
 * circles in one neighbourhood under one string, so a rule, a watch line and a place's own
 * state could not be told to be about the same circle or three different ones.
 */
private fun Trigger.Location.describeCircle(): String =
    "#${GeofenceIds.tag(lat, lng, radiusM)} ${radiusM}m $presence${if (onCrossing) "/crossing" else ""} @${fixed(lat, 2)},${fixed(lng, 2)}"

/**
 * One condition, in the report's own words. Internal rather than private because the firing
 * names the condition that dropped a ring with it ([dev.rwilco.alarm.ReminderFiring]): the log
 * and the report saying the same fence two different ways is how a reader stops trusting either.
 */
internal fun Condition.diagLine(): String = when (this) {
    is Condition.TimeWindow -> "win $from-$to ${days.describe()}"
    is Condition.DateRange -> "dates $from..$to"
    is Condition.OnDays -> "days ${days.describe()}"
    is Condition.AtPlace -> "at #${GeofenceIds.tag(lat, lng, radiusM)} ${radiusM}m @${fixed(lat, 2)},${fixed(lng, 2)} in=${yes(inside)}"
}

private fun Recurrence.describe(): String = when (this) {
    Recurrence.None -> "none"
    Recurrence.ByTrigger -> "byTrigger"
    is Recurrence.After -> "after $amount $unit"
    is Recurrence.MonthlyWeekday -> "monthly $ordinal $day"
    is Recurrence.Calendar -> "calendar " + repeat.describe() + conditions.joinToString("") { " +" + it.diagLine() }
}

private fun Set<java.time.DayOfWeek>.describe(): String =
    if (isEmpty() || size == 7) "d=*" else "d=" + sorted().joinToString("") { it.value.toString() }

private fun yes(value: Boolean): String = if (value) "y" else "n"

/**
 * A number with a dot in it, whatever the phone's language is. A coordinate written the Spanish
 * way — `@40,42,-3,70` — is four numbers where two were meant, and this report is read by
 * somebody who was not there.
 */
private fun fixed(value: Double, decimals: Int): String = String.format(java.util.Locale.ROOT, "%.${decimals}f", value)

private fun span(duration: Duration): String {
    val hours = duration.toHours()
    val minutes = duration.toMinutes() % 60
    return if (hours > 0) "${hours}h${minutes}m" else "${minutes}m"
}
