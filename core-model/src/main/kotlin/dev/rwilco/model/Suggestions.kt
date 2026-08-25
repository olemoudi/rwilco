package dev.rwilco.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.pow

/**
 * What to offer instead of a keyboard.
 *
 * Everyday reminders repeat — the bins, the pills, the water filter — so the fastest way to
 * write one is usually not to write it. These rank what has been used before by how often and
 * how recently, which is the order a person expects: the thing you did last week beats the
 * thing you did once in March, and the thing you do every week beats both.
 *
 * A use is worth [HALF_LIFE_DAYS] days of half-life, so five uses last month still outrank one
 * yesterday, and something abandoned a year ago quietly falls off the end.
 */
private const val HALF_LIFE_DAYS = 30.0

private fun weightOf(used: Instant, now: Instant): Double {
    val days = Duration.between(used, now).toHours().coerceAtLeast(0) / 24.0
    return 0.5.pow(days / HALF_LIFE_DAYS)
}

/** Past reminder texts, best first. Blank ones and anything in [exclude] are left out. */
fun suggestedTexts(
    reminders: List<Reminder>,
    now: Instant,
    limit: Int = 10,
    exclude: String? = null,
): List<String> = rank(
    uses = reminders.mapNotNull { reminder ->
        reminder.text.trim().takeIf { it.isNotEmpty() }?.let { it to reminder.updatedAt }
    },
    now = now,
    limit = limit,
    exclude = exclude?.trim(),
)

/** Tags in use, best first — the same ranking, so the editor and the filter row agree. */
fun suggestedTags(reminders: List<Reminder>, now: Instant, limit: Int = 24): List<String> = rank(
    uses = reminders.flatMap { reminder -> reminder.tags.map { it to reminder.updatedAt } },
    now = now,
    limit = limit,
    exclude = null,
)

/**
 * Case-insensitive: "Compra" and "compra" are one thing, and the spelling that wins is the one
 * used most recently — the person's latest word on it.
 */
private fun rank(uses: List<Pair<String, Instant>>, now: Instant, limit: Int, exclude: String?): List<String> {
    val excluded = exclude?.lowercase(Locale.ROOT)
    val kept = uses.filter { (value, _) ->
        val key = value.lowercase(Locale.ROOT)
        key.isNotEmpty() && key != excluded
    }
    return rankByUse(kept, now, limit) { it.lowercase(Locale.ROOT) }
}

/**
 * The same ranking for anything that has a shape worth counting: uses add up with a half-life,
 * ties break on recency, and the winner of a shape is the way it was written last.
 */
private fun <T> rankByUse(uses: List<Pair<T, Instant>>, now: Instant, limit: Int, key: (T) -> String): List<T> {
    if (uses.isEmpty()) return emptyList()
    val scores = HashMap<String, Double>()
    val latest = HashMap<String, Instant>()
    val newest = HashMap<String, T>()
    for ((value, at) in uses) {
        val shape = key(value)
        scores[shape] = (scores[shape] ?: 0.0) + weightOf(at, now)
        val seen = latest[shape]
        if (seen == null || at >= seen) {
            latest[shape] = at
            newest[shape] = value
        }
    }
    return scores.entries
        .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenByDescending { latest.getValue(it.key) })
        .take(limit)
        .map { newest.getValue(it.key) }
}

/**
 * The "when"s used before, best first, ready to be used again.
 *
 * A trigger is offered by its *shape*, not its instant: "media hora" is a length and comes back
 * as one, "a las nueve los laborables" is already a standing arrangement, and a place is a
 * place. A date-time keeps only its time of day, re-hung on today if that hour is still ahead
 * and on tomorrow if it is not — "las 20:00 del martes pasado" is not something anybody wants
 * offered back. A bare date has nothing reusable in it at all and is left out.
 */
fun suggestedTriggers(reminders: List<Reminder>, now: Instant, zone: ZoneId, limit: Int = 6): List<Trigger> {
    val uses = reminders.flatMap { reminder -> reminder.rules.map { it.trigger to reminder.updatedAt } }
        .filter { (trigger, _) -> shapeOf(trigger) != null }
    return rankByUse(uses, now, limit) { shapeOf(it)!! }.map { reanchor(it, now, zone) }
}

/**
 * The kinds of "when" this person actually uses, best first, with the ones never used keeping
 * their usual order at the end. What the tiles are sorted by when Settings says "the popular
 * ones first" — a favourite you never have to choose.
 */
fun triggerKindsByUse(reminders: List<Reminder>, now: Instant): List<TriggerKind> {
    val uses = reminders.flatMap { reminder -> reminder.rules.map { it.trigger.kind to reminder.updatedAt } }
    val used = rankByUse(uses, now, TriggerKind.entries.size) { it.name }
    return used + TriggerKind.entries.filter { it !in used }
}

/** What makes two uses the same "when". Null for a trigger with nothing to reuse. */
private fun shapeOf(trigger: Trigger): String? = when (trigger) {
    is Trigger.Countdown -> "countdown:${trigger.minutes}"
    is Trigger.AtTime -> "at_time:${trigger.time}:" + trigger.days.map { it.value }.sorted().joinToString(",")
    // Only the hour survives; the day it fell on was that reminder's business.
    is Trigger.AtDateTime -> "at_date_time:${trigger.at.toLocalTime()}"
    // Four decimals is about eleven metres: the same door, however the pin was dropped.
    is Trigger.Location -> String.format(
        Locale.ROOT,
        "location:%.4f,%.4f:%d:%s",
        trigger.lat,
        trigger.lng,
        trigger.radiusM,
        trigger.transition.name,
    )
    is Trigger.Random -> "random:${trigger.timesPer}:${trigger.period}:${trigger.from}:${trigger.to}:" +
        trigger.days.map { it.value }.sorted().joinToString(",")
    is Trigger.OnDate -> null
}

/** The same shape, hung on now: a length starts fresh, an hour looks for its next day. */
private fun reanchor(trigger: Trigger, now: Instant, zone: ZoneId): Trigger = when (trigger) {
    is Trigger.Countdown -> trigger.copy(startedAt = null)
    is Trigger.AtDateTime -> {
        val here = now.atZone(zone)
        val time = trigger.at.toLocalTime()
        val date = if (time > here.toLocalTime()) here.toLocalDate() else here.toLocalDate().plusDays(1)
        Trigger.AtDateTime(LocalDateTime.of(date, time))
    }
    else -> trigger
}
