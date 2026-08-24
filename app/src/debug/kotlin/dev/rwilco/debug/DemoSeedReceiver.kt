package dev.rwilco.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.rwilco.RwilcoApplication
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
                    "clear" -> app.repository.deleteAll()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
