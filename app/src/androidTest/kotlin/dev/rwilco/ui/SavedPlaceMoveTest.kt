package dev.rwilco.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Presence
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.Reminder
import dev.rwilco.model.SavedPlace
import dev.rwilco.model.Status
import dev.rwilco.model.ThemeMode
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A saved place edited or deleted in Settings asks about what rings by it — deleting it, twice
 * before anything is deleted (0.145.0). And an edit asks whether what uses it goes with it, and says what by
 * name (0.143.0, keys 0.144.0): what uses its pin, its key or its name is listed, the finished one and the
 * one somewhere else are not, and the answer reaches the rows — or, for "only the place",
 * leaves them exactly as they were.
 */
@RunWith(AndroidJUnit4::class)
class SavedPlaceMoveTest {

    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    private fun s(id: Int): String = rule.activity.getString(id)

    private val office = SavedPlace("Oficina", 40.501234, -3.661234, 200, id = "office-key")
    private val home = SavedPlace("Casa", 40.4169, -3.7035, 50)

    private val leaving = "Registrar la jornada"
    private val routine = "Regar el ficus"
    private val finished = "Pedir cita en la oficina"
    private val elsewhere = "Comprar pan"
    // The two a pin alone could not find: one keyed to the office on a pin that has drifted,
    // and one from before keys, on an old pin, known only by the name. (A copy under a name of
    // its own has nothing a rename would change, so it is not listed: SavedPlaceMoveTest on the JVM.)
    private val keyed = "Fichar al entrar"
    private val byName = "Llevar el portátil"

    private fun at(place: SavedPlace, presence: Presence) =
        TriggerRule(Trigger.Location(place.lat, place.lng, place.radiusM, presence, place.label, onCrossing = true))

    @Before
    fun fourReminders() = runBlocking {
        app.repository.replaceAll(emptyList())
        app.settingsStore.update {
            it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, theme = ThemeMode.DARK, savedPlaces = listOf(office, home))
        }
        val now = app.clock.instant()
        fun reminder(
            id: String,
            text: String,
            place: SavedPlace,
            status: Status = Status.ACTIVE,
            recurrence: Recurrence = Recurrence.None,
            placeId: String? = null,
        ) =
            Reminder(
                id = id,
                text = text,
                rules = listOf(TriggerRule(at(place, Presence.OUTSIDE).trigger.let { (it as Trigger.Location).copy(placeId = placeId) })),
                status = status,
                recurrence = recurrence,
                createdAt = now,
                updatedAt = now,
            )
        app.repository.saveAll(
            listOf(
                reminder("leaving", leaving, office),
                reminder("routine", routine, office, Status.PAUSED, Recurrence.Since(21, RecurrenceUnit.DAYS)),
                reminder("finished", finished, office, Status.DONE),
                reminder("elsewhere", elsewhere, home),
                reminder("keyed", keyed, office.copy(lat = 40.4, lng = -3.6), placeId = office.id),
                reminder("byName", byName, office.copy(lat = 40.49, lng = -3.65)),
            ),
        )
    }

    /** Settings, with the places group open on the office's row. */
    private fun openThePlaces() {
        rule.onNodeWithContentDescription(s(R.string.home_settings)).performClick()
        rule.waitUntilShown(s(R.string.settings_places))
        // The group opens itself when a place reminder is missing its grant, as it is here.
        if (rule.onAllNodesWithText(office.label, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            // The group's own row: the word is also on the readiness line above it.
            rule.onNode(hasText(s(R.string.settings_places)) and isHeading(), useUnmergedTree = true).performScrollTo().performClick()
        }
        rule.waitUntilShown(office.label)
    }

    /** The office's row opened, and the name changed to [name] in its sheet. */
    private fun renameTheOffice(name: String) {
        openThePlaces()
        rule.onNodeWithText(office.label, useUnmergedTree = true).performScrollTo().performClick()
        rule.waitUntilShown(s(R.string.place_saved_title))
        rule.onNode(hasSetTextAction() and hasText(office.label)).performTextReplacement(name)
        rule.onNodeWithText(s(R.string.sheet_done), useUnmergedTree = true).performClick()
        rule.waitUntilShown(rule.activity.getString(R.string.place_move_title, office.label))
    }

    private fun savedLabel(): String = runBlocking { app.settingsStore.settings.first().savedPlaces.first().label }

    private fun placeId(id: String): String? = runBlocking {
        (app.repository.get(id)?.rules?.single()?.trigger as? Trigger.Location)?.placeId
    }

    private fun label(id: String): String? = runBlocking {
        (app.repository.get(id)?.rules?.single()?.trigger as? Trigger.Location)?.label
    }

    @Test
    fun theQuestionNamesWhatUsesThePlaceAndUpdatesIt() {
        renameTheOffice("Curro")
        // What is still to ring on that pin, by name, with the routine and the pause said...
        rule.onNodeWithText(leaving, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(routine, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(keyed, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(byName, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("${s(R.string.home_search_kind_routine)} · ${s(R.string.home_tag_paused)}", useUnmergedTree = true)
            .assertIsDisplayed()
        // ...and neither the finished one nor the one somewhere else.
        rule.onAllNodesWithText(finished, useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithText(elsewhere, useUnmergedTree = true).assertCountEquals(0)
        shot("settings-place-move")

        rule.onNodeWithText(s(R.string.place_move_confirm), useUnmergedTree = true).performClick()
        // The rows are written first and the place after them, so it is the place that says it is over.
        rule.waitUntil(timeoutMillis = 10_000) { savedLabel() == "Curro" }
        assertEquals("Curro", label("leaving"))
        assertEquals("Curro", label("routine"))
        assertEquals("found by the key, on a pin nothing else would match", "Curro", label("keyed"))
        assertEquals("Curro", label("byName"))
        assertEquals("found by name once, by the key from now on", office.id, placeId("byName"))
        assertEquals("history stays as written", office.label, label("finished"))
        assertEquals(home.label, label("elsewhere"))
    }

    @Test
    fun onlyThePlaceLeavesTheRemindersAsTheyWere() {
        renameTheOffice("Curro")
        rule.onNodeWithText(s(R.string.place_move_keep), useUnmergedTree = true).performClick()
        rule.waitUntil(timeoutMillis = 10_000) { savedLabel() == "Curro" }
        assertEquals(office.label, label("leaving"))
        assertEquals(office.label, label("routine"))
    }

    /** The office's bin — the first of the two rows — and the question it asks. */
    private fun binTheOffice() {
        openThePlaces()
        rule.onAllNodesWithContentDescription(s(R.string.settings_remove_place))[0].performScrollTo().performClick()
        rule.waitUntilShown(rule.activity.getString(R.string.place_remove_title, office.label))
    }

    private fun exists(id: String): Boolean = runBlocking { app.repository.get(id) != null }

    private fun savedLabels(): List<String> = runBlocking { app.settingsStore.settings.first().savedPlaces.map { it.label } }

    @Test
    fun deletingThePlaceAsksAndCanKeepWhatRingsByIt() {
        binTheOffice()
        for (words in listOf(leaving, routine, keyed, byName)) {
            rule.onNodeWithText(words, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        rule.onAllNodesWithText(finished, useUnmergedTree = true).assertCountEquals(0)
        shot("settings-place-remove")
        rule.onNodeWithText(s(R.string.place_remove_keep), useUnmergedTree = true).performClick()
        rule.waitUntil(timeoutMillis = 10_000) { savedLabels() == listOf(home.label) }
        // Each goes on with its own copy of the circle.
        assertEquals(office.label, label("leaving"))
        assertTrue(listOf("leaving", "routine", "keyed", "byName", "finished", "elsewhere").all(::exists))
    }

    @Test
    fun deletingWhatRingsByItIsAskedTwice() {
        binTheOffice()
        rule.onNodeWithText(s(R.string.place_remove_delete), useUnmergedTree = true).performClick()
        rule.waitUntilShown(s(R.string.place_remove_sure_title))
        shot("settings-place-remove-sure")
        // "No" the second time is no: nothing deleted, the place still there.
        rule.onNodeWithText(s(R.string.sheet_cancel), useUnmergedTree = true).performClick()
        rule.waitUntilGone(s(R.string.place_remove_sure_title))
        assertEquals(listOf(office.label, home.label), savedLabels())
        assertTrue(listOf("leaving", "routine", "keyed", "byName").all(::exists))

        rule.onAllNodesWithContentDescription(s(R.string.settings_remove_place))[0].performScrollTo().performClick()
        rule.waitUntilShown(rule.activity.getString(R.string.place_remove_title, office.label))
        rule.onNodeWithText(s(R.string.place_remove_delete), useUnmergedTree = true).performClick()
        rule.waitUntilShown(s(R.string.place_remove_sure_title))
        rule.onNodeWithText(s(R.string.place_remove_sure_confirm), useUnmergedTree = true).performClick()
        rule.waitUntil(timeoutMillis = 10_000) { savedLabels() == listOf(home.label) }
        assertTrue("what rang by it went with it", listOf("leaving", "routine", "keyed", "byName").none(::exists))
        assertTrue("history and the other place's are untouched", exists("finished") && exists("elsewhere"))
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_000)
        val dir = java.io.File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun ComposeTestRule.waitUntilShown(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeTestRule.waitUntilGone(text: String) {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
}
