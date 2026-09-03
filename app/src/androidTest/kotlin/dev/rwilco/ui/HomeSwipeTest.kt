package dev.rwilco.ui

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
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
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
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

    /**
     * Handed over rather than asked for: the app asks for notifications on its first resume, and
     * a system dialog over the screen is a tap that lands nowhere and a screenshot of the
     * permission controller.
     */
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RwilcoApplication
    private val id = "swipe-test"
    private val words = "Coger el paraguas del paragüero"

    private fun s(resId: Int): String = rule.activity.getString(resId)

    private fun s(resId: Int, vararg args: Any): String = rule.activity.getString(resId, *args)

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

    /** The other way: open the card to the left and hold it there, which is the delete. */
    private fun swipeCardLeftAndHold(target: String = words) {
        rule.onNodeWithText(target).performTouchInput {
            down(centerRight)
            moveTo(centerLeft)
        }
        Thread.sleep(900)
        rule.onRoot().performTouchInput { up() }
    }

    /**
     * A delete's undo outlives the snackbar that offered it. Delete one card, mark the next
     * "hecho" — which replaces the snackbar — and the first is still one tap from coming back,
     * from the row at the top of the list, for a minute.
     */
    @Test
    fun aDeleteCanStillBeUndoneAfterTheNextSwipeTookTheSnackbar() {
        val otherId = "swipe-test-other"
        val otherWords = "Bajar la basura al contenedor"
        runBlocking {
            val now = app.clock.instant()
            app.repository.save(Reminder(id = otherId, text = otherWords, createdAt = now, updatedAt = now))
        }
        waitFor(words)
        waitFor(otherWords)

        swipeCardLeftAndHold(words)
        rule.waitUntil(10_000) { runBlocking { app.repository.get(id) } == null }

        // The next gesture's snackbar takes the first one's place, and with it the only undo
        // there used to be.
        swipeCardRightAndHold(otherWords)
        rule.waitUntil(10_000) { runBlocking { app.repository.get(otherId)?.status } == Status.DONE }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText(s(R.string.home_deleted), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }

        // The row is still there, and it still knows which reminder it is about. Its undo is
        // the one beside those words: a snackbar on its way out can still be holding another.
        val rowText = s(R.string.home_deleted_row, words)
        waitFor(rowText)
        rule.onNode(hasText(s(R.string.common_undo)) and hasAnySibling(hasText(rowText))).performClick()
        rule.waitUntil(10_000) { runBlocking { app.repository.get(id)?.text } == words }
        waitFor(words)
    }

    @Before
    fun oneCard() {
        runBlocking {
            app.repository.deleteAll()
            app.settingsStore.update { it.copy(presets = emptyList(), compactHome = false, lastSeenVersionCode = BuildConfig.VERSION_CODE) }
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

    /**
     * The complaint from the phone (0.74.0): "los lunes, y vuelve cada semana", swiped on a
     * Thursday, and the card went on saying "lunes" — true of every Monday and false about the
     * only one that mattered, the one just dealt through. The swipe was right all along;
     * nothing on the screen said so. Now the card and the snackbar both name the day.
     */
    @Test
    fun aWeeklyReminderSwipedDoneSaysWhichDayItComesBackOn() {
        val bins = "Sacar el cubo al portal"
        runBlocking {
            app.repository.deleteAll()
            val now = app.clock.instant()
            app.repository.save(
                Reminder(
                    id = id,
                    text = bins,
                    rules = listOf(TriggerRule(Trigger.Weekday(setOf(DayOfWeek.MONDAY)))),
                    recurrence = Recurrence.After(1, RecurrenceUnit.WEEKS),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        waitFor(bins)

        swipeCardRightAndHold(bins)
        rule.waitUntil(10_000) { runBlocking { app.repository.get(id)?.dealtThrough } != null }
        val after = runBlocking { app.repository.get(id)!! }
        assertEquals("a reminder asked to come back was finished instead", Status.ACTIVE, after.status)

        // The snackbar says it, with the day in it rather than the bare "Hecho".
        val said = prefixOf(R.string.home_marked_done_returns)
        waitForPart(said)
        // And once it has gone (SnackbarDuration.Short), the card is still saying it on the
        // recurrence row — which is the half that matters tomorrow, when nobody is watching a
        // snackbar. Waited for by the snackbar's own words, so the card's cannot answer for it.
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText(said, substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        waitForPart(prefixOf(R.string.card_recurrence_returns))
        shot("home-comes-back")
    }

    /** The words a string with one placeholder starts with, for a substring match. */
    private fun prefixOf(resId: Int): String = s(resId, "\u0000").substringBefore("\u0000").trim()

    private fun waitForPart(value: String) {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText(value, substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(600)
        val dir = java.io.File(rule.activity.filesDir, "screenshots").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun aHechoOnSomethingThatHasNotRungDealsWithTheOneThatWasComing() = runBlocking {
        // A daily at two o'clock whose next one is tomorrow, and nothing has rung.
        val two = java.time.LocalTime.of(14, 0)
        val tomorrow = java.time.LocalDate.now().plusDays(1)
        app.repository.deleteAll()
        val now = app.clock.instant()
        app.repository.save(
            Reminder(
                id = id,
                text = words,
                recurrence = dev.rwilco.model.Recurrence.Calendar(
                    dev.rwilco.model.Trigger.Repeat(
                        startsOn = tomorrow,
                        unit = dev.rwilco.model.RepeatUnit.DAY,
                        time = two,
                    ),
                ),
                createdAt = now,
                updatedAt = now,
            ),
        )
        waitFor(words)

        swipeCardRightAndHold()
        // Tomorrow's is what was dealt with, so it is spent and the day after is what is next.
        val expected = tomorrow.atTime(two).atZone(app.clock.zone).toInstant()
        rule.waitUntil(10_000) { runBlocking { app.repository.get(id)?.dealtThrough } == expected }
        val after = app.repository.get(id)!!
        assertEquals(Status.ACTIVE, after.status)
        assertEquals(expected, after.dealtThrough)
        assertEquals(
            tomorrow.plusDays(1).atTime(two).atZone(app.clock.zone).toInstant(),
            (dev.rwilco.model.nextFire(after, app.clock.instant(), app.clock.zone, java.time.LocalTime.of(9, 0)) as dev.rwilco.model.NextFire.Scheduled).at,
        )
        Unit
    }
}
