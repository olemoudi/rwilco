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
import java.time.Duration
import java.time.Instant

/**
 * Settings that will not read are kept aside before the defaults are written over them (0.167.0).
 * The defaults are what the app runs on; the loss used to be made permanent by the very next write,
 * which the "what's new" sheet makes on every launch.
 */
@RunWith(AndroidJUnit4::class)
class SettingsKeptAsideTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as RwilcoApplication
    private var before: String? = null

    private fun kept(): List<File> = context.filesDir.listFiles { f -> f.name.startsWith(ASIDE_PREFIX) }.orEmpty().sortedBy { it.name }

    @Before
    fun keep(): Unit = runBlocking {
        before = app.settingsStore.rawJson()
        kept().forEach { it.delete() }
    }

    @After
    fun restore(): Unit = runBlocking {
        before?.let { app.settingsStore.replaceRaw(it) }
        kept().forEach { it.delete() }
    }

    @Test
    fun whatWouldNotReadIsKeptBeforeItIsWrittenOverOncePerBlob(): Unit = runBlocking {
        val unreadable = """{"theme":"DARK","defaultTime":"half past seven"}"""
        app.settingsStore.replaceRaw(unreadable)

        check(app.settingsStore.settings.first() == AppSettings()) { "the defaults stand in for settings that will not read" }
        check(kept().isEmpty()) { "reading alone kept something aside; that is the write's job, off the main thread" }
        check(app.settingsStore.sweepHeld(Instant.now())) { "the sweep ran over settings that do not read" }
        app.settingsStore.update { it.copy(lastSeenVersionCode = 1) }

        check(kept().map { it.readText() } == listOf(unreadable)) { "not kept, or not as it was: ${kept().map { it.name }}" }
        check(app.settingsStore.sweepHeld(Instant.now())) { "the sweep is held for a month after" }
        check(!app.settingsStore.sweepHeld(Instant.now().plus(Duration.ofDays(31)))) { "and not for ever" }

        // The same blob again is not kept twice; another one is kept beside it.
        app.settingsStore.replaceRaw(unreadable)
        app.settingsStore.update { it.copy(lastSeenVersionCode = 2) }
        check(kept().size == 1) { "one blob kept twice" }
        Thread.sleep(5) // two files in the same millisecond would share a name
        val lossy = """{"presets":[{"id":"p1"}],"theme":"DARK"}"""
        app.settingsStore.replaceRaw(lossy)
        app.settingsStore.update { it.copy(lastSeenVersionCode = 3) }
        check(kept().map { it.readText() } == listOf(unreadable, lossy)) { "a preset dropped on read was not kept: ${kept().map { it.name }}" }
    }
}
