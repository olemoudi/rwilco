package dev.rwilco.model

import java.time.Duration
import java.time.Instant
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
    if (uses.isEmpty()) return emptyList()
    val excluded = exclude?.lowercase(Locale.ROOT)
    val scores = HashMap<String, Double>()
    val latest = HashMap<String, Instant>()
    val spelling = HashMap<String, String>()
    for ((value, at) in uses) {
        val key = value.lowercase(Locale.ROOT)
        if (key.isEmpty() || key == excluded) continue
        scores[key] = (scores[key] ?: 0.0) + weightOf(at, now)
        val seen = latest[key]
        if (seen == null || at >= seen) {
            latest[key] = at
            spelling[key] = value
        }
    }
    return scores.entries
        .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenByDescending { latest.getValue(it.key) })
        .take(limit)
        .map { spelling.getValue(it.key) }
}
