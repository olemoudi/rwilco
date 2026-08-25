package dev.rwilco.ui

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.test.assertIsDisplayed
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
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

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RwilcoApplication
    private val presetName = "La compra del sábado"

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

        // Picking it counts as a use and opens the form already filled in.
        text(presetName).performClick()
        waitFor(s(R.string.editor_title_new))
        rule.waitUntil(10_000) { runBlocking { app.settingsStore.settings.first().presets.single().uses } == 1 }
        text(presetName).assertIsDisplayed()

        // And what is saved from there is a reminder, not another preset.
        text(s(R.string.common_save)).performClick()
        rule.waitUntil(10_000) { runBlocking { app.repository.openNow().any { it.text == presetName } } }
        assertEquals(1, runBlocking { app.settingsStore.settings.first().presets.size })
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
}
