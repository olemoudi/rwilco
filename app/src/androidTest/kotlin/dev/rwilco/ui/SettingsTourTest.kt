package dev.rwilco.ui

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Walks the folded Settings screen through Compose semantics: the index of ten rows, then a
 * group opened, then the same group closed again. Dark scheme only — light follows the same
 * tokens — and Spanish, which is the longer of the two languages and so the one that finds a
 * summary line that does not fit.
 */
@RunWith(AndroidJUnit4::class)
class SettingsTourTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun useSpanish() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("es-ES")
        }
    }

    /** Handed over rather than asked for: a system dialog over the screen is a screenshot of it. */
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    private fun s(id: Int): String = rule.activity.getString(id)

    @Before
    fun dark() = runBlocking {
        app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, theme = ThemeMode.DARK) }
    }

    @Test
    fun theIndexFoldsAndOpensWhereItStands() {
        rule.onNodeWithContentDescription(s(R.string.home_settings)).performClick()
        rule.waitUntilShown(s(R.string.settings_group_day))
        shot("settings-top")

        // The emulator is missing half the grants, so Alerts has opened itself. Closing it is
        // what leaves the index this screen is meant to be: ten rows and nothing else.
        if (rule.onAllNodesWithText(s(R.string.settings_alert_stacking), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText(s(R.string.settings_alerts), useUnmergedTree = true).performScrollTo().performClick()
            rule.waitUntilGone(s(R.string.settings_alert_stacking))
        }

        // Every group is an index row, and the whole index is on the screen at once: that is
        // the entire point of the fold, and the one thing worth failing a test over.
        for (title in indexTitles()) {
            rule.onNodeWithText(title, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        shot("settings-index")

        // Closed, the group's own controls are nowhere; opened, they are right under its row.
        rule.onAllNodesWithText(s(R.string.settings_awake), useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithText(s(R.string.settings_group_day), useUnmergedTree = true).performScrollTo().performClick()
        rule.waitUntilShown(s(R.string.settings_awake))
        shot("settings-group-open")

        rule.onNodeWithText(s(R.string.settings_group_day), useUnmergedTree = true).performScrollTo().performClick()
        rule.waitUntilGone(s(R.string.settings_awake))
        shot("settings-index-again")
    }

    @Test
    fun theAlertsRowSaysWhetherTheAppCanRing() {
        rule.onNodeWithContentDescription(s(R.string.home_settings)).performClick()
        rule.waitUntilShown(s(R.string.settings_alerts))
        // The closed row carries a verdict either way: "todo en su sitio", or a count in red.
        // Which of the two depends on this emulator's grants, so only the row itself is asserted.
        rule.onNodeWithText(s(R.string.settings_alerts), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(s(R.string.vault_card_title), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        shot("settings-alerts-row")
    }

    /** The ten rows, in the order the screen puts them. */
    private fun indexTitles(): List<String> = listOf(
        s(R.string.settings_alerts),
        s(R.string.settings_sound_title),
        s(R.string.settings_vibration_strength),
        s(R.string.settings_group_new),
        s(R.string.settings_group_day),
        s(R.string.settings_places),
        s(R.string.settings_group_look),
        s(R.string.vault_card_title),
        s(R.string.settings_updates),
        s(R.string.settings_about),
    )

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilGone(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals(count: Int) {
        check(fetchSemanticsNodes().size == count) { "expected $count nodes, found ${fetchSemanticsNodes().size}" }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_500)
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
