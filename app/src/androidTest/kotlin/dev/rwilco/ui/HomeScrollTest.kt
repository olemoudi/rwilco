package dev.rwilco.ui

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.ui.editor.EDITOR_TEXT_TAG
import dev.rwilco.ui.home.HOME_LIST_TAG
import dev.rwilco.model.Reminder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/**
 * Where the list is looking, before and after the editor.
 *
 * Opening a reminder and coming back should leave the screen exactly where it was. It is the
 * same complaint as a card that changes place when you edit it, at one remove: what somebody
 * loses is not the card but the *place they were reading from*, and finding it again means
 * scrolling past everything they had already dealt with.
 */
@RunWith(AndroidJUnit4::class)
class HomeScrollTest {

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

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RwilcoApplication

    /** Enough cards that the last one is well past the fold. */
    private val words = (1..14).map { "Recordatorio número $it del scroll" }

    private fun s(resId: Int): String = rule.activity.getString(resId)

    private fun waitFor(value: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(value, substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * Notes, with no trigger between them. Deliberately: every card in that section ties on the
     * moment, so the tie-break IS the order, which is where a card used to jump on being
     * edited — and taking the scroll with it.
     */
    @Before
    fun seed() = runBlocking {
        app.repository.deleteAll()
        val now = app.clock.instant()
        words.forEachIndexed { index, text ->
            app.repository.save(
                Reminder(
                    id = "scroll-$index",
                    text = text,
                    createdAt = now.minus(Duration.ofMinutes((words.size - index).toLong())),
                    updatedAt = now.minus(Duration.ofMinutes((words.size - index).toLong())),
                ),
            )
        }
    }

    @Test
    fun leavingTheEditorWithoutSavingKeepsThePlaceYouWereReadingFrom() {
        val last = words.last()
        scrollTo(last)
        rule.onNodeWithText(last, useUnmergedTree = true).performClick()
        waitFor(s(R.string.editor_title_edit))
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor(last)
        rule.onNodeWithText(last, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun savingAnEditKeepsBothTheCardAndTheScrollWhereTheyWere() {
        // The one somebody actually complains about. Editing used to move the card to the top
        // of its section — the order inside a section was "last edited first" wherever the
        // moment tied — so coming back meant a screen scrolled to where the card no longer was.
        val last = words.last()
        scrollTo(last)
        rule.onNodeWithText(last, useUnmergedTree = true).performClick()
        waitFor(s(R.string.editor_title_edit))
        rule.onNodeWithTag(EDITOR_TEXT_TAG).performTextInput("!")
        rule.onNodeWithText(s(R.string.common_save), useUnmergedTree = true).performClick()
        waitFor(last)

        // Still the card that was under the thumb, and the list has not been thrown to the top.
        rule.onNodeWithText(last, substring = true, useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithText(words.first(), useUnmergedTree = true).fetchSemanticsNodes().let {
            assertTrue("the list jumped back to the top", it.isEmpty())
        }
    }

    @Test
    fun savingAnEditThatMovesTheCardGoesToWhereItWent() {
        // The other half of the test above, and the one reported from a phone: editing the
        // *words* leaves a card where it was, and keeping the scroll is right. Editing when it
        // rings moves it — here from the bottom of the list to the top of it — and then keeping
        // the scroll means looking at the place it used to be.
        val last = words.last()
        scrollTo(last)
        rule.onNodeWithText(last, useUnmergedTree = true).performClick()
        waitFor(s(R.string.editor_title_edit))

        // "En 30 min" is the first of the quick answers, and it puts the card at the top.
        rule.onNodeWithText(s(R.string.editor_add_trigger), useUnmergedTree = true).performScrollTo()
        rule.onNodeWithText("30 min", substring = true, useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithText(s(R.string.common_save), useUnmergedTree = true).performClick()

        // Back on Home, at the card, wherever it went — not at the bottom where it was.
        waitFor(last)
        rule.onNodeWithText(last, substring = true, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theTopRowLeavesGoingDownAndComesBackGoingUp() {
        // The complaint this answers: reading well down the list and wanting Settings meant
        // scrolling all the way back to the top for a button that had not moved.
        waitFor(words.first())
        val settings = s(R.string.home_settings)
        rule.onNodeWithContentDescription(settings).assertIsDisplayed()

        // Down the list: the row goes with it.
        repeat(3) { rule.onNodeWithTag(HOME_LIST_TAG).performTouchInput { swipeUp() } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(settings).assertIsNotDisplayed()
        rule.onAllNodesWithText(words.first(), useUnmergedTree = true).fetchSemanticsNodes().let {
            assertTrue("the list did not go anywhere, so there is nothing to test", it.isEmpty())
        }

        // And back at the first sign of up. A short drag that rests before it lifts, so no
        // fling carries the list home: the row has to come back for the scroll itself, which
        // is the whole complaint — Settings a flick away from where you are reading.
        rule.onNodeWithTag(HOME_LIST_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, 120f))
            moveBy(Offset(0f, 120f))
            advanceEventTime(400)
            up()
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription(settings).assertIsDisplayed()
        rule.onAllNodesWithText(words.first(), useUnmergedTree = true).fetchSemanticsNodes().let {
            assertTrue("the row came back only because the list went to the top", it.isEmpty())
        }
    }

    private fun scrollTo(text: String) {
        waitFor(words.first())
        rule.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText(text, substring = true))
        rule.waitForIdle()
        rule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
    }
}
