package dev.rwilco.ui

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Preset
import dev.rwilco.model.ThemeMode
import dev.rwilco.shortcuts.PresetShortcuts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The launcher's own door: the intent a pinned preset's shortcut carries, arriving at a cold
 * `MainActivity`. The reminder is written and nothing opens — the same one tap the button on
 * Home gives, without the app in between.
 *
 * Launched with the intent rather than delivered to a running activity: a delivery through
 * `onNewIntent` also sets the activity's intent, and the scenario then no longer recognises
 * the activity it started as its own and cannot close it.
 */
@RunWith(AndroidJUnit4::class)
class PresetShortcutTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as RwilcoApplication
    private val presetName = "Pan"
    private val reminderWords = "Comprar pan (atajo)"
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun onePinnedPreset() = runBlocking {
        app.repository.replaceAll(emptyList())
        app.settingsStore.update {
            it.copy(
                lastSeenVersionCode = BuildConfig.VERSION_CODE,
                theme = ThemeMode.DARK,
                presets = listOf(Preset(id = "p1", name = presetName, text = reminderWords, pinned = true, createdAt = app.clock.instant())),
            )
        }
    }

    @After
    fun clean() = runBlocking {
        runCatching { scenario?.close() }
        app.repository.replaceAll(emptyList())
        app.settingsStore.update { it.copy(presets = emptyList()) }
    }

    @Test
    fun theShortcutWritesTheReminderWithoutOpeningAnything() {
        val shortcut = Intent(context, MainActivity::class.java)
            .setAction(PresetShortcuts.ACTION_PRESET)
            .putExtra(PresetShortcuts.EXTRA_PRESET_ID, "p1")
        scenario = ActivityScenario.launch(shortcut)

        rule.waitUntil(timeoutMillis = 10_000) { runBlocking { app.repository.openNow().any { it.text == reminderWords } } }
        rule.waitUntil(timeoutMillis = 10_000) { runBlocking { app.settingsStore.settings.first().presets.single().uses } == 1 }
    }
}
