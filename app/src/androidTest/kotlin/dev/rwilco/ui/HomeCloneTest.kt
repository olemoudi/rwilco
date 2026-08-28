package dev.rwilco.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Action
import dev.rwilco.model.Reminder
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.Trigger
import dev.rwilco.ui.editor.EDITOR_TEXT_TAG
import dev.rwilco.model.TriggerRule
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime
import java.util.UUID

/**
 * Holding a card offers what can be done to that reminder, and cloning it opens a new one
 * wearing the same shape with the words left blank.
 *
 * A device test because all three halves of it are things only a phone can answer: that a held
 * press is caught at all, that the menu opens above the thumb that opened it, and that the
 * editor arrives with its words empty and everything else already said.
 */
@RunWith(AndroidJUnit4::class)
class HomeCloneTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    private fun s(id: Int): String = rule.activity.getString(id)

    private val words = "Regar las plantas del balcón"
    private val tag = "casa"

    @Before
    fun oneReminder() = runBlocking {
        app.repository.replaceAll(emptyList())
        app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, theme = ThemeMode.DARK) }
        val now = app.clock.instant()
        app.repository.save(
            Reminder(
                id = UUID.randomUUID().toString(),
                text = words,
                tags = listOf(tag),
                rules = listOf(TriggerRule(Trigger.Interval(LocalTime.of(18, 0), LocalTime.of(20, 0)))),
                actions = setOf(Action.NOTIFICATION, Action.VIBRATE),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun holdingACardOffersToCloneItAndTheCopyArrivesWithItsWordsBlank() {
        rule.waitUntilShown(words)
        rule.onNodeWithText(words).performTouchInput { longClick() }

        // The menu says which card it caught, and what can be done to it.
        rule.waitUntilShown(s(R.string.home_clone))
        rule.onNodeWithText(s(R.string.home_clone), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.home_clone_hint), useUnmergedTree = true).assertIsDisplayed()
        shot("home-card-actions")
        rule.onNodeWithText(s(R.string.home_clone), useUnmergedTree = true).performClick()

        // A new reminder, not the one that was held: its title says so.
        rule.waitUntilShown(s(R.string.editor_title_new))
        // The words are blank and waiting — the placeholder is only ever drawn for an empty field.
        rule.onNodeWithText(s(R.string.editor_text_placeholder), useUnmergedTree = true).assertIsDisplayed()
        // With the cursor already in it, which is what puts the keyboard up: everything else
        // about this reminder has been answered, so the words are the only thing left to do.
        rule.onNodeWithTag(EDITOR_TEXT_TAG).assertIsFocused()
        shot("home-cloned-editor")
        // And everything else came with it: the tag, and the stretch of the day it rings in.
        rule.onAllNodesWithText(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty().let {
            check(it) { "the clone should carry the tag" }
        }
        check(rule.onAllNodesWithText("18:00", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            "the clone should carry the rule it was made from"
        }
        // The original is untouched: nothing was written, and it is still on Home behind this.
        runBlocking {
            val rows = app.repository.allNow()
            check(rows.size == 1 && rows.single().text == words) { "cloning must not write anything yet: $rows" }
        }
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
