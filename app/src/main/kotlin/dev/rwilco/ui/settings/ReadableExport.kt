package dev.rwilco.ui.settings

import dev.rwilco.R
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.Words
import dev.rwilco.ui.format.reminderSummary
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Everything, as text anybody can read.
 *
 * The vault is the copy that survives; this is the copy that can be *looked at* — pasted into
 * a note, sent to somebody, opened in ten years by whatever is around then. So it is plain
 * text in the app's own sentences (the same "why it rings" line the notification and the save
 * button say), in the phone's language, with nothing sealed and nothing that needs this app
 * to be understood. Open ones first, oldest first; done ones after, most recent first.
 */
fun readableExport(words: Words, reminders: List<Reminder>, today: LocalDate, defaultTime: LocalTime, zone: ZoneId): String = buildString {
    appendLine(words.get(R.string.export_text_title, TimeText.dayDateWithYear(today, words.locale)))
    val (done, open) = reminders.partition { it.status == Status.DONE }
    fun block(reminder: Reminder) {
        appendLine("• " + reminder.text.trim())
        val why = reminderSummary(words, reminder, today, defaultTime)
        if (why.isNotBlank()) appendLine("  $why")
        if (reminder.tags.isNotEmpty()) appendLine("  " + reminder.tags.joinToString(" ") { "#$it" })
        if (reminder.status == Status.PAUSED) appendLine("  " + words.get(R.string.export_text_paused))
        reminder.doneAt?.takeIf { reminder.status == Status.DONE }?.let { at ->
            appendLine("  " + words.get(R.string.export_text_done_on, TimeText.dayDate(at.atZone(zone).toLocalDate(), words.locale, today)))
        }
        appendLine()
    }
    if (open.isNotEmpty()) {
        appendLine()
        appendLine(words.get(R.string.export_text_open) + " (" + open.size + ")")
        appendLine()
        open.sortedBy { it.createdAt }.forEach(::block)
    }
    if (done.isNotEmpty()) {
        appendLine()
        appendLine(words.get(R.string.export_text_done) + " (" + done.size + ")")
        appendLine()
        done.sortedByDescending { it.doneAt ?: it.updatedAt }.forEach(::block)
    }
}
