package dev.rwilco

import android.app.Application
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.RwilcoDatabase
import dev.rwilco.data.SettingsStore
import dev.rwilco.model.AppSettings
import dev.rwilco.update.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock

/** Process-wide dependency container (manual DI — no frameworks). */
class RwilcoApplication : Application() {

    val clock: Clock = Clock.systemDefaultZone()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var repository: ReminderRepository
        private set
    lateinit var settingsStore: SettingsStore
        private set

    /** Null until the first read lands; the activity paints the window ground until then. */
    lateinit var settings: StateFlow<AppSettings?>
        private set

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        repository = ReminderRepository(RwilcoDatabase.get(this).reminders(), clock)
        settings = settingsStore.settings
            .map<AppSettings, AppSettings?> { it }
            .stateIn(appScope, SharingStarted.Eagerly, null)
        UpdateWorker.schedule(this)
        UpdateWorker.runNow(this)
    }
}
