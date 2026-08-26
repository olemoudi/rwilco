package dev.rwilco

import android.app.Application
import dev.rwilco.data.ReminderRepository
import dev.rwilco.data.RwilcoDatabase
import dev.rwilco.data.SettingsStore
import dev.rwilco.alarm.RearmWorker
import dev.rwilco.alarm.ReminderFiring
import dev.rwilco.alarm.ReminderScheduler
import android.app.AlarmManager
import android.os.Build
import dev.rwilco.geo.GeofenceManager
import dev.rwilco.geo.PlaceLogStore
import dev.rwilco.geo.PlaceWatchStore
import dev.rwilco.geo.PlaceWatcher
import dev.rwilco.geo.hasBackgroundLocation
import dev.rwilco.model.AppSettings
import dev.rwilco.notify.AlertNotifications
import dev.rwilco.update.UpdateWorker
import dev.rwilco.vault.GitHubVault
import dev.rwilco.vault.VaultBackup
import dev.rwilco.vault.VaultNotifications
import dev.rwilco.vault.VaultStore
import dev.rwilco.vault.VaultWorker
import dev.rwilco.vault.fingerprint
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock

/** Process-wide dependency container (manual DI — no frameworks). */
class RwilcoApplication : Application() {

    val clock: Clock = Clock.systemDefaultZone()
    /**
     * Every background job in the app runs here. The handler is what stands between one throw
     * in one collector and the process dying with every alarm-side promise in it: logged, the
     * job that failed is gone and the rest carry on.
     */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> Log.e(TAG, "background work failed", t) },
    )

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

    /** The encrypted backup's own memory: credentials, key, cursors. Off by default. */
    lateinit var vaultStore: VaultStore
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
        vaultStore = VaultStore(this)
        AlertNotifications.ensureChannels(this)

        // The periodic checks only. The one-off check at launch is MainActivity's, because "the
        // app was started" is not "somebody opened the app": the place watch's own alarm starts
        // this process every few minutes to an hour, and each of those used to enqueue a trip
        // to GitHub for a version.json that had not changed since the last one.
        UpdateWorker.schedule(this)
        RearmWorker.schedule(this)
        grants = Grants.read(this)

        // One pass at launch that also speaks up about anything the phone slept through, then a
        // re-arm whenever what to fire changes.
        appScope.launch {
            runCatching {
                firing.rearmAndCatchUp()
                geofences.sync()
                placeWatcher.sync()
            }.onFailure { Log.e(TAG, "the launch re-arm failed", it) }
        }
        appScope.launch {
            repository.open
                // Only the parts scheduling depends on. Without this the armed moment written
                // back to each row would come round as a change and arm it all over again.
                .map { reminders -> reminders.map(ReminderScheduler::schedulingKey) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    // One bad pass must not end the collector: it is the only thing that arms a
                    // reminder somebody has just saved.
                    runCatching {
                        scheduler.rearmAll()
                        geofences.sync()
                        placeWatcher.sync()
                    }.onFailure { Log.e(TAG, "re-arm after a change failed", it) }
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
                .collect { runCatching { scheduler.rearmAll() }.onFailure { Log.e(TAG, "re-arm after a settings change failed", it) } }
        }
        appScope.launch {
            // The backup's net runs only while it is on; off cancels whatever was queued.
            vaultStore.state
                .map { it.enabled }
                .distinctUntilChanged()
                .collect { enabled -> if (enabled) VaultWorker.schedule(this@RwilcoApplication) else VaultWorker.cancel(this@RwilcoApplication) }
        }
        appScope.launch {
            // What makes the backup's promise real: a change to the content — not to what the
            // scheduler writes back, which the fingerprint leaves out — queues one upload for a
            // quarter of an hour later, while the phone is awake from the change.
            combine(repository.rows, settingsStore.raw) { rows, raw -> fingerprint(rows, raw.orEmpty()) }
                .distinctUntilChanged()
                .drop(1)
                .collect { if (vaultStore.read().enabled) VaultWorker.runSoon(this@RwilcoApplication) }
        }
    }

    /** One backup run, wired to this process; [VaultWorker] and the Backup screen make them. */
    fun vaultBackup(): VaultBackup = VaultBackup(
        store = vaultStore,
        rows = repository::allRows,
        settingsJson = settingsStore::rawJson,
        transportFor = { state -> GitHubVault(state.owner, state.repo, state.pat, userAgent = USER_AGENT) },
        clock = clock,
        appVersionCode = BuildConfig.VERSION_CODE,
        dbVersion = RwilcoDatabase.VERSION,
        onAttention = { VaultNotifications.notifyAttention(this, it) },
        onResolved = { VaultNotifications.cancel(this) },
        log = { Log.i("RwilcoVault", it) },
    )

    companion object {
        private const val TAG = "RwilcoApp"
        private const val CATCH_UP_GUARD_MS = 5 * 60 * 1000L

        /** What GitHub sees in the header; a name it asks every client for. */
        const val USER_AGENT = "rwilco/${BuildConfig.VERSION_CODE}"
    }

    /**
     * The two grants the firing depends on, as they stood the last time anybody looked. Both
     * are given by hand in system settings, and the app is still running when the person comes
     * back from there — so nothing restarts, nothing re-arms, and until the six-hourly worker
     * came round a place reminder written before the grant was one the phone was not watching.
     */
    @Volatile
    private var grants: Grants = Grants(background = false, exact = false)

    /**
     * Somebody is back in front of the app: say what was missed while it was away, at most once
     * every few minutes. The launch pass and the six-hourly net are the other two doors; this is
     * the one that answers "I opened the app right after the timer should have gone off".
     */
    fun catchUpIfStale() {
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastCatchUpMs.get()
        if (last != 0L && now - last < CATCH_UP_GUARD_MS) return
        if (!lastCatchUpMs.compareAndSet(last, now)) return
        appScope.launch {
            runCatching { firing.rearmAndCatchUp() }.onFailure { Log.e(TAG, "the resume catch-up failed", it) }
        }
    }

    private val lastCatchUpMs = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Somebody is back in front of the app: if a grant changed while they were away, arm what
     * it unlocks. Cheap when nothing changed, which is every other time.
     */
    fun resyncIfGrantsChanged() {
        val now = Grants.read(this)
        val before = grants
        if (now == before) return
        grants = now
        appScope.launch {
            if (now.exact != before.exact) scheduler.rearmAll()
            if (now.background != before.background) {
                geofences.sync()
                placeWatcher.sync()
            }
        }
    }

    private data class Grants(val background: Boolean, val exact: Boolean) {
        companion object {
            fun read(context: android.content.Context): Grants {
                val alarms = context.getSystemService(AlarmManager::class.java)
                val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms?.canScheduleExactAlarms() == true
                return Grants(background = context.hasBackgroundLocation(), exact = exact)
            }
        }
    }
}
