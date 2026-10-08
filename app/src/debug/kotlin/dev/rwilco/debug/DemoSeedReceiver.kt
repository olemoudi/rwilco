package dev.rwilco.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Action
import dev.rwilco.model.Reminder
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import kotlinx.coroutines.launch

/** Fills or empties the database from adb so screenshots and manual checks start from real content. */
class DemoSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as RwilcoApplication
        val pending = goAsync()
        app.appScope.launch {
            try {
                when (intent.getStringExtra("seed")) {
                    "demo" -> DemoData.seed(app.repository, app.clock)
                    // `--es seed many --ei copies 5`: the demo set, that many times over, for
                    // looking at (and measuring) a list long enough to have a scroll in it.
                    "many" -> DemoData.seedMany(app.repository, app.clock, intent.getIntExtra("copies", 5))
                    "clear" -> app.repository.deleteAll()
                    // `--es seed soon --ei minutes 4`: one loud reminder that many minutes out, for
                    // proving by hand what only a reboot can (ringing before the first unlock).
                    "soon" -> {
                        val now = app.clock.instant()
                        val at = java.time.LocalDateTime.ofInstant(now, app.clock.zone).plusMinutes(intent.getIntExtra("minutes", 4).toLong()).withSecond(0).withNano(0)
                        app.repository.save(
                            Reminder(
                                id = "debug-soon",
                                text = "Prueba: suena a las ${at.toLocalTime()}",
                                rules = listOf(TriggerRule(Trigger.AtDateTime(at))),
                                actions = setOf(Action.FULL_SCREEN, Action.NOTIFICATION, Action.SOUND),
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
