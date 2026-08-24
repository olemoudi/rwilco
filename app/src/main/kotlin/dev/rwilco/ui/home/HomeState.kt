package dev.rwilco.ui.home

import dev.rwilco.model.Action
import dev.rwilco.model.NextFire
import dev.rwilco.model.Reminder
import dev.rwilco.model.Section
import dev.rwilco.model.Status
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerFamily
import dev.rwilco.model.family
import dev.rwilco.model.groupForHome
import dev.rwilco.model.nextFireOf
import dev.rwilco.model.tagsInUse
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class HomeUiState(
    val loaded: Boolean = false,
    val hero: HeroUi? = null,
    val sections: List<SectionUi> = emptyList(),
    val tags: List<String> = emptyList(),
    val selectedTag: String? = null,
    /** When date-only reminders ring; the cards say so under the date. */
    val defaultTime: LocalTime = LocalTime.of(9, 0),
) {
    /** Nothing open at all (as opposed to a filter that matched nothing). */
    val empty: Boolean get() = loaded && hero == null && sections.isEmpty() && selectedTag == null
}

data class HeroUi(val card: ReminderCardUi, val at: Instant)

data class SectionUi(val section: Section, val cards: List<ReminderCardUi>)

data class ReminderCardUi(
    val id: String,
    val text: String,
    val tags: List<String>,
    val triggers: List<TriggerRowUi>,
    val actions: Set<Action>,
    val paused: Boolean,
)

/** One trigger row: the strings are formatted in the composable, which has the locale. */
data class TriggerRowUi(
    val trigger: Trigger,
    val family: TriggerFamily,
    /** The definite moment, when there is one. */
    val nextAt: Instant?,
    /** A random trigger's window for the day of its next draw. */
    val window: Pair<Instant, Instant>?,
)

/** Everything Home shows, from the open reminders. Pure and JVM-tested. */
fun buildHomeState(
    reminders: List<Reminder>,
    defaultTime: LocalTime,
    now: Instant,
    zone: ZoneId,
    selectedTag: String?,
): HomeUiState {
    val tags = tagsInUse(reminders)
    // A filter on a tag that no longer exists (its last reminder was deleted) is no filter.
    val filter = selectedTag?.takeIf { tag -> tags.any { it.equals(tag, ignoreCase = true) } }
    val groups = groupForHome(reminders, now, zone, defaultTime, filter)
    fun card(reminder: Reminder) = ReminderCardUi(
        id = reminder.id,
        text = reminder.text,
        tags = reminder.tags,
        triggers = reminder.triggers.map { trigger ->
            val next = nextFireOf(trigger, reminder.id, now, zone, defaultTime)
            TriggerRowUi(
                trigger = trigger,
                family = trigger.family,
                nextAt = (next as? NextFire.Scheduled)?.at,
                window = (next as? NextFire.Sometime)?.let { it.windowStart to it.windowEnd },
            )
        },
        actions = reminder.actions,
        paused = reminder.status == Status.PAUSED,
    )
    return HomeUiState(
        loaded = true,
        hero = groups.hero?.let { HeroUi(card(it.reminder), (it.next as NextFire.Scheduled).at) },
        sections = groups.sections.map { (section, entries) -> SectionUi(section, entries.map { card(it.reminder) }) },
        tags = tags,
        selectedTag = filter,
        defaultTime = defaultTime,
    )
}
