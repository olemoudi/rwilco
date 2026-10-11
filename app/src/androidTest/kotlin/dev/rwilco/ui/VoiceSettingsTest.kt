package dev.rwilco.ui

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import dev.rwilco.model.GeminiReading
import dev.rwilco.voice.GeminiAnswer
import dev.rwilco.voice.GeminiClient
import dev.rwilco.voice.GeminiInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Settings › Voice's "Probar la clave" (0.180.0), through the real screen with a fake Gemini: the
 * key typed in the field is the one asked, and each answer is said in its own line.
 */
@RunWith(AndroidJUnit4::class)
class VoiceSettingsTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun useSpanish() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("es-ES")
        }
    }

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RwilcoApplication

    private fun s(resId: Int): String = rule.activity.getString(resId)

    private fun waitFor(text: String) {
        rule.waitUntil(30_000) { rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Before
    fun geminiOnWithNoKey() = runBlocking {
        app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, theme = dev.rwilco.model.ThemeMode.DARK, presets = emptyList()) }
        app.geminiStore.clear()
        app.geminiStore.setEnabled(true)
    }

    @After
    fun realGemini() {
        GeminiInput.ask = { key, sentence, instruction, schema -> GeminiClient(key).read(sentence, instruction, schema) }
        runBlocking { app.geminiStore.clear() }
    }

    private fun openVoice() {
        rule.onNodeWithContentDescription(s(R.string.home_settings)).performClick()
        waitFor(s(R.string.settings_group_voice))
        rule.onNodeWithText(s(R.string.settings_group_voice), useUnmergedTree = true).performScrollTo().performClick()
        waitFor(s(R.string.voice_gemini_key))
    }

    @Test
    fun theKeyInTheFieldIsCheckedAndTheAnswerSaid() {
        var askedWith = ""
        GeminiInput.ask = { key, _, _, _ ->
            askedWith = key
            if (key == "AIza-good") GeminiAnswer.Reading(GeminiReading(text = "Comprar pan")) else GeminiAnswer.KeyRefused
        }
        openVoice()
        rule.onNode(hasSetTextAction()).performScrollTo().performTextInput("AIza-good")
        rule.onNodeWithText(s(R.string.voice_gemini_check), useUnmergedTree = true).performScrollTo().performClick()
        waitFor(s(R.string.voice_gemini_check_works))
        shot("settings-gemini-test")
        assertEquals("AIza-good", askedWith)
        // Checking does not save it: the key on disk is still none.
        assertEquals("", runBlocking { app.geminiStore.read() }.key)

        // A refused key says so in its own words.
        rule.onNode(hasSetTextAction()).performTextInput("-typo")
        rule.onNodeWithText(s(R.string.voice_gemini_check), useUnmergedTree = true).performScrollTo().performClick()
        waitFor(s(R.string.voice_gemini_check_refused))
        shot("settings-gemini-refused")
        assertEquals("AIza-good-typo", askedWith)
    }

    /** A capture to look at, the way the tour takes its own. */
    private fun shot(name: String) {
        // The answer's line scrolls into view after a delay, and an effect's delay runs on the
        // test clock: moved on by hand, or the capture is taken before the scroll.
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        Thread.sleep(1_500)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.filesDir, "screenshots").apply { mkdirs() }
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
