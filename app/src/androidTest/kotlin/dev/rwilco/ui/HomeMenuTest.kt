package dev.rwilco.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
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
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.Status
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.util.UUID

/**
 * A held card offers more than a clone: a reminder that rang and was never answered can be put
 * off from Home, a put-off one can be let back to its own moment, and any of them can be kept
 * as a preset with its words as the name.
 *
 * A device test because the menu, the snackbar and the editor it opens are all real screens,
 * and because "posponer" goes through the same door the notification uses.
 */
@RunWith(AndroidJUnit4::class)
class HomeMenuTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    private fun s(id: Int): String = rule.activity.getString(id)

    private val words = "Sacar la basura"
    private val id = UUID.randomUUID().toString()

    @Before
    fun oneOverdueReminder() = runBlocking {
        app.repository.replaceAll(emptyList())
        app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, theme = ThemeMode.DARK) }
        val now = app.clock.instant()
        val zone = app.clock.zone
        val anHourAgo = now.minusSeconds(3_600)
        // Rang an hour ago and nobody answered: the one shape "posponer" is an answer to.
        app.repository.save(
            Reminder(
                id = id,
                text = words,
                rules = listOf(TriggerRule(Trigger.AtDateTime(LocalDateTime.ofInstant(anHourAgo, zone)))),
                actions = setOf(Action.NOTIFICATION),
                createdAt = anHourAgo,
                updatedAt = anHourAgo,
                lastFiredAt = anHourAgo,
                armedFor = anHourAgo,
            ),
        )
    }

    private fun holdTheCard() {
        rule.waitUntilShown(words)
        rule.onAllNodesWithText(words, useUnmergedTree = true)[0].performTouchInput { longClick() }
    }

    @Test
    fun aHeldCardCanBePutOffLetBackAndKeptAsAPreset() {
        // Put off: the offer is there because the reminder rang and was let go.
        holdTheCard()
        rule.waitUntilShown(s(R.string.home_snooze))
        rule.onNodeWithText(s(R.string.home_menu_done), useUnmergedTree = true).assertIsDisplayed()
        // "Pausar" is on the card's own pill too; the menu adds a second one.
        check(rule.onAllNodesWithText(s(R.string.card_pause), useUnmergedTree = true).fetchSemanticsNodes().size >= 2) { "the menu should offer pause" }
        shot("home-menu")
        rule.onNodeWithText(s(R.string.home_snooze), useUnmergedTree = true).performClick()
        rule.waitUntilShown(s(R.string.snooze_ten_minutes))
        shot("home-menu-snooze")
        rule.onNodeWithText(s(R.string.snooze_ten_minutes), useUnmergedTree = true).performClick()
        rule.waitUntil(timeoutMillis = 10_000) { runBlocking { app.repository.get(id)?.snoozedUntil != null } }
        rule.waitUntilShown(s(R.string.home_snoozed_until).substringBefore(" %"))

        // Let back: the same card, now put off, offers to cancel it.
        holdTheCard()
        rule.waitUntilShown(s(R.string.home_cancel_snooze))
        rule.onNodeWithText(s(R.string.home_cancel_snooze), useUnmergedTree = true).performClick()
        rule.waitUntil(timeoutMillis = 10_000) { runBlocking { app.repository.get(id)?.snoozedUntil == null } }
        rule.waitUntilShown(s(R.string.home_snooze_cancelled))

        // Kept as a preset: the preset form, with the words as the name and nothing written.
        holdTheCard()
        rule.waitUntilShown(s(R.string.home_keep_preset))
        rule.onNodeWithText(s(R.string.home_keep_preset), useUnmergedTree = true).performClick()
        rule.waitUntilShown(s(R.string.editor_title_new_preset))
        // Twice: as the preset's name and as the wording a reminder made from it starts with.
        check(rule.onAllNodesWithText(words, useUnmergedTree = true).fetchSemanticsNodes().size == 2) { "the words should be the name and the wording" }
        shot("home-keep-preset")
        runBlocking {
            check(app.settingsStore.settings.first().presets.isEmpty()) { "keeping as a preset must not write until Guardar" }
        }
    }

    /**
     * A reminder that comes back, held before it rings, offers to let one round pass — and the
     * next round is the one after. The card stays: nothing was finished, one Tuesday was skipped.
     */
    @Test
    fun aRecurringCardOffersToSkipItsNextRound() {
        val daily = "Regar las plantas"
        val dailyId = UUID.randomUUID().toString()
        runBlocking {
            val now = app.clock.instant()
            val zone = app.clock.zone
            val tonight = LocalDateTime.ofInstant(now, zone).toLocalDate().atTime(23, 59)
            app.repository.save(
                Reminder(
                    id = dailyId,
                    text = daily,
                    rules = listOf(TriggerRule(Trigger.AtDateTime(tonight))),
                    recurrence = Recurrence.After(1, RecurrenceUnit.DAYS),
                    actions = setOf(Action.NOTIFICATION),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        rule.waitUntilShown(daily)
        rule.onAllNodesWithText(daily, useUnmergedTree = true)[0].performTouchInput { longClick() }
        rule.waitUntilShown(s(R.string.home_skip))
        shot("home-menu-skip")
        rule.onNodeWithText(s(R.string.home_skip), useUnmergedTree = true).performClick()
        // Dealt with ahead of the ring: the anchor moves, the row stays active.
        rule.waitUntil(timeoutMillis = 10_000) { runBlocking { app.repository.get(dailyId)?.lastDealtAt != null } }
        rule.waitUntilShown(s(R.string.home_skipped))
        check(runBlocking { app.repository.get(dailyId)?.status } == Status.ACTIVE) { "skipping a round must not finish the reminder" }
        // The overdue one from the fixture rang and is owed an answer: no skip for it.
        holdTheCard()
        rule.waitUntilShown(s(R.string.home_snooze))
        check(rule.onAllNodesWithText(s(R.string.home_skip), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) { "a ring waiting for an answer is answered, not skipped" }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_000)
        val dir = java.io.File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
