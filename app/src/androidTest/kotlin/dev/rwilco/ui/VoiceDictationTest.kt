package dev.rwilco.ui

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.LocaleList
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import dev.rwilco.model.GeminiEvent
import dev.rwilco.model.GeminiReading
import dev.rwilco.model.GeminiRule
import dev.rwilco.voice.GeminiAnswer
import dev.rwilco.voice.GeminiClient
import dev.rwilco.voice.GeminiInput
import dev.rwilco.voice.Listener
import dev.rwilco.voice.PlatformListener
import dev.rwilco.voice.VoiceEvent
import dev.rwilco.voice.VoiceInput
import dev.rwilco.ui.voice.VOICE_ALTERNATIVE_TAG
import dev.rwilco.ui.voice.VOICE_OFFER_TAG
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
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

    /**
     * The sheet as it is drawn, for looking at (dark mode, per the owner): kept in the app's files
     * as the editor tour keeps its own, and pulled with `scripts/emu.sh tour`'s same `run-as tar`.
     */
    private fun shot(name: String) {
        rule.waitForIdle()
        // A sheet's window can be a frame or two from painted on the software-rendered emulator.
        Thread.sleep(1_500)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.filesDir, "screenshots").apply { mkdirs() }
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

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
        GeminiInput.ask = { key, sentence, instruction, schema -> GeminiClient(key).read(sentence, instruction, schema) }
        runBlocking { app.geminiStore.clear() }
    }

    @Test
    fun aSentenceTheRulesCannotReadIsAskedOfGeminiAndOnlyWrittenOnceConfirmed() {
        runBlocking {
            app.geminiStore.setKey("AIza-test")
            app.geminiStore.setEnabled(true)
        }
        var asked = ""
        GeminiInput.ask = { _, said, _, _ ->
            asked = said
            GeminiAnswer.Reading(GeminiReading(text = "Comprar pilas", rules = listOf(GeminiRule(GeminiEvent(kind = "IN_MINUTES", minutes = 20)))))
        }
        sentence = "comprar pilas cuando acabe el partido"
        rule.waitUntil(30_000) { rule.onAllNodesWithText(s(R.string.home_dictate_hold)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(s(R.string.home_dictate)).performSemanticsAction(SemanticsActions.OnClick)
        waitFor(s(R.string.voice_confirm))
        assertEquals("comprar pilas cuando acabe el partido", asked)
        assertEquals(emptyList<String>(), runBlocking { app.repository.openNow() }.map { it.text })
        rule.onNodeWithText(s(R.string.common_save), useUnmergedTree = true).performClick()
        rule.waitUntil(30_000) { runBlocking { app.repository.openNow().any { it.text == "Comprar pilas" } } }
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
    fun theOtherHalfOfAnOpenHourIsOneTapAway() {
        // Eleven and twenty-three are waking hours on any day of the week, whatever the clock says.
        sentence = "llamar a Juan a las 11"
        rule.waitUntil(30_000) { rule.onAllNodesWithText(s(R.string.home_dictate_hold)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(s(R.string.home_dictate)).performSemanticsAction(SemanticsActions.OnClick)
        waitFor(s(R.string.voice_heard))
        val first = runBlocking { app.repository.openNow() }.single()
        val firstAt = (first.rules.single().trigger as Trigger.AtDateTime).at
        // An alternative keeps the sheet open: still there once everything has settled.
        rule.waitForIdle()
        rule.onNodeWithText(s(R.string.voice_done), useUnmergedTree = true).assertExists()
        shot("voice-created-alternative")
        rule.onNodeWithTag(VOICE_ALTERNATIVE_TAG).performClick()
        rule.waitUntil(30_000) {
            val now = runBlocking { app.repository.openNow() }.single()
            (now.rules.single().trigger as Trigger.AtDateTime).at != firstAt
        }
        val swapped = runBlocking { app.repository.openNow() }.single()
        assertEquals(first.id, swapped.id)
        assertEquals((firstAt.hour + 12) % 24, (swapped.rules.single().trigger as Trigger.AtDateTime).at.hour)
        rule.onNodeWithText(s(R.string.voice_done), useUnmergedTree = true).performClick()
        rule.waitUntil(30_000) { rule.onAllNodesWithText(s(R.string.voice_heard)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aDoubtIsWrittenAndNamedAndWaitsForListo() {
        sentence = "llamar al banco mañana antes de comer"
        rule.waitUntil(30_000) { rule.onAllNodesWithText(s(R.string.home_dictate_hold)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(s(R.string.home_dictate)).performSemanticsAction(SemanticsActions.OnClick)
        waitFor("«antes de»")
        assertEquals(listOf("Llamar al banco antes de comer"), runBlocking { app.repository.openNow() }.map { it.text })
        shot("voice-created-doubt")
        rule.onNodeWithText(s(R.string.voice_done), useUnmergedTree = true).performClick()
        rule.waitUntil(30_000) { rule.onAllNodesWithText("«antes de»", substring = true).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theCardOfAReminderJustDictatedIsOnScreenWhenTheSheetGoes() {
        // Fourteen things due today stand before it: written for next month, the card lands far
        // below the fold, which is where it used to be lost.
        runBlocking {
            val now = java.time.Instant.now()
            repeat(14) { i ->
                app.repository.save(
                    dev.rwilco.model.Reminder(
                        id = "voice-busy-$i",
                        text = "Tarea $i",
                        rules = listOf(dev.rwilco.model.TriggerRule(Trigger.AtDateTime(java.time.LocalDateTime.now().plusMinutes(30L + i)))),
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        }
        sentence = "llamar al banco el mes que viene"
        rule.waitUntil(30_000) { rule.onAllNodesWithText(s(R.string.home_dictate_hold)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(s(R.string.home_dictate)).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(30_000) { runBlocking { app.repository.openNow() }.any { it.text == "Llamar al banco" } }
        // Nothing to look at twice: the sheet goes by itself (at once, on the test's clock), and
        // Home goes to the card.
        rule.waitUntil(30_000) { rule.onAllNodesWithText(s(R.string.voice_heard)).fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(30_000) {
            runCatching { rule.onNodeWithText("Llamar al banco", useUnmergedTree = true).assertIsDisplayed() }.isSuccess
        }
        shot("voice-card-on-home")
    }

    // --- Gemini when the grammar has doubts (0.183.0) ---

    private fun geminiOn() = runBlocking {
        app.geminiStore.setKey("AIza-test")
        app.geminiStore.setEnabled(true)
    }

    @Test
    fun aDoubtIsCheckedWithGeminiAndItsOtherReadingIsOfferedNeverApplied() {
        geminiOn()
        val tomorrow = java.time.LocalDate.now().plusDays(1)
        GeminiInput.ask = { _, _, _, _ ->
            GeminiAnswer.Reading(GeminiReading(text = "Llamar al banco", rules = listOf(GeminiRule(GeminiEvent(kind = "AT", date = tomorrow.toString(), time = "13:00")))))
        }
        sentence = "llamar al banco mañana antes de comer"
        rule.waitUntil(30_000) { rule.onAllNodesWithText(s(R.string.home_dictate_hold)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(s(R.string.home_dictate)).performSemanticsAction(SemanticsActions.OnClick)
        waitFor(s(R.string.voice_created_offered))
        // Written as the rules read it; Gemini's reading is only offered.
        assertEquals(listOf("Llamar al banco antes de comer"), runBlocking { app.repository.openNow() }.map { it.text })
        shot("voice-gemini-offer")
        rule.onNodeWithTag(VOICE_OFFER_TAG).performClick()
        rule.waitUntil(30_000) { runBlocking { app.repository.openNow() }.singleOrNull()?.text == "Llamar al banco" }
        assertEquals(tomorrow.atTime(13, 0), (runBlocking { app.repository.openNow() }.single().rules.single().trigger as Trigger.AtDateTime).at)
        // And the rules' own reading is one tap away, to take it back.
        waitFor(s(R.string.voice_original_offer))
    }

    @Test
    fun aGeminiThatNeverAnswersCostsNothing() {
        geminiOn()
        GeminiInput.ask = { _, _, _, _ -> awaitCancellation() }
        sentence = "llamar al banco mañana antes de comer"
        rule.waitUntil(30_000) { rule.onAllNodesWithText(s(R.string.home_dictate_hold)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(s(R.string.home_dictate)).performSemanticsAction(SemanticsActions.OnClick)
        waitFor(s(R.string.voice_created_refining))
        // Written before Gemini says anything.
        assertEquals(listOf("Llamar al banco antes de comer"), runBlocking { app.repository.openNow() }.map { it.text })
        shot("voice-refining")
        // And past the patience, it stays as it was, and says so.
        waitFor(s(R.string.voice_created_failed))
        assertEquals(listOf("Llamar al banco antes de comer"), runBlocking { app.repository.openNow() }.map { it.text })
    }

    @Test
    fun whileGeminiThinksTheWordsCanGoToTheEditor() {
        geminiOn()
        GeminiInput.ask = { _, _, _, _ -> awaitCancellation() }
        sentence = "comprar pilas cuando acabe el partido"
        rule.waitUntil(30_000) { rule.onAllNodesWithText(s(R.string.home_dictate_hold)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(s(R.string.home_dictate)).performSemanticsAction(SemanticsActions.OnClick)
        waitFor(s(R.string.voice_asking_gemini))
        rule.onNodeWithText(s(R.string.voice_type), useUnmergedTree = true).performClick()
        waitFor(s(R.string.editor_title_new))
        waitFor("comprar pilas cuando acabe el partido")
        assertEquals(emptyList<String>(), runBlocking { app.repository.openNow() }.map { it.text })
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
