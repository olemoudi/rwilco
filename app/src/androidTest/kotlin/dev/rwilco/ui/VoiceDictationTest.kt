package dev.rwilco.ui

import android.app.LocaleManager
import android.os.Handler
import android.os.Looper
import android.os.LocaleList
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Presence
import dev.rwilco.model.SavedPlace
import dev.rwilco.model.Trigger
import dev.rwilco.voice.Listener
import dev.rwilco.voice.PlatformListener
import dev.rwilco.voice.VoiceEvent
import dev.rwilco.voice.VoiceInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * A reminder said to the mic, through the real sheet and the real Home, with a fake ear: the
 * sentence goes in as the recognizer would hand it over (a partial, then the final), and what
 * comes out is either a reminder on Home or the editor with the words in it.
 *
 * The undo is not walked here: the sheet goes by itself a few seconds after "Creado", and the
 * test clock fast-forwards exactly that kind of wait. Its arithmetic is `VoiceStepTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class VoiceDictationTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun useSpanish() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("es-ES")
        }
    }

    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS, android.Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as RwilcoApplication
    private val casa = SavedPlace("Casa", 40.4168, -3.7038, 120, id = "voice-casa")

    /** What the fake ear will have heard. */
    private var sentence = ""

    /** Speaks [sentence] the way Android's recognizer does: ready, a partial, the final — each a beat apart. */
    private inner class FakeEar : Listener {
        private val main = Handler(Looper.getMainLooper())
        override fun start(locale: Locale, onEvent: (VoiceEvent) -> Unit) {
            main.postDelayed({ onEvent(VoiceEvent.Ready) }, 50)
            main.postDelayed({ onEvent(VoiceEvent.Partial(sentence.substringBefore(' '))) }, 150)
            main.postDelayed({ onEvent(VoiceEvent.Final(sentence)) }, 300)
        }
        override fun stop() = Unit
        override fun cancel() = main.removeCallbacksAndMessages(null)
    }

    private fun s(resId: Int): String = rule.activity.getString(resId)

    private fun waitFor(value: String) {
        rule.waitUntil(30_000) { rule.onAllNodesWithText(value, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Before
    fun quietHomeWithAPlace() = runBlocking {
        VoiceInput.factory = { FakeEar() }
        app.repository.deleteAll()
        app.settingsStore.update {
            it.copy(presets = emptyList(), compactHome = false, savedPlaces = listOf(casa), lastSeenVersionCode = BuildConfig.VERSION_CODE)
        }
    }

    @After
    fun realEar() {
        VoiceInput.factory = ::PlatformListener
    }

    @Test
    fun aTapOnTheDoorIsNotEnough() {
        sentence = "comprar pan cuando llegue a casa"
        rule.waitUntil(30_000) { rule.onAllNodesWithText(s(R.string.home_dictate_hold)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(s(R.string.home_dictate)).performClick()
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText(s(R.string.voice_listening)).fetchSemanticsNodes().size)
        assertEquals(emptyList<String>(), runBlocking { app.repository.openNow() }.map { it.text })
    }

    @Test
    fun aSentenceWithItsWhenIsWrittenAndLandsOnHome() {
        sentence = "comprar pan cuando llegue a casa"
        // Held as "hecho" is held: a finger kept down until the ring has filled and the sheet
        // has done its work, and only then lifted (the way `holdToAnswer` holds the alert).
        rule.waitUntil(30_000) { rule.onAllNodesWithText(s(R.string.home_dictate_hold)).fetchSemanticsNodes().isNotEmpty() }
        val door = rule.onNodeWithContentDescription(s(R.string.home_dictate))
        door.performTouchInput { down(center) }
        // Written, with the words that are left and the doorway into the saved place.
        rule.waitUntil(30_000) { runBlocking { app.repository.openNow().any { it.text == "Comprar pan" } } }
        door.performTouchInput { up() }
        val written = runBlocking { app.repository.openNow() }.single()
        val place = written.rules.single().trigger as Trigger.Location
        assertEquals("Casa", place.label)
        assertEquals(Presence.INSIDE, place.presence)
        assertEquals(true, place.onCrossing)
        assertEquals("voice-casa", place.placeId)
        // And on Home once the sheet has gone.
        waitFor("Comprar pan")
    }

    @Test
    fun aSentenceWithTwoWhensIsOneFencedRule() {
        sentence = "comprar pan al llegar a casa por la tarde con pantalla completa"
        rule.waitUntil(30_000) { rule.onAllNodesWithText(s(R.string.home_dictate_hold)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(s(R.string.home_dictate)).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(30_000) { runBlocking { app.repository.openNow().any { it.text == "Comprar pan" } } }
        val written = runBlocking { app.repository.openNow() }.single()
        val rule = written.rules.single()
        assertEquals("Casa", (rule.trigger as Trigger.Location).label)
        assertEquals(1, rule.conditions.size)
        assertEquals(true, dev.rwilco.model.Action.FULL_SCREEN in written.actions)
    }

    @Test
    fun aSentenceWithNoWhenGoesToTheEditorWithItsWords() {
        sentence = "llamar a mi madre"
        // The click a screen reader gives every hold in the app.
        rule.waitUntil(30_000) { rule.onAllNodesWithText(s(R.string.home_dictate_hold)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(s(R.string.home_dictate)).performSemanticsAction(SemanticsActions.OnClick)
        waitFor(s(R.string.voice_to_editor_nothing))
        waitFor(s(R.string.editor_title_new))
        waitFor("llamar a mi madre")
        assertEquals(emptyList<String>(), runBlocking { app.repository.openNow() }.map { it.text })
    }
}
