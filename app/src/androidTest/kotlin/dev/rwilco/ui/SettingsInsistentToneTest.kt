package dev.rwilco.ui

import android.graphics.Bitmap
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
import dev.rwilco.model.AlertSound
import dev.rwilco.model.Chime
import dev.rwilco.model.DEFAULT_ACTIONS
import dev.rwilco.model.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The tone for the reminders that keep asking is offered before anything asks.
 *
 * It used to live inside the fold that hides the round's two numbers, so on a phone with no
 * insistent reminder written yet the row was simply not there — and somebody who went looking
 * for that setting found the app did not have one. A preference you can only reach after you
 * have already written the reminder is not a preference anybody can find.
 *
 * A device test because it is a question about what a screen shows: nothing else can answer it.
 * The numbers stay folded on purpose, and that half is asserted here too, or the fix would
 * quietly become "show everything".
 */
@RunWith(AndroidJUnit4::class)
class SettingsInsistentToneTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    private fun s(id: Int): String = rule.activity.getString(id)

    /** A phone with nothing insistent anywhere: no such reminder, and not what a blank one starts as. */
    @Before
    fun nothingIsAsking() = runBlocking {
        app.repository.replaceAll(emptyList())
        app.settingsStore.update {
            it.copy(
                lastSeenVersionCode = BuildConfig.VERSION_CODE,
                theme = ThemeMode.DARK,
                insistentSound = null,
                defaultActions = DEFAULT_ACTIONS,
            )
        }
    }

    @Test
    fun theInsistentToneIsOfferedBeforeAnythingAsksForIt() {
        rule.onNodeWithContentDescription(s(R.string.home_settings)).performClick()
        rule.waitUntilShown(s(R.string.settings_sound_title))
        rule.onNodeWithText(s(R.string.settings_sound_title), useUnmergedTree = true).performScrollTo().performClick()
        rule.waitUntilShown(s(R.string.settings_sound_two_tones))

        // The choice is there, on a phone where nothing has asked for that sound yet.
        rule.onNodeWithText(s(R.string.settings_sound_two_tones), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        // And the round's two numbers are not: they describe something only such a reminder has.
        check(rule.onAllNodesWithText(s(R.string.settings_sound_plays), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            "the insistent round's numbers should stay folded until something asks for the sound"
        }
        shot("settings-insistent-tone-offered")

        // Turned on, it brings a second row of chimes and a second "custom file" button with
        // it — still with nothing asking for that sound. Flipped through the store rather than
        // tapped: in a SettingSwitchRow the title is a label and the Switch beside it is what
        // takes the touch, so tapping the words is not how anybody turns this on.
        runBlocking { app.settingsStore.update { it.copy(insistentSound = AlertSound.Bundled(Chime.ALERT)) } }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText(s(R.string.settings_sound_custom), useUnmergedTree = true).fetchSemanticsNodes().size >= 2
        }
        // And its own "oírlo en continuo": a full-screen alert for a reminder that keeps asking
        // rings this tone round and round, so it is the one that most needs hearing that way.
        check(rule.onAllNodesWithText(s(R.string.settings_sound_loop), useUnmergedTree = true).fetchSemanticsNodes().size >= 2) {
            "the insistent tone should offer the continuous preview too"
        }
        rule.onNodeWithText(s(R.string.settings_sound_two_tones), useUnmergedTree = true).performScrollTo()
        shot("settings-insistent-tone-on")
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
