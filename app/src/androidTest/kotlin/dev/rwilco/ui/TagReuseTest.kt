package dev.rwilco.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import androidx.compose.ui.test.performTextReplacement
import dev.rwilco.ui.components.TAG_NAME_FIELD_TAG
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

        // In the row itself, not behind the dots: with one tag there is nothing else to offer.
        rule.waitUntilShown(tag)
        rule.onNodeWithText(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    /**
     * The "+" on the row asks for a name and nothing else, and the tag it makes is on the
     * reminder that gets saved.
     *
     * This used to be a field that unfolded in the form, and a word left half-typed in it was a
     * tag lost on the way to "Guardar": the field committed on losing the focus and the save
     * cleared the focus on its way in, one snapshot too late. A dialog cannot be left half-typed
     * — it is answered or cancelled — which is the whole reason it is one (0.87.0).
     */
    @Test
    fun aTagMadeFromThePlusIsSavedWithTheReminder() {
        val words = "Cambiar el filtro"
        val typed = "coche"
        rule.waitUntilShown(rule.activity.getString(R.string.home_new))
        rule.onNodeWithText(rule.activity.getString(R.string.home_new), useUnmergedTree = true).performClick()
        rule.waitUntilShown(rule.activity.getString(R.string.editor_write))

        rule.onNodeWithText(rule.activity.getString(R.string.editor_write), useUnmergedTree = true).performClick()
        rule.onNodeWithTag(EDITOR_TEXT_TAG).performTextInput(words)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.editor_new_tag)).performScrollTo().performClick()
        rule.waitUntilShown(rule.activity.getString(R.string.editor_new_tag_hint))
        rule.onNodeWithTag(TAG_NAME_FIELD_TAG).performTextInput(typed)
        rule.onNodeWithText(rule.activity.getString(R.string.sheet_add), useUnmergedTree = true).performClick()
        rule.waitUntilGone(rule.activity.getString(R.string.editor_new_tag_hint))
        rule.onNodeWithText(rule.activity.getString(R.string.common_save), useUnmergedTree = true).performClick()

        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { app.repository.allNow().any { it.text == words } }
        }
        val saved = runBlocking { app.repository.allNow().first { it.text == words } }
        check(saved.tags == listOf(typed)) { "the tag from the dialog should have been saved: ${saved.tags}" }
    }

    /**
     * The "+" at the end of Home's row is where a tag is renamed, and the rename reaches every
     * reminder carrying it — the only door to that, now the hold on a chip is gone.
     */
    @Test
    fun aTagIsRenamedFromHomeAndTheReminderFollows() {
        val renamed = "lampista"
        rule.waitUntilShown(rule.activity.getString(R.string.home_new))
        rule.waitUntilShown(tag)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.home_tags_manage)).performClick()
        rule.waitUntilShown(rule.activity.getString(R.string.curate_tags_title))

        rule.onNodeWithContentDescription(rule.activity.getString(R.string.curate_rename)).performClick()
        // The word is on the screen three times over — the chip behind the dialog, the card's
        // own label, and the field the pencil just opened. Only one of them can be typed into.
        rule.onNode(hasSetTextAction() and hasText(tag), useUnmergedTree = true).performTextReplacement(renamed)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.curate_rename_confirm)).performClick()

        rule.waitUntil(timeoutMillis = 10_000) {
            runBlocking { app.repository.allNow().any { it.tags == listOf(renamed) } }
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilGone(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
