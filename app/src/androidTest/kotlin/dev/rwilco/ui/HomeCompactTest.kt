package dev.rwilco.ui

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Presence
import dev.rwilco.model.Reminder
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.ui.editor.EDITOR_TEXT_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The list folded away, one card opened out of it, and folded back.
 *
 * What tells the two apart on screen is the rule: a card that is open says its trigger in
 * words — the name of the place — and a folded one keeps only the pin. So that name is the
 * assertion throughout: it is the thing the mode actually costs, and the thing somebody taps a
 * folded card to get back.
 *
 * A **place** on purpose, twice over. A bare one has no floor, so `heroOf` never lifts either
 * card out and both stay ordinary rows (the hero is always open and would answer for the wrong
 * card); and its label is a word this test chose, rather than a time that has to be spelled the
 * way the formatter happens to spell it.
 */
@RunWith(AndroidJUnit4::class)
class HomeCompactTest {

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
    private val words = "Bajar el cubo de la basura"
    private val other = "Regar el jazmín del balcón"

    /** The place's own name: on an open card and on no folded one. */
    private val place = "El portal"

    /** Written during the test, so it is the one card that has just been saved. */
    private val fresh = "Comprar pilas para el mando"
    private val crowded = "Entregar los papeles de la matrícula"

    private fun s(resId: Int): String = rule.activity.getString(resId)

    private fun waitFor(value: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(value, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(600)
        val dir = java.io.File(rule.activity.filesDir, "screenshots").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitGone(value: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(value, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    /** Two cards with a place each, so neither is the hero and both are ordinary rows. */
    @Before
    fun twoCards() = runBlocking {
        app.repository.deleteAll()
        app.settingsStore.update {
            it.copy(presets = emptyList(), compactHome = false, lastSeenVersionCode = BuildConfig.VERSION_CODE)
        }
        val now = app.clock.instant()
        val atThePlace = listOf(TriggerRule(Trigger.Location(40.4168, -3.7038, 100, Presence.INSIDE, place)))
        // Three tags on the first one, which is also what puts the folded row's "…" to work.
        app.repository.save(Reminder(id = "compact-1", text = words, rules = atThePlace, tags = listOf("casa", "compra", "recados"), createdAt = now, updatedAt = now))
        app.repository.save(Reminder(id = "compact-2", text = other, rules = atThePlace, createdAt = now, updatedAt = now))
        // More tags than a line can hold, which is what the folded row's mark is for.
        app.repository.save(
            Reminder(
                id = "compact-3",
                text = crowded,
                rules = atThePlace,
                tags = listOf("universidad", "papeleo", "matrícula", "septiembre", "urgente", "pendiente"),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun theListFoldsAwayAndOneCardCanBeOpenedOutOfIt() {
        waitFor(words)
        // Open, the rule is in words under the reminder.
        val ruleWords = place
        waitFor(ruleWords)

        // The button over "Nuevo" folds the whole list.
        shot("home-open")
        rule.onNodeWithContentDescription(s(R.string.home_compact_on)).performClick()
        waitGone(ruleWords)
        rule.onNodeWithText(words).assertIsDisplayed()
        rule.onNodeWithText(other).assertIsDisplayed()
        shot("home-compact")
        rule.waitUntil(10_000) { runBlocking { app.settingsStore.settings.first().compactHome } }
        // Six tags do not fit on a folded line, and the row says so rather than clipping one
        // in half or quietly showing the first two.
        rule.waitUntil(10_000) { rule.onAllNodesWithText(s(R.string.card_tags_more)).fetchSemanticsNodes().isNotEmpty() }

        // A tap on a folded card opens THAT one, and leaves the rest folded.
        rule.onNodeWithText(words).performClick()
        waitFor(ruleWords)
        shot("home-compact-one-open")

        // And the same tap folds it back again: the gesture undoes itself (0.71.0).
        rule.onNodeWithText(words).performClick()
        waitGone(ruleWords)

        // The toggle is a clean sweep: opening the list out again leaves no exceptions behind.
        rule.onNodeWithContentDescription(s(R.string.home_compact_off)).performClick()
        waitFor(ruleWords)
        rule.waitUntil(10_000) { runBlocking { !app.settingsStore.settings.first().compactHome } }
    }

    @Test
    fun aReminderJustWrittenIsLandedOnAndShownOpen() {
        // "Guardar" used to end on a screen that looked exactly as it had before: with the list
        // folded away the new reminder was one line among the others, wherever its section
        // happened to be. Home goes to it now and opens that one card — the words to read back,
        // and the pencil there to press if something is wrong.
        waitFor(words)
        rule.onNodeWithContentDescription(s(R.string.home_compact_on)).performClick()
        waitGone(place)

        // With nothing kept as a preset, "Nuevo" goes straight to a blank form.
        rule.onNodeWithText(s(R.string.home_new), useUnmergedTree = true).performClick()
        waitFor(s(R.string.editor_title_new))
        rule.onNodeWithText(s(R.string.editor_write), useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(EDITOR_TEXT_TAG).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(EDITOR_TEXT_TAG).performTextInput(fresh)
        rule.onNodeWithText(s(R.string.common_save), useUnmergedTree = true).performClick()

        // On Home, at the new card: open, which is what having a pencil means.
        waitFor(fresh)
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasContentDescription(fresh, substring = true) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        shot("home-new-card-open")
        // And nothing else was opened with it: the fold is still the answer for the rest.
        rule.onAllNodesWithText(place).assertCountEquals(0)
    }
}
