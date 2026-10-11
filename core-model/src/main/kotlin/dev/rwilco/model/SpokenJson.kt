package dev.rwilco.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/*
 * Gemini, when the grammar understood nothing or only part (0.178.0) — opt in, the person's own
 * key, text only (`voice/GeminiClient.kt`). This side is the pure half: what Gemini is told, the
 * shape its answer is held to, and how that answer becomes a reading — through **the same gate**
 * as the grammar's (`Spoken.toReminder`), so a guess can never write anything the grammar could
 * not have. And a guess is never written unseen: the sheet shows it and asks (the owner's call,
 * 2026-10-11).
 *
 * The answer's shape is flat on purpose: one event per rule with a kind and the fields that kind
 * uses, every field optional, because the schema language Gemini is held to has no unions. The
 * places are an enum of the person's own labels, built for each request, so the answer can only
 * name a place that exists.
 */

@Serializable
data class GeminiReading(
    val text: String = "",
    val match: String = "ANY",
    val rules: List<GeminiRule> = emptyList(),
    val recurrence: GeminiRecurrence? = null,
    val deadline: GeminiDeadline? = null,
    val actions: GeminiActions? = null,
)

@Serializable
data class GeminiRule(val event: GeminiEvent, val conditions: List<GeminiCondition> = emptyList())

@Serializable
data class GeminiEvent(
    val kind: String,
    val date: String? = null,
    val time: String? = null,
    val toDate: String? = null,
    val toTime: String? = null,
    val minutes: Int? = null,
    val times: Int? = null,
    val period: String? = null,
    val days: List<String> = emptyList(),
    val place: String? = null,
    val dwellMinutes: Int? = null,
)

@Serializable
data class GeminiCondition(
    val kind: String,
    val time: String? = null,
    val toTime: String? = null,
    val date: String? = null,
    val toDate: String? = null,
    val days: List<String> = emptyList(),
    val monthDays: List<Int> = emptyList(),
    val moving: String? = null,
    val place: String? = null,
)

@Serializable
data class GeminiRecurrence(
    val kind: String,
    val every: Int? = null,
    val unit: String? = null,
    val time: String? = null,
    val days: List<String> = emptyList(),
    val startDate: String? = null,
)

@Serializable
data class GeminiDeadline(val kind: String, val time: String? = null, val minutes: Int? = null)

@Serializable
data class GeminiActions(
    val fullScreen: Boolean? = null,
    val untilAnswered: Boolean? = null,
    val silent: Boolean? = null,
    val noVibration: Boolean? = null,
    val notificationOnly: Boolean? = null,
)

private val EVENT_KINDS = listOf("AT", "DAY", "DAY_RANGE", "IN_MINUTES", "BETWEEN_HOURS", "RANDOM", "WEEKDAYS", "ARRIVE", "LEAVE", "INSIDE", "OUTSIDE", "LEAVE_HERE")
private val CONDITION_KINDS = listOf("HOURS", "DATES", "WEEKDAYS", "MONTH_DAYS", "MOVING", "AT_PLACE", "AWAY_FROM_PLACE")
private val DAY_NAMES = DayOfWeek.entries.map { it.name }

/**
 * The shape Gemini's answer is held to (the OpenAPI subset `responseSchema` takes). [places] are
 * the person's saved labels: the only places an answer may name.
 */
fun geminiSchema(places: List<String>): JsonObject {
    fun string(vararg values: String) = buildJsonObject {
        put("type", "STRING")
        if (values.isNotEmpty()) putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
    }
    fun nullable(schema: JsonObject) = JsonObject(schema + ("nullable" to JsonPrimitive(true)))
    val integer = buildJsonObject { put("type", "INTEGER") }
    val boolean = buildJsonObject { put("type", "BOOLEAN") }
    fun array(items: JsonObject) = buildJsonObject {
        put("type", "ARRAY")
        put("items", items)
    }
    fun obj(required: List<String>, vararg properties: Pair<String, JsonElement>) = buildJsonObject {
        put("type", "OBJECT")
        put("properties", JsonObject(properties.toMap()))
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }
    val place = if (places.isEmpty()) nullable(string()) else nullable(string(*places.toTypedArray()))
    val days = array(string(*DAY_NAMES.toTypedArray()))
    val event = obj(
        listOf("kind"),
        "kind" to string(*EVENT_KINDS.toTypedArray()),
        "date" to nullable(string()), "time" to nullable(string()), "toDate" to nullable(string()), "toTime" to nullable(string()),
        "minutes" to nullable(integer), "times" to nullable(integer), "period" to nullable(string("DAY", "WEEK")),
        "days" to days, "place" to place, "dwellMinutes" to nullable(integer),
    )
    val condition = obj(
        listOf("kind"),
        "kind" to string(*CONDITION_KINDS.toTypedArray()),
        "time" to nullable(string()), "toTime" to nullable(string()), "date" to nullable(string()), "toDate" to nullable(string()),
        "days" to days, "monthDays" to array(integer), "moving" to nullable(string("DRIVING", "WALKING", "ANY")), "place" to place,
    )
    val recurrence = nullable(
        obj(
            listOf("kind"),
            "kind" to string("CALENDAR", "AFTER_DONE", "SINCE_DONE"),
            "every" to nullable(integer), "unit" to nullable(string("HOURS", "DAYS", "WEEKS", "MONTHS", "YEARS")),
            "time" to nullable(string()), "days" to days, "startDate" to nullable(string()),
        ),
    )
    val deadline = nullable(obj(listOf("kind"), "kind" to string("BEFORE_TIME", "WITHIN_MINUTES"), "time" to nullable(string()), "minutes" to nullable(integer)))
    val actions = nullable(
        obj(
            emptyList(),
            "fullScreen" to nullable(boolean), "untilAnswered" to nullable(boolean), "silent" to nullable(boolean),
            "noVibration" to nullable(boolean), "notificationOnly" to nullable(boolean),
        ),
    )
    return obj(
        listOf("text", "match", "rules"),
        "text" to string(),
        "match" to string("ANY", "ALL", "TOGETHER"),
        "rules" to array(obj(listOf("event"), "event" to event, "conditions" to array(condition))),
        "recurrence" to recurrence,
        "deadline" to deadline,
        "actions" to actions,
    )
}

/**
 * What Gemini is told: the local date and time, the person's own hours and the names of their
 * places, and the readings the owner chose for this app — so that a guess means what the grammar
 * would have meant. Text only; nothing here is a position, a time zone, a contact or another
 * reminder.
 */
fun geminiInstruction(now: Instant, zone: ZoneId, settings: AppSettings): String {
    val here = now.atZone(zone)
    val parts = settings.dayParts
    val weekday = here.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val places = settings.savedPlaces.joinToString(", ") { "\"${it.label}\"" }.ifEmpty { "none" }
    val windows = settings.savedWindows.joinToString(", ") { "\"${it.label}\" = ${it.from}-${it.to}" }.ifEmpty { "none" }
    return """
        You turn one sentence, dictated to a reminders app in Spanish or English, into a reminder. Answer only with the JSON the schema asks for.
        Now: $weekday ${here.toLocalDate()} ${here.toLocalTime().withSecond(0).withNano(0)}, local time.
        The person's hours: morning ${parts.morning}, afternoon ${parts.afternoon}, evening ${parts.evening}. A date with no hour is a DAY event: due the whole of that day from when the person's day starts, never a made-up hour.
        Saved places (the only ones that exist): $places. Saved windows of the day: $windows.
        - "text": what to remember, without the when, in the sentence's own words and language, first letter capital.
        - Each rule is an event plus conditions that must hold when it happens. "match": ANY = any rule rings it (also for "A y B" with two moments: it rings twice); ALL = every rule must have happened, the last rings (only if said: "las dos cosas", "both"); TOGETHER = all true at the same instant (states, "a la vez").
        - Events: AT (date YYYY-MM-DD + time HH:mm), DAY (date, no hour), DAY_RANGE (date..toDate), IN_MINUTES (minutes from now), BETWEEN_HOURS (time..toTime, optional days), RANDOM (times per period, between time..toTime), WEEKDAYS (days), ARRIVE/LEAVE (doorway in/out of a saved place, optional dwellMinutes 5-90), INSIDE/OUTSIDE (being in / away from a saved place), LEAVE_HERE (leaving where the phone is now).
        - "Cuando llegue a X" is ARRIVE; "mientras esté / cuando esté en X" is INSIDE. "Por la tarde" next to a place is a HOURS condition from afternoon to evening; alone with a day it is that day at the afternoon hour. "Esta semana" is DAY_RANGE from today to Sunday.
        - Conditions: HOURS (time..toTime), DATES (date..toDate), WEEKDAYS (days), MONTH_DAYS, MOVING (DRIVING/WALKING/ANY, only on ARRIVE/LEAVE), AT_PLACE/AWAY_FROM_PLACE (place).
        - recurrence: CALENDAR (every N unit, optional time, days for weeks, startDate), AFTER_DONE (comes back N unit after it is done), SINCE_DONE (a routine: every N unit since the last time it was done). deadline: BEFORE_TIME (time) or WITHIN_MINUTES.
        - actions only when said: fullScreen, untilAnswered (keeps ringing), silent, noVibration, notificationOnly.
        - If the sentence names a place that is not saved, or a when you cannot express, return no rules and no recurrence.
    """.trimIndent()
}

/**
 * Gemini's answer as a reading, through the same doors as the grammar's: a place it names must
 * be a saved one, every date and hour must parse, and [Spoken.toReminder] judges the rest. [said]
 * is the sentence minus "recuérdame", for the editor if it comes to that.
 */
fun GeminiReading.toHeard(now: Instant, zone: ZoneId, settings: AppSettings, here: Fix?, hereLabel: String, said: String): Heard {
    val unsure = Heard.Unsure(said, Heard.Why.PARTIAL)
    val today = now.atZone(zone).toLocalDate()
    val placeByLabel = settings.savedPlaces.associateBy { Words.of(it.label).text.trim() }
    fun placeOf(label: String?): SavedPlace? = label?.let { placeByLabel[Words.of(it).text.trim()] }
    fun time(value: String?): LocalTime? = value?.let { runCatching { LocalTime.parse(it.take(5)) }.getOrNull() }
    fun date(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    fun days(values: List<String>): Set<DayOfWeek> = values.mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name.uppercase() } }.toSet()
    var needsHere = false

    fun eventOf(event: GeminiEvent): Trigger? = when (event.kind) {
        "AT" -> date(event.date)?.let { d -> time(event.time)?.let { Trigger.AtDateTime(d.atTime(it)) } }
        "DAY" -> date(event.date)?.let { Trigger.DayRandom(it) }
        "DAY_RANGE" -> date(event.date)?.let { from -> date(event.toDate)?.takeIf { !it.isBefore(from) }?.let { Trigger.DateRange(from, it) } }
        "IN_MINUTES" -> event.minutes?.takeIf { it in MIN_COUNTDOWN_MINUTES..MAX_COUNTDOWN_MINUTES }?.let { Trigger.Countdown(it) }
        "BETWEEN_HOURS" -> time(event.time)?.let { from -> time(event.toTime)?.takeIf { it != from }?.let { Trigger.Interval(from, it, days(event.days)) } }
        "RANDOM" -> event.times?.takeIf { it > 0 }?.let { times ->
            Trigger.Random(times, if (event.period == "WEEK") Period.WEEK else Period.DAY, time(event.time) ?: LocalTime.of(10, 0), time(event.toTime) ?: LocalTime.of(20, 0), days(event.days))
        }
        "WEEKDAYS" -> days(event.days).takeIf { it.isNotEmpty() }?.let { Trigger.Weekday(it) }
        "ARRIVE", "LEAVE", "INSIDE", "OUTSIDE" -> placeOf(event.place)?.let { place ->
            val crossing = event.kind == "ARRIVE" || event.kind == "LEAVE"
            Trigger.Location(
                place.lat, place.lng, place.radiusM,
                if (event.kind == "ARRIVE" || event.kind == "INSIDE") Presence.INSIDE else Presence.OUTSIDE, place.label,
                onCrossing = crossing, dwellMinutes = event.dwellMinutes?.takeIf { crossing }, placeId = place.id.ifBlank { null },
            )
        }
        "LEAVE_HERE" -> if (here == null) {
            needsHere = true
            null
        } else {
            hereCircle(here, hereLabel)
        }
        else -> null
    }

    fun conditionOf(condition: GeminiCondition): Condition? = when (condition.kind) {
        "HOURS" -> time(condition.time)?.let { from -> time(condition.toTime)?.takeIf { it != from }?.let { Condition.TimeWindow(from, it, days(condition.days)) } }
        "DATES" -> date(condition.date)?.let { from -> date(condition.toDate)?.takeIf { !it.isBefore(from) }?.let { Condition.DateRange(from, it) } }
        "WEEKDAYS" -> days(condition.days).takeIf { it.isNotEmpty() }?.let { Condition.OnDays(it) }
        "MONTH_DAYS" -> condition.monthDays.toSet().takeIf { it.isNotEmpty() }?.let { Condition.OnMonthDays(it) }
        "MOVING" -> when (condition.moving) {
            "DRIVING" -> movingOf(MovingKind.DRIVING)
            "WALKING" -> movingOf(MovingKind.ON_FOOT)
            "ANY" -> movingOf(MovingKind.ANY)
            else -> null
        }
        "AT_PLACE", "AWAY_FROM_PLACE" -> placeOf(condition.place)?.let { place ->
            Condition.AtPlace(place.lat, place.lng, place.radiusM, place.label, inside = condition.kind == "AT_PLACE", placeId = place.id.ifBlank { null })
        }
        else -> null
    }

    val rules = rules.map { rule ->
        val trigger = eventOf(rule.event)
        if (needsHere) return Heard.NeedsHere(said)
        TriggerRule(trigger ?: return unsure, rule.conditions.map { conditionOf(it) ?: return unsure })
    }
    val recurrence = recurrence?.let { asked ->
        val every = asked.every ?: 1
        when (asked.kind) {
            "CALENDAR" -> {
                val unit = when (asked.unit) {
                    "DAYS" -> RepeatUnit.DAY
                    "WEEKS" -> RepeatUnit.WEEK
                    "MONTHS" -> RepeatUnit.MONTH
                    "YEARS" -> RepeatUnit.YEAR
                    else -> return unsure
                }
                Recurrence.Calendar(Trigger.Repeat(startsOn = date(asked.startDate) ?: today, every = every, unit = unit, time = time(asked.time), days = if (unit == RepeatUnit.WEEK) days(asked.days) else emptySet()))
            }
            "AFTER_DONE", "SINCE_DONE" -> {
                val unit = when (asked.unit) {
                    "HOURS" -> RecurrenceUnit.HOURS
                    "DAYS" -> RecurrenceUnit.DAYS
                    "WEEKS" -> RecurrenceUnit.WEEKS
                    "MONTHS" -> RecurrenceUnit.MONTHS
                    "YEARS" -> RecurrenceUnit.YEARS
                    else -> return unsure
                }
                if (asked.kind == "SINCE_DONE") Recurrence.Since(every, unit) else Recurrence.After(every, unit)
            }
            else -> return unsure
        }
    } ?: if (rules.any { it.trigger is Trigger.Random }) Recurrence.ByTrigger else Recurrence.None
    if (rules.isEmpty() && recurrence == Recurrence.None) return Heard.Unsure(said, Heard.Why.NOTHING)
    // What the grammar refuses, a guess may not write either: a calendar beside moments of its
    // own is two answers to "when does it come back".
    if (recurrence.isAnchored && rules.isNotEmpty()) return unsure
    val match = when (match) {
        "ALL" -> RuleMatch.ALL
        "TOGETHER" -> RuleMatch.TOGETHER
        else -> RuleMatch.ANY
    }.takeIf { rules.size > 1 } ?: RuleMatch.ANY
    val deadline = deadline?.let { asked ->
        when (asked.kind) {
            "BEFORE_TIME" -> time(asked.time)?.let { Deadline.Window(LocalTime.MIDNIGHT, it) } ?: return unsure
            "WITHIN_MINUTES" -> asked.minutes?.takeIf { it > 0 }?.let { Deadline.Timer(it) } ?: return unsure
            else -> return unsure
        }
    }
    // A deadline the rules cannot carry is refused, not dropped: dropping it would show and write
    // a reminder with no limit as if it were the one that was said.
    if (deadline != null && !deadlineApplies(deadline, rules, match)) return unsure
    val asked = actions?.let {
        SpokenActions(
            only = if (it.notificationOnly == true) setOf(Action.NOTIFICATION) else null,
            add = setOfNotNull(Action.FULL_SCREEN.takeIf { _ -> it.fullScreen == true }, Action.SOUND_UNTIL_ANSWERED.takeIf { _ -> it.untilAnswered == true }),
            remove = setOfNotNull(
                Action.SOUND.takeIf { _ -> it.silent == true }, Action.SOUND_UNTIL_ANSWERED.takeIf { _ -> it.silent == true },
                Action.VIBRATE.takeIf { _ -> it.noVibration == true },
            ),
        )
    } ?: SpokenActions()
    val words = text.trim().replaceFirstChar { it.titlecase(Locale.ROOT) }.ifBlank { return Heard.Unsure(said, Heard.Why.NO_WORDS) }
    return Heard.Read(Spoken(words, rules, match, recurrence, deadline, asked), said)
}

/** How Gemini's answers are read: anything it adds that this build does not know is ignored, not fatal. */
val geminiJson: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }

/** The places' labels, as the schema's enum offers them. */
val AppSettings.geminiPlaces: List<String> get() = savedPlaces.map { it.label }.filter { it.isNotBlank() }.distinct()
