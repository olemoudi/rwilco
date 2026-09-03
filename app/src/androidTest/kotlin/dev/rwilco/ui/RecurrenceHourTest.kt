package dev.rwilco.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.ThemeMode
import dev.rwilco.ui.components.TIME_FIELD_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * "Cada día" now says which hour it comes back at, and the three answers are on the card that
 * asks the rest of that question. A device test because it is a row on a screen: that it is
 * where the anchor is, and that choosing an hour of its own brings a field to say which.
 */
@RunWith(AndroidJUnit4::class)
class RecurrenceHourTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    private fun s(id: Int): String = rule.activity.getString(id)

    private val words = "Estirar la espalda"

    @Before
    fun oneDailyReminder() = runBlocking {
        app.repository.replaceAll(emptyList())
        app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, theme = ThemeMode.DARK) }
        val now = app.clock.instant()
        // No rules at all: the recurrence is the whole arrangement, so its hour is what rings.
        app.repository.save(
            Reminder(
                id = UUID.randomUUID().toString(),
                text = words,
                recurrence = Recurrence.After(1, RecurrenceUnit.DAYS),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun theHourIsAskedBesideTheAnchorAndCanBeOneOfItsOwn() {
        rule.waitUntilShown(words)
        rule.editCard(words)
        rule.waitUntilShown(s(R.string.recur_hour))

        for (label in listOf(R.string.recur_hour_day_start, R.string.recur_hour_same, R.string.recur_hour_custom)) {
            rule.onNodeWithText(s(label), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        shot("editor-recurrence-hour")

        // An hour of its own brings the field that says which.
        val before = rule.onAllNodesWithTag(TIME_FIELD_TAG).fetchSemanticsNodes().size
        rule.onNodeWithText(s(R.string.recur_hour_custom), useUnmergedTree = true).performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithTag(TIME_FIELD_TAG).fetchSemanticsNodes().size > before
        }
        shot("editor-recurrence-hour-custom")

        rule.onNodeWithText(s(R.string.common_save)).performClick()
        rule.waitUntilShown(words)
        runBlocking {
            val saved = app.repository.allNow().single().recurrence as Recurrence.After
            check(saved.hour is dev.rwilco.model.RecurrenceHour.At) { "the hour should have reached the row: $saved" }
        }
        shot("home-recurrence-hour")
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
