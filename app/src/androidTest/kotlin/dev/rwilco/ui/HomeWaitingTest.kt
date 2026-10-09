package dev.rwilco.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
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
import dev.rwilco.model.Preset
import dev.rwilco.model.Reminder
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.ui.alert.AlertActivity
import dev.rwilco.ui.home.HOME_LIST_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

/**
 * What is waiting for an answer is the first thing on Home, and the tap is the alert screen.
 *
 * A device test because both halves are only real on a device: the card is above the list in a
 * real `LazyColumn`, and what the tap does is start another activity — the one screen that can
 * actually answer the thing, whether or not this reminder ever asked for a full screen.
 *
 * **On top means in sight** (0.173.0). With a preset, its row is on screen before the reminders
 * are read, and the list keeps its place by the key of its first visible row — so the card
 * arriving a beat later went in above that row, out of sight, and the app opened on the presets
 * and the chips. A list without presets has nothing to hold on to up there, which is why this
 * test, which used to have none, never saw it.
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

    /** Enough of them that the last is well past the fold, and a preset, whose row comes first. */
    private val fillers = (1..14).map { "Nota de relleno número $it" }

    @Before
    fun aPresetAndAListLongerThanTheScreen() = runBlocking {
        val now = app.clock.instant()
        app.repository.replaceAll(emptyList())
        fillers.forEachIndexed { index, text ->
            val at = now.minus(Duration.ofMinutes((fillers.size - index).toLong()))
            app.repository.save(Reminder(id = "filler-$index", text = text, createdAt = at, updatedAt = at))
        }
        app.settingsStore.update {
            it.copy(
                lastSeenVersionCode = BuildConfig.VERSION_CODE,
                theme = ThemeMode.DARK,
                presets = listOf(Preset(id = "p1", name = "Pan", text = "Comprar pan", pinned = true, createdAt = now)),
            )
        }
    }

    private fun oneRingNobodyAnswered() = runBlocking {
        val anHourAgo = app.clock.instant().minusSeconds(3_600)
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
        // The app is already up, its presets on screen: the card comes after them, as it does
        // on a phone opened cold.
        rule.waitUntilShown("Pan")
        oneRingNobodyAnswered()
        waitUntilInSight(s(R.string.home_waiting_title))
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

    @Test
    fun aCardThatTurnsUpWhileTheListShowsItsTopIsInSight() {
        rule.waitUntilShown("Pan")
        // Past the moments after an opening, so it is the list holding its top that shows it.
        Thread.sleep(3_500)
        oneRingNobodyAnswered()
        waitUntilInSight(s(R.string.home_waiting_title))
    }

    @Test
    fun reopenedWithSomethingWaitingTheListStartsAtIt() {
        val last = fillers.last()
        rule.waitUntilShown(fillers.first())
        rule.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText(last))
        rule.waitForIdle()
        // Away, and a ring nobody answers while the app is not on screen.
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        oneRingNobodyAnswered()
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitUntilInSight(s(R.string.home_waiting_title))
        shot("home-waiting-reopened")
    }

    @Test
    fun aCardThatTurnsUpWhileReadingFurtherDownLeavesTheListWhereItIs() {
        val last = fillers.last()
        rule.waitUntilShown(fillers.first())
        rule.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText(last))
        Thread.sleep(3_500)
        oneRingNobodyAnswered()
        // The card is built above, out of sight, and the list stays where it was being read.
        rule.waitForIdle()
        Thread.sleep(1_500)
        rule.onNodeWithText(last, useUnmergedTree = true).assertIsDisplayed()
    }

    /** On screen, not merely composed: the bug was a card that existed, above the fold. */
    private fun waitUntilInSight(text: String) {
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { rule.onAllNodesWithText(text, useUnmergedTree = true).onFirst().assertIsDisplayed() }.isSuccess
        }
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
