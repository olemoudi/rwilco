package dev.rwilco.alarm

import dev.rwilco.model.Action
import dev.rwilco.model.Recurrence
import dev.rwilco.model.Reminder
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * "Probar una alerta": a real reminder that rings in ten seconds through the real path — the
 * row is saved, the scheduling watcher arms it, `AlarmReceiver` fires it, `AlertPresenter`
 * shows it — because nothing less proves anything. A synthetic reminder handed straight to the
 * presenter would show a screen `AlertActivity` then drops (it re-reads the row, and there is
 * none), and would say nothing about exact alarms, the channel, Do Not Disturb or the lock
 * screen, which are the things being tested.
 *
 * It is a real row with a marked id, and "hecho" deletes it instead of finishing it
 * ([isTest], read in `ReminderFiring.dismiss`): a rehearsal is not a thing that got done, and
 * "Hechos" counts the week.
 */
object TestAlert {
    private const val PREFIX = "test-alert:"
    const val SECONDS_AHEAD = 10L

    fun isTest(id: String): Boolean = id.startsWith(PREFIX)

    fun reminder(now: Instant, zone: ZoneId, text: String): Reminder = Reminder(
        id = PREFIX + now.toEpochMilli(),
        text = text,
        rules = listOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.ofInstant(now.plusSeconds(SECONDS_AHEAD), zone)))),
        actions = setOf(Action.FULL_SCREEN, Action.NOTIFICATION, Action.SOUND, Action.VIBRATE),
        recurrence = Recurrence.None,
        createdAt = now,
        updatedAt = now,
    )
}
