@file:UseSerializers(InstantSerializer::class)

package dev.rwilco.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.time.ZoneId

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
    /**
     * The words a reminder made from this one starts with, or empty when there are none.
     *
     * Separate from [name] because they are different jobs: the name labels the shape on a
     * button ("la compra del sábado") and this is what the reminder actually says ("pan, café y
     * pilas"). Some shapes have words that never change — "sacar la basura" — and some are a
     * shape precisely because the words change every time. Empty is the second kind, and the
     * editor opens with the cursor waiting.
     */
    val text: String = "",
    val tags: List<String> = emptyList(),
    // Element by element on the way in: an unreadable rule costs this preset a rule, and
    // not the whole settings blob. See TolerantRules.
    @Serializable(with = TolerantRules::class)
    val rules: List<TriggerRule> = emptyList(),
    val ruleMatch: RuleMatch = RuleMatch.ANY,
    @Serializable(with = TolerantActions::class)
    val actions: Set<Action> = DEFAULT_ACTIONS,
    /** How the reminders made from it come back after they are dealt with. */
    @Serializable(with = TolerantRecurrence::class)
    val recurrence: Recurrence = Recurrence.None,
    /** The deadline the reminders made from it carry, if any; one a build cannot read is none. */
    @Serializable(with = TolerantDeadline::class)
    val deadline: Deadline? = null,
    /** Which of the [PRESET_COLORS] this one wears. */
    val colorIndex: Int = 0,
    /** Whether it has a button of its own on Home, for making one in a single tap. */
    val pinned: Boolean = false,
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
/**
 * [preset] written into this list, in its own place when it is already there. See the recurrence
 * presets' [List.keeping], which is the same rule and the same reason: order is what the sort
 * below falls back on when two presets are tied, and rebuilding one at the end of the list moves
 * it for no reason anybody asked for.
 */
fun List<Preset>.keeping(preset: Preset): List<Preset> =
    if (none { it.id == preset.id }) this + preset
    else map { if (it.id == preset.id) preset else it }

fun presetsByPopularity(presets: List<Preset>): List<Preset> = presets.sortedWith(
    compareByDescending<Preset> { it.uses }.thenByDescending { it.lastUsedAt ?: it.createdAt },
)

/** A preset used: one more use, and the clock says when. */
fun Preset.used(now: Instant): Preset = copy(uses = uses + 1, lastUsedAt = now)

/**
 * The preset's shape as a reminder waiting for its words.
 *
 * The words come from the preset's own [Preset.text] — its default wording — or from [words]
 * when the person has typed something. Never from the name: that labels the shape, and nobody
 * wants a list of reminders all called the same.
 */
fun Preset.toReminder(
    id: String,
    now: Instant,
    words: String = text,
    /**
     * What it should do when it rings, when that is being decided at the moment of use rather
     * than taken from the shape — the tiles under the words in the preset dialog. The preset
     * itself is untouched: this is one reminder saying "this time, also make a noise".
     */
    actions: Set<Action> = this.actions,
    /** Where and how the day is shaped, for a date left to the day: see [settleDays]. */
    zone: ZoneId,
    shape: DayShape = DayShape.DEFAULT,
): Reminder = Reminder(
    id = id,
    text = words,
    tags = tags,
    // Any countdown it carries starts ticking now: that is the whole reason a preset stores a
    // length rather than a moment. And a date left to the day is drawn from what is left of it.
    rules = settleDays(startCountdowns(settleRelativeDates(rules, now, zone), now), now, zone, shape),
    ruleMatch = ruleMatch,
    actions = actions,
    recurrence = recurrence,
    deadline = deadline,
    status = Status.ACTIVE,
    createdAt = now,
    updatedAt = now,
)
