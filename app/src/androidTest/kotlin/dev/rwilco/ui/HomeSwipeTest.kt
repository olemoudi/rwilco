package dev.rwilco.ui

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

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
     * Open the card and hold it there until it acts — which is the whole gesture now. The
     * release goes through the root: by then the card has gone from the list, and asking for it
     * by name would find nothing. Letting go too early is [SwipeableCardTest]'s job, where a
     * hand-driven clock can stop time before the fill finishes.
     */
    private fun swipeCardRightAndHold(target: String = words) {
        rule.onNodeWithText(target).performTouchInput {
            down(centerLeft)
            moveTo(centerRight)
        }
        Thread.sleep(900)
        rule.onRoot().performTouchInput { up() }
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

        swipeCardRightAndHold()
        rule.waitUntil(10_000) { runBlocking { app.repository.get(id)?.status } == Status.DONE }

        // The snackbar hands it back.
        rule.onNodeWithText(s(R.string.common_undo)).performClick()
        rule.waitUntil(10_000) { runBlocking { app.repository.get(id)?.status } == Status.ACTIVE }
        waitFor(words)

        // And it answers a second swipe, which a card stuck at its dismissed end would not.
        swipeCardRightAndHold()
        rule.waitUntil(10_000) { runBlocking { app.repository.get(id)?.status } == Status.DONE }
        assertEquals(Status.DONE, runBlocking { app.repository.get(id)?.status })
    }

    /**
     * "Hecho" means one thing, wherever it is given.
     *
     * The swipe used to file the reminder as DONE outright — right for most of them, wrong for
     * every one asked to come back. A medicine routine ("cada 1 h") was finished by the swipe
     * instead of starting its next round, and the moment the recurrence counts from was never
     * written down, so it could not have come back even if it had stayed.
     */
    @Test
    fun aRecurringReminderSwipedDoneComesBackCountedFromTheSwipe() {
        val pills = "Tomar la pastilla"
        runBlocking {
            app.repository.deleteAll()
            val written = app.clock.instant().minus(Duration.ofHours(2))
            // Rang an hour after it was written and nobody answered: overdue, waiting on Home.
            app.repository.save(
                Reminder(
                    id = id,
                    text = pills,
                    recurrence = Recurrence.After(1, RecurrenceUnit.HOURS),
                    createdAt = written,
                    updatedAt = written,
                    lastFiredAt = written.plus(Duration.ofHours(1)),
                ),
            )
        }
        waitFor(pills)

        val swipedAt = app.clock.instant()
        swipeCardRightAndHold(pills)
        // The anchor and the alarm are written by the same dismissal, the alarm last: a row
        // read between the two would say "dealt with, nothing armed" for a few milliseconds.
        rule.waitUntil(10_000) { runBlocking { app.repository.get(id)?.let { it.lastDealtAt != null && it.armedFor != null } } == true }

        val after = runBlocking { app.repository.get(id)!! }
        assertEquals("a reminder asked to come back was finished instead", Status.ACTIVE, after.status)
        assertTrue("the anchor has to be the swipe, not the firing", after.lastDealtAt!! >= swipedAt)
        val armed = after.armedFor
        assertTrue("nothing was armed for the next round", armed != null)
        assertTrue(
            "an hour after the swipe, not after the ring: $armed",
            Duration.between(swipedAt, armed).toMinutes() in 55..65,
        )
    }
}
