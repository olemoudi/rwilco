package dev.rwilco.ui.alert

import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerFamily
import dev.rwilco.model.family
import dev.rwilco.ui.editor.Draft
import java.time.LocalDate
import java.time.LocalTime

/** What the full-screen alert shows; built from a saved reminder (phase 2) or the editor's draft. */
data class AlertContent(
    val text: String,
    val tags: List<String>,
    val trigger: Trigger?,
    val family: TriggerFamily,
    val today: LocalDate,
    val defaultTime: LocalTime,
) {
    companion object {
        /** A reminder that is actually ringing. */
        fun fromReminder(reminder: dev.rwilco.model.Reminder, today: LocalDate, defaultTime: LocalTime): AlertContent {
            val trigger = reminder.rules.firstOrNull()?.trigger
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
