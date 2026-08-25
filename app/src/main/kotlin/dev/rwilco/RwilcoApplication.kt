package dev.rwilco

import android.app.Application
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.RwilcoDatabase
import dev.rwilco.data.SettingsStore
import dev.rwilco.alarm.RearmWorker
import dev.rwilco.alarm.ReminderFiring
import dev.rwilco.alarm.ReminderScheduler
import dev.rwilco.geo.GeofenceManager
import dev.rwilco.geo.PlaceLogStore
import dev.rwilco.geo.PlaceWatchStore
import dev.rwilco.geo.PlaceWatcher
import dev.rwilco.model.AppSettings
import dev.rwilco.notify.AlertNotifications
import dev.rwilco.update.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock

/** Process-wide dependency container (manual DI — no frameworks). */
class RwilcoApplication : Application() {

    val clock: Clock = Clock.systemDefaultZone()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var repository: ReminderRepository
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var scheduler: ReminderScheduler
        private set
    lateinit var firing: ReminderFiring
        private set
    lateinit var geofences: GeofenceManager
        private set
    lateinit var placeWatcher: PlaceWatcher
        private set

    /** What the place watch did and why, for the log behind the button in Settings. */
    lateinit var placeLog: PlaceLogStore
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
        scheduler = ReminderScheduler(this, repository, settingsStore, clock)
        val placeWatch = PlaceWatchStore(this)
        firing = ReminderFiring(this, repository, settingsStore, scheduler, placeWatch, clock)
        geofences = GeofenceManager(this, repository)
        placeLog = PlaceLogStore(this)
        placeWatcher = PlaceWatcher(this, repository, firing, placeWatch, placeLog, settingsStore, clock)
        AlertNotifications.ensureChannels(this)

        UpdateWorker.schedule(this)
        UpdateWorker.runNow(this)
        RearmWorker.schedule(this)

        // One pass at launch that also speaks up about anything the phone slept through, then a
        // re-arm whenever what to fire changes.
        appScope.launch {
            firing.rearmAndCatchUp()
            geofences.sync()
            placeWatcher.sync()
        }
        appScope.launch {
            repository.open
                // Only the parts scheduling depends on. Without this the armed moment written
                // back to each row would come round as a change and arm it all over again.
                .map { reminders -> reminders.map(ReminderScheduler::schedulingKey) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    scheduler.rearmAll()
                    geofences.sync()
                    placeWatcher.sync()
                }
        }
        appScope.launch {
            // The two hours the settings hold that decide when something rings: the one a
            // date-only reminder goes off at, and the one "el día siguiente" means. Both are
            // scheduling inputs, and a setting changed without a re-arm is a setting that only
            // takes effect at the next reboot.
            settingsStore.settings
                .map { it.defaultTime to it.dayStart }
                .distinctUntilChanged()
                .drop(1)
                .collect { scheduler.rearmAll() }
        }
    }
}
