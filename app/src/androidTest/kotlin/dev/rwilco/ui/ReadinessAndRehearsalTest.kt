package dev.rwilco.ui

import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.alarm.TestAlert
import dev.rwilco.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two things only a phone can answer: that Home says so when the phone may not ring (the
 * emulator never has overlay, usage access or battery exemption, so it never can), and that
 * "probar una alerta" rings through the real path — the row, the alarm, the notification —
 * and leaves nothing behind once it is dealt with.
 */
@RunWith(AndroidJUnit4::class)
class ReadinessAndRehearsalTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as RwilcoApplication
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    private fun s(id: Int): String = rule.activity.getString(id)

    @Before
    fun clean() = runBlocking {
        app.repository.replaceAll(emptyList())
        app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, theme = ThemeMode.DARK, dismissedAlertProblems = emptySet()) }
    }

    @Test
    fun homeSaysThePhoneMayNotRingAndTheRehearsalRingsAnyway() {
        // The strip, and its way to the fix.
        rule.waitUntilShown(s(R.string.home_readiness_title))
        shot("home-readiness")
        rule.onNodeWithText(s(R.string.home_readiness_fix), useUnmergedTree = true).performClick()
        rule.waitUntilShown(s(R.string.settings_alerts))
        rule.waitUntilShown(s(R.string.settings_test_alert))
        shot("settings-alerts-test")

        // The rehearsal: saved like the settings row saves it, and the real path does the rest.
        val text = s(R.string.settings_test_alert_text)
        runBlocking { app.repository.save(TestAlert.reminder(app.clock.instant(), app.clock.zone, text)) }
        rule.waitUntil(timeoutMillis = 40_000) { activeTitles().contains(text) }
        val id = runBlocking { app.repository.allNow().single { TestAlert.isTest(it.id) }.id }
        runBlocking { app.firing.dismiss(id) }
        rule.waitUntil(timeoutMillis = 10_000) { !activeTitles().contains(text) }
        check(runBlocking { app.repository.allNow().none { TestAlert.isTest(it.id) } }) { "a rehearsal dealt with must leave no row" }
        check(runBlocking { app.repository.done.first().none { TestAlert.isTest(it.id) } }) { "a rehearsal is not a thing that got done" }
    }

    private fun activeTitles(): List<String> =
        manager.activeNotifications.mapNotNull { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_000)
        val dir = java.io.File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
