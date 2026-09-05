package dev.rwilco.ui

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.UpdateChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Choosing which stream of builds this phone follows.
 *
 * Asked of a device because the asymmetry is the whole design and it lives in the UI: the way
 * into alpha is a question, and the way back is not — a phone cannot be moved backwards, so
 * beta is chosen without ceremony and rejoined later. A dialog on the wrong side of that would
 * either nag somebody choosing the safer option or move them onto untested builds in one tap.
 */
@RunWith(AndroidJUnit4::class)
class UpdateChannelFlowTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun useSpanish() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("es-ES")
        }
    }

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    private fun s(id: Int): String = rule.activity.getString(id)

    @Before
    fun onBetaAndInTheDark() = runBlocking {
        app.settingsStore.update {
            it.copy(
                lastSeenVersionCode = BuildConfig.VERSION_CODE,
                theme = ThemeMode.DARK,
                updateChannel = UpdateChannel.BETA,
            )
        }
    }

    @Test
    fun alphaIsAskedForAndBetaIsNot() {
        openUpdates()
        rule.onNodeWithText(s(R.string.settings_channel_beta), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        shot("settings-channel")

        // The way in asks, and a cancelled question changes nothing.
        tap(R.string.settings_channel_alpha)
        rule.waitUntilShown(s(R.string.settings_channel_warn_title))
        shot("settings-channel-warning")
        tapInDialog(R.string.sheet_cancel)
        rule.waitUntilGone(s(R.string.settings_channel_warn_title))
        check(channelNow() == UpdateChannel.BETA) { "a cancelled question moved the phone" }

        tap(R.string.settings_channel_alpha)
        rule.waitUntilShown(s(R.string.settings_channel_warn_title))
        tapInDialog(R.string.settings_channel_warn_confirm)
        rule.waitUntil(timeoutMillis = 10_000) { channelNow() == UpdateChannel.ALPHA }

        // And back, without ceremony: choosing the safer of the two is not worth a dialog.
        tap(R.string.settings_channel_beta)
        rule.waitUntil(timeoutMillis = 10_000) { channelNow() == UpdateChannel.BETA }
        check(rule.onAllNodesWithText(s(R.string.settings_channel_warn_title), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            "coming back to beta asked a question it should not have"
        }
    }

    private fun channelNow(): UpdateChannel = runBlocking { app.settingsStore.settings.first().updateChannel }

    /** On the screen, which scrolls. */
    private fun tap(id: Int) = rule.onNodeWithText(s(id), useUnmergedTree = true).performScrollTo().performClick()

    /** In the dialog over it, which does not: scrolling to a node in one is an assertion error. */
    private fun tapInDialog(id: Int) = rule.onNodeWithText(s(id), useUnmergedTree = true).performClick()

    private fun openUpdates() {
        rule.onNodeWithContentDescription(s(R.string.home_settings)).performClick()
        rule.waitUntilShown(s(R.string.settings_updates))
        rule.onNodeWithText(s(R.string.settings_updates), useUnmergedTree = true).performScrollTo().performClick()
        rule.waitUntilShown(s(R.string.settings_channel))
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(800)
        val dir = File(app.filesDir, "screenshots").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilGone(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
}
