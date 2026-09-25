package dev.rwilco.cheer

import android.content.res.Resources
import dev.rwilco.R
import dev.rwilco.model.AchievementFamily
import dev.rwilco.model.CheerKind
import dev.rwilco.model.CheerPick
import dev.rwilco.model.Unlocked
import dev.rwilco.model.shortSubject

/**
 * The words for a line of encouragement: one string-array per kind in each language, the
 * phrasing picked by `pickCheer`, the numbers and the reminder's words filled in. Plain
 * [Resources] rather than Compose, because the worker says the same lines as Home does.
 *
 * Every array item carries exactly the placeholders its kind is formatted with ([ARGUMENTS],
 * held by `CheerStringsTest`), and every number handed in is two or more — the arrays have no
 * singular to fall back on.
 */
object CheerText {

    fun arrayOf(kind: CheerKind): Int = when (kind) {
        CheerKind.STREAK -> R.array.cheer_streak
        CheerKind.BEST_STREAK -> R.array.cheer_best_streak
        CheerKind.NEVER_FAILS -> R.array.cheer_never_fails
        CheerKind.FIRST_TIME -> R.array.cheer_first_time
        CheerKind.WEEK_UP -> R.array.cheer_week_up
        CheerKind.WEEK_COUNT -> R.array.cheer_week_count
        CheerKind.TODAY_COUNT -> R.array.cheer_today
        CheerKind.AHEAD -> R.array.cheer_ahead
        CheerKind.ACHIEVEMENT -> R.array.cheer_achievement
    }

    /** What each kind is formatted with, in order: `s` a text, `d` a number. */
    val ARGUMENTS: Map<CheerKind, String> = mapOf(
        CheerKind.STREAK to "sd",
        CheerKind.BEST_STREAK to "sd",
        CheerKind.NEVER_FAILS to "sd",
        CheerKind.FIRST_TIME to "dd",
        CheerKind.WEEK_UP to "dd",
        CheerKind.WEEK_COUNT to "d",
        CheerKind.TODAY_COUNT to "d",
        CheerKind.AHEAD to "d",
        CheerKind.ACHIEVEMENT to "s",
    )

    /** How many phrasings each kind has, for `pickCheer` to choose among. */
    fun variants(resources: Resources): Map<CheerKind, Int> =
        CheerKind.entries.associateWith { resources.getStringArray(arrayOf(it)).size }

    fun format(resources: Resources, pick: CheerPick): String {
        val cheer = pick.cheer
        val items = resources.getStringArray(arrayOf(cheer.kind))
        val template = items[pick.variant.coerceIn(0, items.lastIndex)]
        val args: Array<Any> = when (cheer.kind) {
            CheerKind.STREAK, CheerKind.BEST_STREAK, CheerKind.NEVER_FAILS -> arrayOf(shortSubject(cheer.subject.orEmpty()), cheer.numbers[0])
            CheerKind.FIRST_TIME, CheerKind.WEEK_UP -> arrayOf(cheer.numbers[0], cheer.numbers[1])
            CheerKind.WEEK_COUNT, CheerKind.TODAY_COUNT, CheerKind.AHEAD -> arrayOf(cheer.numbers[0])
            CheerKind.ACHIEVEMENT -> arrayOf(cheer.achievement?.let { achievementLine(resources, it) }.orEmpty())
        }
        return String.format(resources.configuration.locales[0], template, *args)
    }

    /** "30 seguidas", "100 hechos", "4 semanas redondas", "10 hechos antes de sonar". */
    fun achievementTitle(resources: Resources, family: AchievementFamily, tier: Int): String = when (family) {
        AchievementFamily.STREAK -> resources.getString(R.string.achievement_streak, tier)
        AchievementFamily.HECHOS -> resources.getString(R.string.achievement_hechos, tier)
        AchievementFamily.PERFECT_WEEKS -> resources.getQuantityString(R.plurals.achievement_perfect_weeks, tier, tier)
        AchievementFamily.AHEAD -> resources.getString(R.string.achievement_ahead, tier)
    }

    /** The title, and the reminder it was earned with when there is one: "30 seguidas con «Pastillas»". */
    private fun achievementLine(resources: Resources, one: Unlocked): String {
        val title = achievementTitle(resources, one.family, one.tier)
        return one.about?.let { resources.getString(R.string.cheer_achievement_with, title, shortSubject(it)) } ?: title
    }
}
