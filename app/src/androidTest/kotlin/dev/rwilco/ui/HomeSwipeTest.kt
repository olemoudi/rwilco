package dev.rwilco.ui

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Swiping a card, taking it back, and swiping it again.
 *
 * The second swipe is the point. A dismiss box left resting at its dismissed end outlives the
 * row it belonged to — the list reuses it by key — so "undo" handed the reminder back frozen
 * halfway across the screen and the next swipe had nowhere left to go.
 */
@RunWith(AndroidJUnit4::class)
class HomeSwipeTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun useSpanish() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("es-ES")
        }
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RwilcoApplication
    private val id = "swipe-test"
    private val words = "Coger el paraguas del paragüero"

    private fun s(resId: Int): String = rule.activity.getString(resId)

    private fun waitFor(value: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(value, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * A slow drag from one edge of the card to the other: settled by position rather than by
     * fling, so the threshold is crossed for certain and the test is not timing the emulator.
     */
    private fun swipeCardRight() {
        rule.onNodeWithText(words).performTouchInput {
            down(centerLeft)
            moveTo(centerRight)
            up()
        }
    }

    @Before
    fun oneCard() {
        runBlocking {
            app.repository.deleteAll()
            app.settingsStore.update { it.copy(presets = emptyList(), lastSeenVersionCode = BuildConfig.VERSION_CODE) }
            val now = app.clock.instant()
            // No trigger: one plain card, no hero, nothing else on the screen to swipe by mistake.
            app.repository.save(Reminder(id = id, text = words, createdAt = now, updatedAt = now))
        }
    }

    @Test
    fun aCardTakenBackCanBeSwipedAgain() {
        waitFor(words)

        swipeCardRight()
        rule.waitUntil(10_000) { runBlocking { app.repository.get(id)?.status } == Status.DONE }

        // The snackbar hands it back.
        rule.onNodeWithText(s(R.string.common_undo)).performClick()
        rule.waitUntil(10_000) { runBlocking { app.repository.get(id)?.status } == Status.ACTIVE }
        waitFor(words)

        // And it answers a second swipe, which a card stuck at its dismissed end would not.
        swipeCardRight()
        rule.waitUntil(10_000) { runBlocking { app.repository.get(id)?.status } == Status.DONE }
        assertEquals(Status.DONE, runBlocking { app.repository.get(id)?.status })
    }
}
