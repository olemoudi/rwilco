package dev.rwilco.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.ui.editor.EDITOR_NET_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * The safety net, from the switch to the mark on the card.
 *
 * A device test because every part of it is a screen: that the switch is where somebody would
 * look for it, that it says this reminder's own wait rather than the rule in the abstract, and
 * that a card carrying a net says so at a glance.
 */
@RunWith(AndroidJUnit4::class)
class SafetyNetFlowTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    private fun s(id: Int): String = rule.activity.getString(id)

    private val words = "Regar las plantas del balcón"

    @Before
    fun oneReminder() = runBlocking {
        app.repository.replaceAll(emptyList())
        app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, theme = ThemeMode.DARK) }
        val now = app.clock.instant()
        app.repository.save(
            Reminder(
                id = UUID.randomUUID().toString(),
                text = words,
                tags = listOf("casa"),
                rules = listOf(TriggerRule(Trigger.AtDateTime(LocalDate.now().plusDays(1).atTime(9, 0)))),
                // Six-hourly, so the wait is a tenth of six hours rather than the whole day:
                // the number on the screen is this reminder's own.
                recurrence = Recurrence.After(6, RecurrenceUnit.HOURS),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun theNetIsAskedForOnTheReminderAndSaidOnItsCard() {
        rule.waitUntilShown(words)
        // Nothing wears the mark until somebody asks for one.
        check(rule.onAllNodesWithText(words, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        rule.onAllNodesWithContentDescription(s(R.string.card_safety_net)).assertCountEquals(0)

        rule.onNodeWithText(words).performClick()
        rule.waitUntilShown(s(R.string.editor_net_title))
        rule.onNodeWithText(s(R.string.editor_net_title), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        // A tenth of six hours, said in this reminder's own numbers rather than as the rule.
        // Matched on the number alone: "36 min" is the same in both languages, and which one
        // this device is in depends on what ran before it.
        check(rule.onAllNodesWithText("36 min", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            "the row should say a tenth of six hours"
        }
        shot("editor-safety-net")

        rule.onNodeWithTag(EDITOR_NET_TAG).performScrollTo().performClick()
        rule.onNodeWithText(s(R.string.common_save)).performClick()

        rule.waitUntilShown(words)
        rule.onNodeWithContentDescription(s(R.string.card_safety_net), useUnmergedTree = true).assertIsDisplayed()
        shot("home-safety-net-mark")
        runBlocking {
            check(app.repository.allNow().single().safetyNet) { "the switch should have reached the row" }
        }
    }

    @Test
    fun theSettingsSectionSaysWhatTheNetMeans() {
        rule.onNodeWithContentDescription(s(R.string.home_settings)).performClick()
        rule.waitUntilShown(s(R.string.settings_net_title))
        rule.onNodeWithText(s(R.string.settings_net_title), useUnmergedTree = true).performScrollTo().performClick()
        rule.waitUntilShown(s(R.string.settings_net_after))
        for (title in listOf(R.string.settings_net_after, R.string.settings_net_fraction, R.string.settings_net_floor)) {
            rule.onNodeWithText(s(title), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText(s(R.string.settings_net_about), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        shot("settings-safety-net")
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(count: Int) {
        check(fetchSemanticsNodes().size == count) { "expected $count nodes, found ${fetchSemanticsNodes().size}" }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_000)
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
