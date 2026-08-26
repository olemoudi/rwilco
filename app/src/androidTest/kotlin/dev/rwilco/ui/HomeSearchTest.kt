package dev.rwilco.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.debug.DemoData
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.ui.home.HOME_SEARCH_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalTime

/**
 * The magnifier, on a real list: what only a device can answer is that the field takes the
 * keyboard, that both kinds of result come up saying which they are, and that a tag result
 * turns into the filter instead of opening something.
 */
@RunWith(AndroidJUnit4::class)
class HomeSearchTest {

    /**
     * Handed over rather than asked for: the app asks for notifications on its first resume, and
     * a system dialog over the screen is a tap that lands nowhere and a screenshot of the
     * permission controller.
     */
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun s(id: Int): String = rule.activity.getString(id)

    @Before
    fun seed() {
        val app = context.applicationContext as RwilcoApplication
        runBlocking {
            DemoData.seed(app.repository, app.clock)
            // Keep "What's new" from opening over the screen being captured.
            app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE) }
            // One reminder whose words and whose tag both answer the same query, so the two
            // kinds of row are guaranteed to appear side by side.
            val now = app.clock.instant()
            app.repository.save(
                Reminder(
                    id = "search-bread",
                    text = "Comprar pan",
                    tags = listOf("compra"),
                    rules = listOf(TriggerRule(Trigger.AtTime(LocalTime.of(19, 0), emptySet()))),
                    status = Status.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    @Test
    fun searchTellsRemindersAndTagsApart() {
        rule.waitUntilShown(s(R.string.home_next_up))

        rule.onNodeWithContentDescription(s(R.string.home_search)).performClick()
        rule.onNodeWithTag(HOME_SEARCH_TAG).performTextInput("compra")
        rule.waitUntilShown("Comprar pan")

        // Each row says what it is; without that, "compra" and "Comprar pan" are two lines that
        // do different things when tapped and look the same.
        assertTrue(rule.onAllNodesWithText(s(R.string.home_search_kind_reminder), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText(s(R.string.home_search_kind_tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        shot("home-search")

        // A tag is not something to open: it becomes the filter, and the search closes behind
        // it. The first tag row is "compra" itself — an exact match outranks "lista de la compra".
        rule.onAllNodesWithText(s(R.string.home_search_kind_tag), useUnmergedTree = true)[0].performClick()
        rule.waitUntilGone(s(R.string.home_search_hint))
        rule.waitUntilShown("Comprar pan")
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(value: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(value, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilGone(value: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(value, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    /** Same capture as the tour's, so the screenshots of both land in one place. */
    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(500)
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
