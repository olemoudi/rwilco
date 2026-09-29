package dev.rwilco.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Settings that will not read are kept aside before the defaults are written over them (0.167.0).
 * The defaults are what the app runs on; the loss used to be made permanent by the very next write,
 * which the "what's new" sheet makes on every launch.
 */
@RunWith(AndroidJUnit4::class)
class SettingsKeptAsideTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as RwilcoApplication
    private val aside get() = File(context.filesDir, UNREADABLE_FILE)
    private var before: String? = null

    @Before
    fun keep(): Unit = runBlocking {
        before = app.settingsStore.rawJson()
        aside.delete()
    }

    @After
    fun restore(): Unit = runBlocking {
        before?.let { app.settingsStore.replaceRaw(it) }
        aside.delete()
    }

    @Test
    fun anUnreadableBlobIsKeptBeforeTheDefaultsAreWrittenOverIt(): Unit = runBlocking {
        val unreadable = """{"theme":"DARK","defaultTime":"half past seven"}"""
        app.settingsStore.replaceRaw(unreadable)

        check(app.settingsStore.settings.first() == AppSettings()) { "the defaults stand in for settings that will not read" }
        app.settingsStore.update { it.copy(lastSeenVersionCode = 1) }

        check(app.settingsStore.hasUnreadable()) { "nothing was kept aside" }
        check(aside.readText() == unreadable) { "what was kept is not what would not read: ${aside.readText()}" }

        // A second failure is the defaults' own, not the data: the first blob is the one kept.
        app.settingsStore.replaceRaw("""{"theme":""")
        app.settingsStore.settings.first()
        check(aside.readText() == unreadable) { "a later failure wrote over the blob kept aside" }
    }
}
