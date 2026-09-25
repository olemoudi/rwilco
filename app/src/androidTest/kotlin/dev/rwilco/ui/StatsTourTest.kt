package dev.rwilco.ui

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.debug.DemoData
import dev.rwilco.model.ThemeMode
import dev.rwilco.ui.home.HOME_LIST_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The statistics, on the demo set's history (`DemoData.demoHistory`): the pills taken on every
 * weekday of seven weeks, three of them after a snooze and one left unanswered nine weekdays ago.
 * Dark and Spanish, like the other tours.
 */
@RunWith(AndroidJUnit4::class)
class StatsTourTest {

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

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    private fun s(id: Int, vararg args: Any): String = rule.activity.getString(id, *args)

    @Before
    fun seed() = runBlocking {
        app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, theme = ThemeMode.DARK) }
        DemoData.seed(app.repository, app.clock)
    }

    /**
     * Eight in a row under a best of twenty-six, the strip with its one red mark, and the lines
     * under it: the whole card, which is judged by looking at it.
     */
    @Test
    fun theEditorShowsAReminderStatistics() {
        val pills = "Pastillas de la tensión"
        rule.waitUntil(10_000) { rule.onAllNodesWithText(pills, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() || scrollTo(pills) }
        rule.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText(pills, substring = true))
        rule.editCard(pills)
        val best = s(R.string.stats_best, 26)
        rule.waitUntil(10_000) { rule.onAllNodesWithText(best, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(best, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        // The card's last line: scrolled to, it brings the whole card up with it.
        rule.onNodeWithText(s(R.string.stats_first_time, 31, 34), substring = true, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        shot("editor-stats")
    }

    /** The list is built a beat after launch; a scroll before then finds no list to scroll. */
    private fun scrollTo(text: String): Boolean = runCatching {
        rule.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText(text, substring = true))
        true
    }.getOrDefault(false)

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_500)
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
