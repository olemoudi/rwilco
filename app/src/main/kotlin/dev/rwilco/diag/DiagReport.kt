package dev.rwilco.diag

import dev.rwilco.model.dayShape
import dev.rwilco.model.AppSettings
import dev.rwilco.model.Condition
import dev.rwilco.model.DiagNote
import dev.rwilco.model.NextFire
import dev.rwilco.model.Recurrence
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.model.RepeatEnd
import dev.rwilco.model.RepeatUnit
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.model.WatchNote
import dev.rwilco.model.monthlyRule
import dev.rwilco.model.weekDays
import dev.rwilco.model.nextFire
import dev.rwilco.model.nextWake
import dev.rwilco.model.pendingRules
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
)

/** As many reminders as anybody will read; the rest are counted and named as missing. */
const val DIAG_REMINDERS = 30

/** As many log lines as fit in something somebody will paste into a conversation. */
const val DIAG_NOTES = 120

/** And the tail of the place watch's own account, which has a screen of its own for the whole of it. */
const val DIAG_WATCH_NOTES = 15

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
    appendLine()

    appendLine("-- settings that decide when things ring --")
    with(settings) {
        appendLine("defaultTime=$defaultTime dayStart=$dayStart weekend=$weekendDay@$weekendTime")
        appendLine("actions=${defaultActions.joinToString("+") { it.name.take(2) }} sound=${alertSound.javaClass.simpleName} plays=$soundPlays gap=${soundGapMinutes}m vibration=${vibration.strength}/${vibration.rhythm}")
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
    val shown = reminders.sortedByDescending { it.updatedAt }.take(DIAG_REMINDERS)
    appendLine("-- reminders: ${reminders.size} ($active active, $paused paused, $done done) --")
    for (reminder in shown) {
        appendLine(reminder.identityLine())
        appendLine("    " + reminder.stateLine(env.now, zone, settings, stampOf = ::stamp))
    }
    if (reminders.size > shown.size) appendLine("... ${reminders.size - shown.size} more not listed (oldest by last edit)")
    appendLine()

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
                    (note.gapM?.let { " gap=${it.toInt()}m" } ?: "") +
                    (note.inside?.let { " inside=${yes(it)}" } ?: "") +
                    (note.speedMps?.let { " v=${fixed(it, 1)}" } ?: "") +
                    (note.waitS?.let { " next=${it / 60}m" } ?: "") +
                    (note.charge?.let { " bat=$it" } ?: "") +
                    (if (note.precise) " gps" else ""),
            )
        }
    }
    appendLine("== end ==")
}

/** `#0f1e2d3c ACTIVE ANY t=13 g=2 rules=[…] rec=…` — who it is and what it asks for. */
private fun Reminder.identityLine(): String = buildString {
    append("#${id.take(8)} $status $ruleMatch t=${text.length} g=${tags.size}")
    append(" rules=[").append(rules.joinToString(" | ") { it.describe() }).append("]")
    append(" rec=").append(recurrence.describe())
}

/** `armed=… fired=… dealt=… next=…` — every stamp the firing path decides from. */
private fun Reminder.stateLine(now: Instant, zone: ZoneId, settings: AppSettings, stampOf: (Instant?) -> String): String = buildString {
    append("armed=${stampOf(armedFor)}${armedRule?.let { "/r$it" } ?: ""}")
    append(" fired=${stampOf(lastFiredAt)} dealt=${stampOf(lastDealtAt)} snooze=${stampOf(snoozedUntil)}")
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
    trigger.describe() + if (conditions.isEmpty()) "" else conditions.joinToString(",", prefix = " if(", postfix = ")") { it.describe() }

private fun Trigger.describe(): String = when (this) {
    is Trigger.AtDateTime -> "at $at"
    is Trigger.OnDate -> "on $date"
    is Trigger.AtTime -> "time $time ${days.describe()}"
    is Trigger.DayRandom -> "on $date at random"
    is Trigger.Repeat -> "repeat every $every ${unit.name.lowercase()}" +
        (time?.let { " at $it" } ?: " at random") +
        (if (unit == RepeatUnit.WEEK) " ${weekDays().describe()}" else "") +
        (if (unit == RepeatUnit.MONTH) " ${monthlyRule()}" else "") +
        " from $startsOn" + (if (ends == RepeatEnd.Never) "" else " until $ends")
    is Trigger.Interval -> "window $from-$to ${days.describe()}"
    is Trigger.Countdown -> "countdown ${minutes}m started=${startedAt ?: "-"}"
    is Trigger.Location -> "place ${describeCircle()}"
    is Trigger.Random -> "random $timesPer/$period $from-$to ${days.describe()}"
}

/** Rounded to two decimals — about a kilometre: two rules on one circle still match, and that is all. */
private fun Trigger.Location.describeCircle(): String = "${radiusM}m $transition @${fixed(lat, 2)},${fixed(lng, 2)}"

private fun Condition.describe(): String = when (this) {
    is Condition.TimeWindow -> "win $from-$to ${days.describe()}"
    is Condition.AtPlace -> "at ${radiusM}m @${fixed(lat, 2)},${fixed(lng, 2)} in=${yes(inside)}"
}

private fun Recurrence.describe(): String = when (this) {
    Recurrence.None -> "none"
    Recurrence.ByTrigger -> "byTrigger"
    is Recurrence.After -> "after $amount $unit"
    is Recurrence.MonthlyWeekday -> "monthly $ordinal $day"
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
