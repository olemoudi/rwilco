package dev.rwilco.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.ui.home.HOME_SEARCH_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * Home's magnifier finds only what is still to do, and a result opens into its Home card —
 * swiped "hecho" or deleted there, edited from its pencil — while what was done is searched on
 * the Hechos screen (0.146.0).
 */
@RunWith(AndroidJUnit4::class)
class SearchCardTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    private fun s(id: Int): String = rule.activity.getString(id)

    private val bread = "Comprar pan"
    private val milk = "Comprar leche"
    private val screws = "Comprar tornillos"

    @Before
    fun threeToBuy() = runBlocking {
        app.repository.replaceAll(emptyList())
        app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, theme = ThemeMode.DARK) }
        val now = app.clock.instant()
        val tomorrow = LocalDateTime.ofInstant(now, app.clock.zone).plusDays(1).withHour(19).withMinute(0)
        fun reminder(id: String, text: String, status: Status = Status.ACTIVE) = Reminder(
            id = id,
            text = text,
            rules = listOf(TriggerRule(Trigger.AtDateTime(tomorrow))),
            status = status,
            createdAt = now,
            updatedAt = now,
            doneAt = now.takeIf { status == Status.DONE },
        )
        app.repository.saveAll(listOf(reminder("bread", bread), reminder("milk", milk), reminder("screws", screws, Status.DONE)))
    }

    private fun searchHome(query: String) {
        rule.waitUntilShown(bread)
        rule.onNodeWithContentDescription(s(R.string.home_search)).performClick()
        rule.onNodeWithTag(HOME_SEARCH_TAG).performTextInput(query)
        rule.waitUntilShown(s(R.string.home_search_kind_reminder))
    }

    /** A result tapped: the Home card in its place, pencil and all. */
    private fun openResult(text: String) {
        rule.onNodeWithText(text, useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithContentDescription(rule.activity.getString(R.string.card_edit, text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun status(id: String): Status? = runBlocking { app.repository.get(id)?.status }

    @Test
    fun homeFindsOnlyWhatIsStillToDoAndOpensItIntoItsCard() {
        searchHome("comprar")
        rule.waitUntilShown(milk)
        rule.onAllNodesWithText(screws, useUnmergedTree = true).assertCountEquals(0)

        openResult(bread)
        shot("search-card-open")
        // Swiped "hecho", as on Home: done, and gone from what is still to do.
        swipeAndHold(bread, right = true)
        rule.waitUntil(10_000) { status("bread") == Status.DONE }
        rule.waitUntilGone(bread)

        // Swiped the other way: deleted.
        openResult(milk)
        swipeAndHold(milk, right = false)
        rule.waitUntil(10_000) { runBlocking { app.repository.get("milk") } == null }
        assertNull(runBlocking { app.repository.get("milk") })
    }

    @Test
    fun theCardsPencilOpensTheForm() {
        searchHome("pan")
        openResult(bread)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.card_edit, bread)).performClick()
        rule.waitUntilShown(s(R.string.editor_title_edit))
        assertEquals(Status.ACTIVE, status("bread"))
    }

    @Test
    fun whatWasDoneIsSearchedOnItsOwnScreen() {
        rule.waitUntilShown(bread)
        rule.onNodeWithContentDescription(s(R.string.home_done_list)).performClick()
        rule.waitUntilShown(screws)
        rule.onNodeWithContentDescription(s(R.string.done_search)).performClick()
        rule.onNodeWithTag(HOME_SEARCH_TAG).performTextInput("tornillos")
        rule.waitUntilShown(screws)
        rule.onNodeWithText(screws, useUnmergedTree = true).assertIsDisplayed()
        shot("done-search")
        rule.onNodeWithTag(HOME_SEARCH_TAG).performTextInput("zzz")
        rule.waitUntilShown(s(R.string.home_search_none_title))
    }

    private fun swipeAndHold(text: String, right: Boolean) {
        rule.onNodeWithText(text).performTouchInput {
            if (right) {
                down(centerLeft)
                moveTo(centerRight)
            } else {
                down(centerRight)
                moveTo(centerLeft)
            }
        }
        Thread.sleep(900)
        rule.onRoot().performTouchInput { up() }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_000)
        val dir = java.io.File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeTestRule.waitUntilGone(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
}
