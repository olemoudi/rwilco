@file:UseSerializers(InstantSerializer::class)

package dev.rwilco.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/**
 * How many colours a preset can be given. A preset is found by its colour before it is read —
 * that is the whole point of giving it one — so the palette is small enough for the colours to
 * stay tellable apart, and the theme owns what they actually are.
 */
const val PRESET_COLORS = 8

/** How long a preset's name may be. Shorter than a reminder's words: it is a label on a button. */
const val MAX_PRESET_NAME = 40

/**
 * A reminder somebody makes often enough to keep the shape of: the words, the tags, the
 * triggers and what happens when it fires, under a name.
 *
 * It is not a reminder — nothing about it is waiting to ring — it is the answer to "the usual?".
 * [uses] and [lastUsedAt] are what put the ones actually used at the top of the list, and
 * [colorIndex] is what lets a hand find one without reading.
 */
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val tags: List<String> = emptyList(),
    val rules: List<TriggerRule> = emptyList(),
    val ruleMatch: RuleMatch = RuleMatch.ANY,
    val actions: Set<Action> = DEFAULT_ACTIONS,
    /** Which of the [PRESET_COLORS] this one wears. */
    val colorIndex: Int = 0,
    val uses: Int = 0,
    val lastUsedAt: Instant? = null,
    val createdAt: Instant,
)

/**
 * The colour to give a new preset: whichever is least spoken for, earliest first. With fewer
 * presets than colours that is simply a new colour each time; past that it shares them out
 * evenly rather than piling on the first.
 */
fun nextPresetColor(existing: List<Preset>): Int {
    val taken = IntArray(PRESET_COLORS)
    for (preset in existing) {
        val index = preset.colorIndex
        if (index in 0 until PRESET_COLORS) taken[index]++
    }
    var best = 0
    for (index in 1 until PRESET_COLORS) if (taken[index] < taken[best]) best = index
    return best
}

/**
 * Most used first, and among equals the one used most recently — which for a preset never used
 * is the day it was made, so a new one sits above an old one nobody touches.
 */
fun presetsByPopularity(presets: List<Preset>): List<Preset> = presets.sortedWith(
    compareByDescending<Preset> { it.uses }.thenByDescending { it.lastUsedAt ?: it.createdAt },
)

/** A preset used: one more use, and the clock says when. */
fun Preset.used(now: Instant): Preset = copy(uses = uses + 1, lastUsedAt = now)

/** The preset's shape as a reminder waiting to be written. */
fun Preset.toReminder(id: String, now: Instant): Reminder = Reminder(
    id = id,
    text = name,
    tags = tags,
    rules = rules,
    ruleMatch = ruleMatch,
    actions = actions,
    status = Status.ACTIVE,
    createdAt = now,
    updatedAt = now,
)
