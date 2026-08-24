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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.util.UUID

/**
 * A tag used once has to come back on its own the next time.
 *
 * Asserted on a device rather than reasoned about, because the owner reported not being offered
 * their previous tags and the ranking, the query and the screen are three places it could have
 * been lost.
 */
@RunWith(AndroidJUnit4::class)
class TagReuseTest {

    @get:Rule
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
}
