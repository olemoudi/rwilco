package dev.rwilco.ui

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.PRESET_COLORS
import dev.rwilco.ui.editor.EDITOR_TEXT_TAG
import kotlinx.coroutines.flow.first
import dev.rwilco.model.Action
import dev.rwilco.model.DEFAULT_ACTIONS
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A preset from end to end: written in the editor with the toggle on, kept in the settings,
 * offered by "New" from then on, and handed back to the form already filled in.
 */
@RunWith(AndroidJUnit4::class)
class PresetFlowTest {

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
    private val presetName = "La compra del sábado"
    private val reminderWords = "Pan, café y pilas"

    private fun s(id: Int): String = rule.activity.getString(id)

    private fun text(value: String) = rule.onNodeWithText(value, useUnmergedTree = true)

    private fun waitFor(value: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(value, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Before
    fun emptyStart() {
        runBlocking {
            app.repository.deleteAll()
            app.settingsStore.update { it.copy(presets = emptyList(), lastSeenVersionCode = BuildConfig.VERSION_CODE) }
        }
    }

    @Test
    fun aPresetIsWrittenOnceAndOfferedEverAfter() {
        waitFor(s(R.string.home_new))

        // With nothing kept, "New" goes straight to a blank form — no question worth asking.
        text(s(R.string.home_new)).performClick()
        waitFor(s(R.string.editor_title_new))

        // The toggle turns the form into a preset: same four parts, a name instead of words.
        text(s(R.string.editor_as_preset)).performScrollTo().performClick()
        waitFor(s(R.string.editor_title_new_preset))
        text(s(R.string.editor_name_preset)).performClick()
        rule.onNodeWithTag(EDITOR_TEXT_TAG).performTextInput(presetName)
        text(s(R.string.common_save)).performClick()

        // It lands in the settings, with a colour of its own and no uses yet.
        rule.waitUntil(10_000) { runBlocking { app.settingsStore.settings.first().presets.isNotEmpty() } }
        val saved = runBlocking { app.settingsStore.settings.first().presets.single() }
        assertEquals(presetName, saved.name)
        assertEquals(0, saved.uses)
        assertTrue(saved.colorIndex in 0 until PRESET_COLORS)

        // Now "New" asks first, and the preset is one of the two answers.
        waitFor(s(R.string.home_new))
        text(s(R.string.home_new)).performClick()
        waitFor(s(R.string.home_new_title))
        text(s(R.string.home_new_blank)).assertIsDisplayed()
        text(s(R.string.home_new_preset)).performClick()
        waitFor(presetName)

        // Picking it counts as a use and opens the form with everything but the words.
        text(presetName).performClick()
        waitFor(s(R.string.editor_title_new))
        rule.waitUntil(10_000) { runBlocking { app.settingsStore.settings.first().presets.single().uses } == 1 }
        // The name labels the shape on screen, and the words are waiting with the cursor in
        // them: this preset was saved without default wording.
        text(presetName).assertIsDisplayed()
        rule.onNodeWithTag(EDITOR_TEXT_TAG).assertIsFocused()

        // Typing is all that is left, and what is saved is a reminder, not another preset.
        rule.onNodeWithTag(EDITOR_TEXT_TAG).performTextInput(reminderWords)
        text(s(R.string.common_save)).performClick()
        rule.waitUntil(10_000) { runBlocking { app.repository.openNow().any { it.text == reminderWords } } }
        assertEquals(1, runBlocking { app.settingsStore.settings.first().presets.size })
    }

    @Test
    fun aPresetWithDefaultWordsArrivesFilledInAndLeavesTheKeyboardAlone() {
        waitFor(s(R.string.home_new))
        runBlocking {
            app.settingsStore.update { settings ->
                settings.copy(
                    presets = listOf(
                        dev.rwilco.model.Preset(
                            id = "p1",
                            name = presetName,
                            text = reminderWords,
                            createdAt = app.clock.instant(),
                        ),
                    ),
                )
            }
        }
        text(s(R.string.home_new)).performClick()
        waitFor(s(R.string.home_new_title))
        text(s(R.string.home_new_preset)).performClick()
        waitFor(presetName)
        text(presetName).performClick()

        waitFor(reminderWords)
        rule.onNodeWithTag(EDITOR_TEXT_TAG).assertIsNotFocused()
        // Nothing left to do but save it.
        text(s(R.string.common_save)).performClick()
        rule.waitUntil(10_000) { runBlocking { app.repository.openNow().any { it.text == reminderWords } } }
    }

    /** The row under the date: one tap and the reminder exists. */
    @Test
    fun aPinnedPresetWithWordsIsWrittenInOneTap() {
        waitFor(s(R.string.home_new))
        runBlocking {
            app.settingsStore.update { settings ->
                settings.copy(
                    presets = listOf(
                        dev.rwilco.model.Preset(
                            id = "p1",
                            name = presetName,
                            text = reminderWords,
                            pinned = true,
                            createdAt = app.clock.instant(),
                        ),
                    ),
                )
            }
        }
        waitFor(presetName)
        text(presetName).performClick()

        // No form, no dialog: the reminder is simply there, and it counted as a use.
        rule.waitUntil(10_000) { runBlocking { app.repository.openNow().any { it.text == reminderWords } } }
        rule.waitUntil(10_000) { runBlocking { app.settingsStore.settings.first().presets.single().uses } == 1 }
    }

    /** The same tap, on a shape whose words change every time. */
    @Test
    fun aPinnedPresetWithoutWordsAsksForThemAndNothingElse() {
        waitFor(s(R.string.home_new))
        runBlocking {
            app.settingsStore.update { settings ->
                settings.copy(
                    presets = listOf(
                        dev.rwilco.model.Preset(
                            id = "p1",
                            name = presetName,
                            tags = listOf("compra"),
                            pinned = true,
                            createdAt = app.clock.instant(),
                        ),
                    ),
                )
            }
        }
        waitFor(presetName)
        text(presetName).performClick()

        // A field, already focused, and one button.
        waitFor(s(R.string.home_pin_create_now))
        rule.onNodeWithText(s(R.string.editor_text_placeholder)).performTextInput(reminderWords)

        // Under the words, what it will do when it rings — the shape's answer, already on, and
        // changeable for this one reminder without editing the shape. The preset carries the
        // default pair (notification and vibration); this asks for the screen as well.
        rule.onNodeWithContentDescription(s(R.string.action_full_screen)).performClick()
        shot("preset-words")
        text(s(R.string.home_pin_create_now)).performClick()

        rule.waitUntil(10_000) { runBlocking { app.repository.openNow().any { it.text == reminderWords } } }
        val made = runBlocking { app.repository.openNow().first { it.text == reminderWords } }
        assertEquals("the shape came with it", listOf("compra"), made.tags)
        assertEquals(
            "the tiles under the words are what it does",
            setOf(Action.NOTIFICATION, Action.VIBRATE, Action.FULL_SCREEN),
            made.actions,
        )
        // And the shape itself is untouched by having been used that way once.
        val shape = runBlocking { app.settingsStore.settings.first() }.presets.first { it.id == "p1" }
        assertEquals("the preset kept its own answer", DEFAULT_ACTIONS, shape.actions)
    }

    @Test
    fun aPresetCanBeEditedAndDeletedFromTheChooser() {
        waitFor(s(R.string.home_new))
        runBlocking {
            app.settingsStore.update { settings ->
                settings.copy(
                    presets = listOf(
                        dev.rwilco.model.Preset(id = "p1", name = presetName, createdAt = app.clock.instant()),
                    ),
                )
            }
        }
        text(s(R.string.home_new)).performClick()
        waitFor(s(R.string.home_new_title))
        text(s(R.string.home_new_preset)).performClick()
        waitFor(presetName)

        rule.onNodeWithContentDescription(s(R.string.home_preset_edit)).performClick()
        waitFor(s(R.string.editor_title_edit_preset))

        // The bin on a preset removes the preset, and nothing else.
        rule.onNodeWithContentDescription(s(R.string.editor_delete_preset)).performClick()
        rule.waitUntil(10_000) { runBlocking { app.settingsStore.settings.first().presets.isEmpty() } }
        waitFor(s(R.string.home_new))
    }

    /** One capture, so a row of five glyphs can be looked at rather than reasoned about. */
    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_500)
        val dir = java.io.File(app.filesDir, "screenshots").apply { mkdirs() }
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(dir, "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
