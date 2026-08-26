package dev.rwilco.diag

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.rwilco.model.DiagLog
import dev.rwilco.model.DiagNote
import dev.rwilco.model.ReminderCodec
import dev.rwilco.model.noting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

private val Context.diagDataStore: DataStore<Preferences> by preferencesDataStore(
    "rwilco_diagnostics",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Where the app's account of itself is kept: its own file, and the one in the app that is
 * cheerfully lost. It is written from the firing path, so it is written little and never on
 * the way to a notification — [Diag] hands the write to a background scope and returns.
 */
class DiagStore(private val context: Context) {

    private val key = stringPreferencesKey("diag_log_json")
    private val lock = Mutex()

    val log: Flow<DiagLog> = context.diagDataStore.data.map { prefs ->
        prefs[key]?.let(ReminderCodec::decodeDiagLog) ?: DiagLog()
    }

    suspend fun read(): DiagLog = log.first()

    /** One line. Serialised on a mutex: several doors write here within the same second. */
    suspend fun note(note: DiagNote) = lock.withLock {
        context.diagDataStore.edit { prefs ->
            val current = prefs[key]?.let(ReminderCodec::decodeDiagLog) ?: DiagLog()
            prefs[key] = ReminderCodec.encodeDiagLog(current.noting(note))
        }
    }

    suspend fun clear() {
        context.diagDataStore.edit { prefs -> prefs.remove(key) }
    }
}

/**
 * The one line every part of the app writes through.
 *
 * A plain object, because the places worth writing from are receivers, workers and pure-ish
 * classes that have no container between them and the store — and because a diagnostic that
 * needs plumbing to reach is a diagnostic nobody adds. Nothing is written until
 * [install] has run, which makes it a no-op in tests rather than a crash.
 */
object Diag {

    @Volatile
    private var store: DiagStore? = null

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var clock: Clock = Clock.systemDefaultZone()

    fun install(store: DiagStore, scope: CoroutineScope, clock: Clock) {
        this.store = store
        this.scope = scope
        this.clock = clock
    }

    /** [tag] is which part spoke — `fire`, `arm`, `show`, `vault`, `sys`, `update`. */
    fun note(tag: String, text: String) {
        val store = store ?: return
        val scope = scope ?: return
        val note = DiagNote(clock.instant(), tag, text)
        scope.launch { runCatching { store.note(note) }.onFailure { Log.w("RwilcoDiag", "could not write a note", it) } }
    }
}
