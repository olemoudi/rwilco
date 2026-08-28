package dev.rwilco.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Presence
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalTime
import java.util.UUID

/**
 * A window with no days named says which days it *allows*, not how often it comes back.
 *
 * "Cada día" under a window, on a card whose next row says "cada mes", read as two claims about
 * repeating — and the wrong one was the window's, since whether anything comes back at all is
 * what "Vuelve" answers. A device test because the fix is a line on a card, and the line is
 * longer than the one it replaces: it has to fit.
 */
@RunWith(AndroidJUnit4::class)
class IntervalDaysTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    private fun s(id: Int): String = rule.activity.getString(id)

    private val words = "Dinero para la casa"

    @Before
    fun oneWindowedReminder() = runBlocking {
        app.repository.replaceAll(emptyList())
        app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, theme = ThemeMode.DARK) }
        val now = app.clock.instant()
        app.repository.save(
            Reminder(
                id = UUID.randomUUID().toString(),
                text = words,
                rules = listOf(
                    TriggerRule(Trigger.Location(40.43, -3.67, 50, Presence.INSIDE, "Casa")),
                    TriggerRule(Trigger.Interval(LocalTime.of(15, 0), LocalTime.of(22, 0))),
                ),
                ruleMatch = RuleMatch.TOGETHER,
                recurrence = Recurrence.After(1, RecurrenceUnit.MONTHS),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun aWindowWithNoDaysSaysWhichDaysItAllows() {
        rule.waitUntilShown(words)
        // The whole line, not a clipped one: the row gives it a single line and ellipsises the
        // rest, so a phrase that does not fit would arrive as "cualquier día de la sem…".
        rule.onNodeWithText(s(R.string.trigger_any_day_of_week), useUnmergedTree = true).assertIsDisplayed()
        check(rule.onAllNodesWithText(s(R.string.trigger_every_day), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            "a window does not repeat; that is what Vuelve answers"
        }
        shot("card-window-any-day")
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_000)
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
