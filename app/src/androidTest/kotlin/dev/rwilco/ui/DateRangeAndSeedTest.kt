package dev.rwilco.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.Trigger
import dev.rwilco.ui.editor.EDITOR_TEXT_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The two things about this change that only a real screen can answer: that a stretch of the
 * calendar survives the trip from the sheet to the row on disk, and that the calendar in
 * "Vuelve" opens on the hour the rules above it already name.
 *
 * The tour opens every configurator and cancels it, which is the right shape for "nothing
 * crashes" and says nothing about what a save actually writes. These two do the saving.
 */
@RunWith(AndroidJUnit4::class)
class DateRangeAndSeedTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    private fun s(id: Int): String = rule.activity.getString(id)
    private fun text(value: String) = rule.onNodeWithText(value, useUnmergedTree = true)

    private val words = "Renovar el abono"

    @Before
    fun anEmptyPhone() = runBlocking {
        app.repository.replaceAll(emptyList())
        app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, theme = ThemeMode.DARK) }
    }

    @Test
    fun aStretchOfTheCalendarIsSavedAsOne() {
        openNewReminder()

        text(s(R.string.editor_add_trigger)).performScrollTo().performClick()
        rule.waitUntilShown(s(R.string.kind_date_range))
        text(s(R.string.kind_date_range)).performClick()
        rule.waitUntilShown(s(R.string.date_range_to))
        // Two calendars and no hour anywhere: that is the whole tile.
        text(s(R.string.date_range_from)).performScrollTo().assertIsDisplayed()
        text(s(R.string.sheet_add)).performClick()
        rule.waitUntilGone(s(R.string.sheet_add))
        shot("editor-date-range-row")

        text(s(R.string.common_save)).performClick()
        rule.waitUntilShown(words)
        runBlocking {
            val trigger = app.repository.allNow().single().rules.single().trigger
            check(trigger is Trigger.DateRange) { "the row should hold a stretch of the calendar: $trigger" }
            check(!trigger.to.isBefore(trigger.from)) { "and one that ends after it starts: $trigger" }
        }
        shot("home-date-range")
    }

    @Test
    fun theCalendarOpensOnTheHourTheRulesAlreadyName() {
        openNewReminder()

        // A date with an hour somebody typed. The sheet opens on "me da igual la hora", so the
        // hour is a choice made here and not a default falling through.
        text(s(R.string.editor_add_trigger)).performScrollTo().performClick()
        rule.waitUntilShown(s(R.string.kind_date))
        text(s(R.string.kind_date)).performClick()
        rule.waitUntilShown(s(R.string.sheet_any_time))
        text(s(R.string.sheet_at_this_time)).performScrollTo().performClick()
        rule.waitUntilShown(s(R.string.sheet_time))
        text(s(R.string.sheet_add)).performClick()
        rule.waitUntilGone(s(R.string.sheet_add))

        // "Vuelve" → días concretos. It must open on that same answer, not back on the default:
        // asking for the hour twice, three rows apart, is asking somebody to agree with
        // themselves. The hour field is only shown by the answer that names one.
        text(s(R.string.recur_calendar)).performScrollTo().performClick()
        rule.waitUntilShown(s(R.string.sheet_repeat_starts))
        text(s(R.string.sheet_time)).performScrollTo().assertIsDisplayed()
        shot("sheet-calendar-seeded")
    }

    private fun openNewReminder() {
        rule.waitUntilShown(s(R.string.home_new))
        text(s(R.string.home_new)).performClick()
        rule.waitUntilShown(s(R.string.editor_write))
        text(s(R.string.editor_write)).performClick()
        rule.onNodeWithTag(EDITOR_TEXT_TAG).performTextInput(words)
    }

    private fun ComposeTestRule.waitUntilShown(value: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeTestRule.waitUntilGone(value: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_000)
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
