package dev.rwilco.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What this app is, said before it is trusted with anything.
 *
 * The two buttons are two different promises and only a device can tell them apart: "OK" closes
 * it and it comes back at the next launch, and the other one is the only thing that stops it.
 * Both halves are asserted, because getting them the wrong way round would either nag somebody
 * who asked it not to or silence a notice nobody read.
 */
@RunWith(AndroidJUnit4::class)
class DisclaimerTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    /** The suite answers the notice once for the whole run (RwilcoTestRunner); this class does not. */
    @Before
    fun asIfTheAppHadJustBeenStarted() {
        runBlocking { app.settingsStore.update { it.copy(disclaimerRead = false, theme = ThemeMode.DARK) } }
        Disclaimer.readThisRun = false
    }

    @Test
    fun okClosesItForThisRunAndLeavesItComingBack() {
        rule.waitUntilShown()
        rule.onNodeWithTag(DISCLAIMER_TAG).assertIsDisplayed()
        shot("disclaimer")

        rule.onNodeWithText(rule.activity.getString(R.string.disclaimer_ok), useUnmergedTree = true).performClick()
        rule.waitUntilGone()

        val read = runBlocking { app.settingsStore.settings.first().disclaimerRead }
        check(!read) { "OK must not be the flag: the notice is due again at the next launch" }
        // Which is what the next launch looks like from in here: the same settings, a new run.
        Disclaimer.readThisRun = false
        rule.waitUntilShown()
    }

    @Test
    fun theOtherButtonIsTheOnlyThingThatStopsIt() {
        rule.waitUntilShown()
        rule.onNodeWithText(rule.activity.getString(R.string.disclaimer_never_again), useUnmergedTree = true).performClick()
        rule.waitUntilGone()

        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { app.settingsStore.settings.first().disclaimerRead }
        }
        // A new run, and this time it stays away.
        Disclaimer.readThisRun = false
        rule.waitForIdle()
        check(rule.onAllNodesWithTag(DISCLAIMER_TAG).fetchSemanticsNodes().isEmpty()) {
            "the notice came back after it was turned off"
        }
    }

    /** The one screen everybody sees on every launch, so it is worth looking at. */
    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(800)
        val dir = java.io.File(app.filesDir, "screenshots").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(dir, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown() =
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(DISCLAIMER_TAG).fetchSemanticsNodes().isNotEmpty() }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilGone() =
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag(DISCLAIMER_TAG).fetchSemanticsNodes().isEmpty() }
}
