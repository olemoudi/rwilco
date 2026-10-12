package dev.rwilco.ui

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.LocaleList
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.flow.first
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
import dev.rwilco.ui.voice.VOICE_FIX_FIELD_TAG
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
 * sentence goes in as the recognizer would hand it over (stretches closed by pauses, partials,
 * and the final once "Terminado" asks for it), and what comes out is either a reminder on Home or
 * the editor with the words in it.
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

    /** What the fake ear will have heard, stretch by stretch: each but the last closed by a pause. */
    private var stretches = listOf("")

    /** What the fake ear will have heard, in one breath. */
    private var sentence: String
        get() = stretches.joinToString(" ")
        set(value) {
            stretches = listOf(value)
        }

    /**
     * Speaks [stretches] the way Android's recognizer does since 0.186.0: ready, then each stretch
     * as partials and closed by a pause, the last one still being said — each a beat apart — and
     * its final only once "Terminado" asks for it.
     */
    private inner class FakeEar : Listener {
        private val main = Handler(Looper.getMainLooper())
        private var onEvent: ((VoiceEvent) -> Unit)? = null
        override fun start(locale: Locale, words: List<String>, onEvent: (VoiceEvent) -> Unit) {
            this.onEvent = onEvent
            main.postDelayed({ onEvent(VoiceEvent.Ready) }, 50)
            var at = 150L
            for (stretch in stretches.dropLast(1)) {
                main.postDelayed({ onEvent(VoiceEvent.Partial(stretch)) }, at)
                main.postDelayed({ onEvent(VoiceEvent.Segment(stretch)) }, at + 100)
                at += 250
            }
            val last = stretches.last()
            main.postDelayed({ onEvent(VoiceEvent.Partial(last.substringBefore(' '))) }, at)
            main.postDelayed({ onEvent(VoiceEvent.Partial(last)) }, at + 150)
        }
        override fun stop() {
            main.postDelayed({ onEvent?.invoke(VoiceEvent.Final(stretches.last())) }, 50)
        }
        override fun cancel() {
            onEvent = null
            main.removeCallbacksAndMessages(null)
        }
    }

    /** The sheet opened, everything said, then "Terminado". */
    private fun dictate() {
        open()
        finish()
    }

    /** The click a screen reader gives every hold in the app. */
    private fun open() {
        rule.waitUntil(30_000) { rule.onAllNodesWithText(s(R.string.home_dictate_hold)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(s(R.string.home_dictate)).performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Once all of it is on the sheet, "Terminado": nothing ends a dictation by itself. */
    private fun finish() {
        waitFor(sentence)
        rule.onNodeWithText(s(R.string.voice_stop), useUnmergedTree = true).performClick()
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
            it.copy(presets = emptyList(), compactHome = false, savedPlaces = listOf(casa), lastSeenVersionCode = BuildConfig.VERSION_CODE, voiceFixes = emptyMap())
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
        dictate()
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
        // And it says why (0.191.0).
        waitFor(s(R.string.home_dictate_tapped))
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
        finish()
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
    fun aPauseDoesNotEndItAndEveryStretchIsRead() {
        // The owner's sentence (2026-10-12), said with a pause in the middle of it: once, the
        // pause was the end, and "y llevar 10 minutos allí" never reached the reading.
        stretches = listOf("comprar pan al llegar a casa", "y llevar 10 minutos allí")
        dictate()
        rule.waitUntil(30_000) { runBlocking { app.repository.openNow() }.any { it.text == "Comprar pan" } }
        val place = runBlocking { app.repository.openNow() }.single().rules.single().trigger as Trigger.Location
        assertEquals("Casa", place.label)
        assertEquals(10, place.dwellMinutes)
    }

    @Test
    fun aCorrectionSaidIsReadInPlaceOfWhatItCorrectsAndShownStruck() {
        // Said calmly, corrected after a pause (0.187.0): one hour, not two, and the sheet waits
        // for "Listo" so what was taken out can be seen.
        stretches = listOf("llamar a Juan mañana a las cinco", "no, a las seis")
        dictate()
        rule.waitUntil(30_000) { runBlocking { app.repository.openNow() }.any { it.text == "Llamar a Juan" } }
        val at = when (val trigger = runBlocking { app.repository.openNow() }.single().rules.single().trigger) {
            is Trigger.AtDateTime -> trigger.at.toLocalTime()
            is Trigger.RelativeDate -> trigger.time
            else -> null
        }
        assertEquals(java.time.LocalTime.of(18, 0), at)
        waitFor("«a las cinco no»")
        rule.waitForIdle()
        rule.onNodeWithText(s(R.string.voice_done), useUnmergedTree = true).assertExists()
        shot("voice-created-correction")
        rule.onNodeWithText(s(R.string.voice_done), useUnmergedTree = true).performClick()
        rule.waitUntil(30_000) { rule.onAllNodesWithText(s(R.string.voice_heard)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aNameAlreadyWrittenIsWrittenTheSameWhenItIsMisheard() {
        // The owner's "Vito" (0.189.0): written once in a reminder, it is his word from then on,
        // and a recognizer's "Bito" is put right.
        runBlocking {
            val now = java.time.Instant.now()
            app.repository.save(dev.rwilco.model.Reminder(id = "voice-vito", text = "Llamar a Vito", createdAt = now, updatedAt = now))
        }
        sentence = "comprar flores para Bito mañana"
        open()
        // Put right as it is heard, not only once it is in.
        waitFor("comprar flores para Vito mañana")
        rule.onNodeWithText(s(R.string.voice_stop), useUnmergedTree = true).performClick()
        rule.waitUntil(30_000) { runBlocking { app.repository.openNow() }.any { it.text == "Comprar flores para Vito" } }
    }

    @Test
    fun whatWasHeardIsPutRightByHandIntoTheSameReminderAndLearned() {
        // An open hour keeps the sheet open (its other half is offered), as somebody reading it would.
        sentence = "llamar a visto a las 11"
        dictate()
        rule.waitUntil(30_000) { runBlocking { app.repository.openNow() }.any { it.text == "Llamar a visto" } }
        val first = runBlocking { app.repository.openNow() }.single()
        rule.onNodeWithText(s(R.string.voice_fix_text), useUnmergedTree = true).performClick()
        rule.onNodeWithTag(VOICE_FIX_FIELD_TAG).performTextReplacement("llamar a Vito a las 11")
        shot("voice-fix-by-hand")
        rule.onNodeWithText(s(R.string.voice_reread), useUnmergedTree = true).performClick()
        rule.waitUntil(30_000) { runBlocking { app.repository.openNow() }.singleOrNull()?.text == "Llamar a Vito" }
        assertEquals(first.id, runBlocking { app.repository.openNow() }.single().id)
        // Learned: the same mishearing in the same place is put right next time.
        rule.waitUntil(30_000) { runBlocking { app.settingsStore.settings.first() }.voiceFixes["a visto"] == "Vito" }
        rule.onNodeWithText(s(R.string.voice_done), useUnmergedTree = true).performClick()
        rule.waitUntil(30_000) { rule.onAllNodesWithText(s(R.string.voice_heard)).fetchSemanticsNodes().isEmpty() }
        sentence = "escribir a visto el viernes"
        open()
        waitFor("escribir a Vito el viernes")
        rule.onNodeWithText(s(R.string.voice_stop), useUnmergedTree = true).performClick()
        rule.waitUntil(30_000) { runBlocking { app.repository.openNow() }.any { it.text == "Escribir a Vito" } }
    }

    @Test
    fun aFixTheRulesCannotReadOpensTheSameReminderInTheEditor() {
        // A fix by hand that says no "when" goes to the editor — on the reminder it was fixing,
        // with the new words, and backing out leaves that reminder as it was (0.190.1).
        sentence = "llamar a Juan a las 11"
        dictate()
        rule.waitUntil(30_000) { runBlocking { app.repository.openNow() }.any { it.text == "Llamar a Juan" } }
        val first = runBlocking { app.repository.openNow() }.single()
        rule.onNodeWithText(s(R.string.voice_fix_text), useUnmergedTree = true).performClick()
        rule.onNodeWithTag(VOICE_FIX_FIELD_TAG).performTextReplacement("llamar a Pedro")
        rule.onNodeWithText(s(R.string.voice_reread), useUnmergedTree = true).performClick()
        waitFor(s(R.string.voice_to_editor_nothing))
        waitFor("llamar a Pedro")
        fun asWritten() = runBlocking { app.repository.openNow() }.map { Triple(it.id, it.text, it.rules) }
        assertEquals(listOf(Triple(first.id, first.text, first.rules)), asWritten())
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(listOf(Triple(first.id, first.text, first.rules)), asWritten())
    }

    @Test
    fun moreSaidAfterItWasWrittenCorrectsTheSameReminder() {
        // An open hour keeps the sheet open (its other half is offered), as somebody reading it would.
        sentence = "llamar a Juan a las 11"
        dictate()
        rule.waitUntil(30_000) { runBlocking { app.repository.openNow() }.any { it.text == "Llamar a Juan" } }
        val first = runBlocking { app.repository.openNow() }.single()
        shot("voice-created-fix-chips")
        sentence = "no, a las 12"
        rule.onNodeWithText(s(R.string.voice_say_more), useUnmergedTree = true).performClick()
        finish()
        rule.waitUntil(30_000) { (runBlocking { app.repository.openNow() }.singleOrNull()?.rules?.single()?.trigger as? Trigger.AtDateTime)?.at?.hour == 12 }
        assertEquals(first.id, runBlocking { app.repository.openNow() }.single().id)
        assertEquals("Llamar a Juan", runBlocking { app.repository.openNow() }.single().text)
    }

    @Test
    fun aMicrophonePausedKeepsTheWordsUntilTerminado() {
        stretches = listOf("comprar pan", "al llegar a casa")
        open()
        waitFor(sentence)
        // Leaving the app closes the microphone (as a long silence does), and the words stay.
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor(s(R.string.voice_paused))
        shot("voice-paused")
        assertEquals(emptyList<String>(), runBlocking { app.repository.openNow() }.map { it.text })
        rule.onNodeWithText(s(R.string.voice_stop), useUnmergedTree = true).performClick()
        rule.waitUntil(30_000) { runBlocking { app.repository.openNow() }.any { it.text == "Comprar pan" } }
    }

    @Test
    fun aSentenceWithTwoWhensIsOneFencedRule() {
        sentence = "comprar pan al llegar a casa por la tarde con pantalla completa"
        dictate()
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
        dictate()
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
        dictate()
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
        dictate()
        rule.waitUntil(30_000) { runBlocking { app.repository.openNow() }.any { it.text == "Llamar al banco" } }
        // Nothing to look at twice: the sheet goes by itself (at once, on the test's clock), and
        // Home goes to the card.
        rule.waitUntil(30_000) { rule.onAllNodesWithText(s(R.string.voice_heard)).fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(30_000) {
            runCatching { rule.onNodeWithText("Llamar al banco", useUnmergedTree = true).assertIsDisplayed() }.isSuccess
        }
        // Gone by itself, with its undo a moment longer down here (0.191.0).
        waitFor(rule.activity.getString(R.string.voice_created_named, "Llamar al banco"))
        shot("voice-card-on-home")
        rule.onNodeWithText(s(R.string.common_undo), useUnmergedTree = true).performClick()
        rule.waitUntil(30_000) { runBlocking { app.repository.openNow() }.none { it.text == "Llamar al banco" } }
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
        dictate()
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
        dictate()
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
        dictate()
        waitFor(s(R.string.voice_asking_gemini))
        rule.onNodeWithText(s(R.string.voice_type), useUnmergedTree = true).performClick()
        waitFor(s(R.string.editor_title_new))
        waitFor("comprar pilas cuando acabe el partido")
        assertEquals(emptyList<String>(), runBlocking { app.repository.openNow() }.map { it.text })
    }

    @Test
    fun aSentenceWithNoWhenGoesToTheEditorWithItsWords() {
        sentence = "llamar a mi madre"
        dictate()
        waitFor(s(R.string.voice_to_editor_nothing))
        waitFor(s(R.string.editor_title_new))
        waitFor("llamar a mi madre")
        assertEquals(emptyList<String>(), runBlocking { app.repository.openNow() }.map { it.text })
    }
}
