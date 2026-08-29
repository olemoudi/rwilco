package dev.rwilco.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Reminder
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import kotlinx.coroutines.runBlocking
import org.junit.Before
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.util.UUID
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import dev.rwilco.ui.editor.EDITOR_TAG_FIELD_TAG
import dev.rwilco.ui.editor.EDITOR_TEXT_TAG

/**
 * A tag used once has to come back on its own the next time.
 *
 * Asserted on a device rather than reasoned about, because the owner reported not being offered
 * their previous tags and the ranking, the query and the screen are three places it could have
 * been lost.
 */
@RunWith(AndroidJUnit4::class)
class TagReuseTest {

    /**
     * Handed over rather than asked for: the app asks for notifications on its first resume, and
     * a system dialog over the screen is a tap that lands nowhere and a screenshot of the
     * permission controller.
     */
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as RwilcoApplication
    private val tag = "fontanero"

    @Before
    fun oneReminderWithOneTag() {
        runBlocking {
            app.repository.deleteAll()
            val now = app.clock.instant()
            app.repository.save(
                Reminder(
                    id = UUID.randomUUID().toString(),
                    text = "Llamar al fontanero",
                    tags = listOf(tag),
                    rules = listOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.now().plusDays(1)))),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    @Test
    fun aTagUsedBeforeIsOfferedOnTheNextReminder() {
        val newLabel = rule.activity.getString(R.string.home_new)
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText(newLabel, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(newLabel, useUnmergedTree = true).performClick()

        val reuseLabel = rule.activity.getString(R.string.editor_reuse_tag)
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText(reuseLabel, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    /**
     * A tag typed but not yet added is part of the reminder: pressing "Guardar" with a word in
     * that field means the word. The field committed on losing the focus and the save cleared
     * the focus on its way in, which lands one snapshot too late — the reminder was saved with
     * no tag at all, and the word typed just vanished.
     */
    @Test
    fun aTagStillBeingTypedIsSavedWithTheReminder() {
        val words = "Cambiar el filtro"
        val typed = "coche"
        rule.waitUntilShown(rule.activity.getString(R.string.home_new))
        rule.onNodeWithText(rule.activity.getString(R.string.home_new), useUnmergedTree = true).performClick()
        rule.waitUntilShown(rule.activity.getString(R.string.editor_write))

        rule.onNodeWithText(rule.activity.getString(R.string.editor_write), useUnmergedTree = true).performClick()
        rule.onNodeWithTag(EDITOR_TEXT_TAG).performTextInput(words)
        rule.onNodeWithText(rule.activity.getString(R.string.editor_new_tag), useUnmergedTree = true).performScrollTo().performClick()
        rule.waitUntilShown(rule.activity.getString(R.string.editor_new_tag_hint))
        rule.onNodeWithTag(EDITOR_TAG_FIELD_TAG).performTextInput(typed)
        // Straight to "Guardar", without pressing the + or leaving the field.
        rule.onNodeWithText(rule.activity.getString(R.string.common_save), useUnmergedTree = true).performClick()

        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { app.repository.allNow().any { it.text == words } }
        }
        val saved = runBlocking { app.repository.allNow().first { it.text == words } }
        check(saved.tags == listOf(typed)) { "the tag being typed should have been saved: ${saved.tags}" }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
