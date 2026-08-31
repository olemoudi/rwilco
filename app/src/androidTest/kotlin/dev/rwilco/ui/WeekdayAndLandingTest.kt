package dev.rwilco.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceHour
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.SpanLanding
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.UUID

/**
 * "Los viernes a las 14:00, y vuelve cada 30 días" — written the way it is now written, on a
 * real screen.
 *
 * Three things that only a device can answer, and they are all one flow because that is how
 * somebody meets them: the days are their own tile now; a second trigger arrives meaning "a la
 * vez" instead of "cualquiera"; and the moment the rules name days, "Vuelve" stops guessing
 * where thirty days lands and asks.
 */
@RunWith(AndroidJUnit4::class)
class WeekdayAndLandingTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    private fun s(id: Int): String = rule.activity.getString(id)

    private val words = "Revisar el filtro"

    /** The day as the toggles name it: the full name, in whatever locale the app is running in. */
    private val friday: String
        get() = DayOfWeek.FRIDAY.getDisplayName(TextStyle.FULL, rule.activity.resources.configuration.locales[0])

    @Before
    fun anHourEveryDayComingBackEveryThirtyDays() = runBlocking {
        app.repository.replaceAll(emptyList())
        app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, theme = ThemeMode.DARK) }
        val now = app.clock.instant()
        app.repository.save(
            Reminder(
                id = UUID.randomUUID().toString(),
                text = words,
                rules = listOf(TriggerRule(Trigger.TimeOfDay(LocalTime.of(14, 0)))),
                recurrence = Recurrence.After(30, RecurrenceUnit.DAYS),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun theDaysAreTheirOwnTileAndTheSpanThenAsksWhereItLands() {
        rule.waitUntilShown(words)
        rule.onNodeWithText(words).performClick()
        rule.waitUntilShown(s(R.string.recur_counts_from))

        // An hour every day narrows no days, so there is nothing for the span to land on and
        // the question is not put. A control that decides nothing is worse than no control.
        rule.onAllNodesWithText(s(R.string.recur_landing), useUnmergedTree = true).assertCountEquals(0)

        // The new tile, and the only thing on it: which days.
        rule.onNodeWithText(s(R.string.editor_add_trigger)).performScrollTo().performClick()
        rule.waitUntilShown(s(R.string.kind_weekday))
        rule.onNodeWithText(s(R.string.kind_weekday)).performClick()
        rule.waitUntilShown(s(R.string.weekday_days_hint))
        shot("sheet-weekday")
        rule.onNodeWithContentDescription(friday).performClick()
        rule.onNodeWithText(s(R.string.sheet_add)).performClick()

        // A second trigger means "a la vez": one arrangement with two halves, not two
        // arrangements either of which rings.
        rule.waitUntilShown(s(R.string.editor_match_together_hint))
        rule.onNodeWithText(s(R.string.editor_match_together_hint), useUnmergedTree = true).assertIsDisplayed()
        shot("editor-weekday-together")

        // And now the rules do name days, so "Vuelve" asks where thirty days lands.
        rule.onNodeWithText(s(R.string.recur_landing), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        for (label in listOf(R.string.recur_landing_next, R.string.recur_landing_nearest, R.string.recur_landing_exact)) {
            rule.onNodeWithText(s(label), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        shot("editor-span-landing")
        rule.onNodeWithText(s(R.string.recur_landing_exact), useUnmergedTree = true).performScrollTo().performClick()
        // Choosing it takes the rules out of the loop, so it takes their hour with it — or the
        // reminder would quietly start ringing at breakfast.
        rule.waitUntilShown(s(R.string.recur_hour_exact_note))

        rule.onNodeWithText(s(R.string.common_save)).performClick()
        rule.waitUntilShown(words)
        runBlocking {
            val saved = app.repository.allNow().single()
            check(saved.rules.size == 2) { "the day did not reach the row: ${saved.rules}" }
            check(saved.rules[1].trigger == Trigger.Weekday(setOf(DayOfWeek.FRIDAY))) { "${saved.rules[1]}" }
            check(saved.ruleMatch == RuleMatch.TOGETHER) { "the second rule did not mean «a la vez»: ${saved.ruleMatch}" }
            val span = saved.recurrence as Recurrence.After
            check(span.landing == SpanLanding.EXACT) { "the landing did not reach the row: $span" }
            check(span.hour == RecurrenceHour.At(LocalTime.of(14, 0))) { "the rules' hour was not adopted: $span" }
        }
        shot("home-weekday-landing")
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(700)
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
