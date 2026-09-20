package dev.rwilco.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Action
import dev.rwilco.model.Reminder
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.ui.alert.AlertActivity
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.util.UUID

/**
 * What is waiting for an answer is the first thing on Home, and the tap is the alert screen.
 *
 * A device test because both halves are only real on a device: the card is above the list in a
 * real `LazyColumn`, and what the tap does is start another activity — the one screen that can
 * actually answer the thing, whether or not this reminder ever asked for a full screen.
 */
@RunWith(AndroidJUnit4::class)
class HomeWaitingTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    private fun s(id: Int): String = rule.activity.getString(id)

    private val words = "Sacar la basura (prueba de espera)"
    private val id = UUID.randomUUID().toString()

    @Before
    fun oneRingNobodyAnswered() = runBlocking {
        app.repository.replaceAll(emptyList())
        app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, theme = ThemeMode.DARK) }
        val now = app.clock.instant()
        val anHourAgo = now.minusSeconds(3_600)
        // A notification and nothing else: no full screen asked for anywhere, which is the
        // point — the card leads to one all the same.
        app.repository.save(
            Reminder(
                id = id,
                text = words,
                rules = listOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.ofInstant(anHourAgo, app.clock.zone)))),
                actions = setOf(Action.NOTIFICATION),
                createdAt = anHourAgo,
                updatedAt = anHourAgo,
                lastFiredAt = anHourAgo,
                armedFor = anHourAgo,
            ),
        )
    }

    @Test
    fun theWaitingCardIsOnTopSaidOnceAndOpensTheAlert() {
        rule.waitUntilShown(s(R.string.home_waiting_title))
        // Said once: lifted out of "vencidos" rather than read twice on one screen.
        rule.waitForIdle()
        check(rule.onAllNodesWithText(words, useUnmergedTree = true).fetchSemanticsNodes().size == 1) {
            "the reminder should be on the waiting card and nowhere else on Home"
        }
        shot("home-waiting")

        rule.onNodeWithContentDescription(s(R.string.home_waiting_title), substring = true).performClick()
        rule.waitUntil(timeoutMillis = 10_000) { alertShowing() }
        shot("home-waiting-alert")
    }

    private fun alertShowing(): Boolean {
        var found = false
        instrumentation.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .any { it is AlertActivity }
        }
        return found
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_000)
        val dir = java.io.File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
