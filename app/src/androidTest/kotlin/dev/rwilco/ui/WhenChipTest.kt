package dev.rwilco.ui

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.ThemeMode
import dev.rwilco.ui.editor.EDITOR_TEXT_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The words carry their own "when", and the editor offers it as the first quick chip.
 *
 * A device test because the chip is the whole feature: that typing "mañana a las 9" makes it
 * appear, that it wears its glyph, that one tap turns it into a rule the sentence under the
 * form then reads back, and that it is gone once taken. The reading itself is pinned by
 * `WhenInTextTest` in the model; this walks the screen.
 */
@RunWith(AndroidJUnit4::class)
class WhenChipTest {

    companion object {
        /** The owner's language, and the one the README shows; set before the activity exists. */
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

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    private fun s(id: Int): String = rule.activity.getString(id)

    @Before
    fun aBlankPhone() = runBlocking {
        app.repository.replaceAll(emptyList())
        app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, theme = ThemeMode.DARK, presets = emptyList()) }
    }

    @Test
    fun theWordsOfferTheirOwnWhenAndOneTapTakesIt() {
        openTheEditor()
        rule.onNodeWithTag(EDITOR_TEXT_TAG).performTextInput("Regar las plantas mañana a las 9")

        // The chip: a reading of the words, wearing its glyph, first in the row.
        val fromWords = s(R.string.editor_when_from_words)
        rule.waitUntilShown(fromWords)
        shot("editor-when-chip")
        rule.onNodeWithContentDescription(fromWords).performClick()

        // One tap and it is a rule: the sentence under the form now reads "mañana", and the
        // chip has nothing left to offer.
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText(s(R.string.relative_tomorrow), substring = true, ignoreCase = true, useUnmergedTree = true)
                .fetchSemanticsNodes().size >= 2
        }
        rule.onAllNodesWithContentDescription(fromWords).assertCountEquals(0)
    }

    @Test
    fun aRepeatInTheWordsFillsVuelve() {
        openTheEditor()
        rule.onNodeWithTag(EDITOR_TEXT_TAG).performTextInput("Pastillas cada martes a las 8")

        val fromWords = s(R.string.editor_when_from_words)
        rule.waitUntilShown(fromWords)
        rule.onNodeWithContentDescription(fromWords).performClick()

        // "Vuelve" now says the weekly calendar, and "No repetir" is no longer the answer.
        val weekly = rule.activity.resources.getQuantityString(R.plurals.trigger_repeat_weeks, 1)
        rule.waitUntilShown(weekly)
        rule.onAllNodesWithContentDescription(fromWords).assertCountEquals(0)
    }

    private fun openTheEditor() {
        rule.waitUntilShown(s(R.string.home_new))
        rule.onNodeWithText(s(R.string.home_new), useUnmergedTree = true).performClick()
        rule.waitUntilShown(s(R.string.editor_title_new))
        rule.onNodeWithText(s(R.string.editor_write), useUnmergedTree = true).performClick()
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithTag(EDITOR_TEXT_TAG).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(text, substring = true, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithContentDescription(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** A capture for the README, the way the tour takes its own. */
    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_500)
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
