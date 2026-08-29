package dev.rwilco.ui.alert

import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerFamily
import dev.rwilco.model.family
import dev.rwilco.ui.editor.Draft
import java.time.LocalDate
import java.time.LocalTime

/** What the full-screen alert shows; built from a saved reminder that rang, or from the editor's draft. */
data class AlertContent(
    val text: String,
    val tags: List<String>,
    val trigger: Trigger?,
    val family: TriggerFamily,
    val today: LocalDate,
    val defaultTime: LocalTime,
) {
    companion object {
        /**
         * A reminder that is actually ringing. [ruleIndex] is the rule whose moment this is,
         * when one is — a snooze's and a recurrence's moments have none, and fall back to the
         * first rule, which is what the card is recognised by.
         */
        fun fromReminder(reminder: dev.rwilco.model.Reminder, today: LocalDate, defaultTime: LocalTime, ruleIndex: Int? = null): AlertContent {
            val trigger = (ruleIndex?.let { reminder.rules.getOrNull(it) } ?: reminder.rules.firstOrNull())?.trigger
            return AlertContent(
                text = reminder.text,
                tags = reminder.tags,
                trigger = trigger,
                family = trigger?.family ?: TriggerFamily.TIME,
                today = today,
                defaultTime = defaultTime,
            )
        }

        fun fromDraft(draft: Draft, today: LocalDate, defaultTime: LocalTime): AlertContent {
            val trigger = draft.rules.firstOrNull()?.trigger
            return AlertContent(
                text = draft.text.trim(),
                tags = draft.tags,
                trigger = trigger,
                family = trigger?.family ?: TriggerFamily.TIME,
                today = today,
                defaultTime = defaultTime,
            )
        }
    }
}
