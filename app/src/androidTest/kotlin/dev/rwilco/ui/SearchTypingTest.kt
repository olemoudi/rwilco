package dev.rwilco.ui

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.Reminder
import dev.rwilco.ui.home.HOME_SEARCH_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Letters land in the order they were typed.
 *
 * **This does not reproduce the bug it was written for, and says so rather than pretending.**
 * The field used to hand its text to the ViewModel and read it back off a StateFlow, and that
 * round trip crosses a dispatcher and rebuilds the whole result list on the way; two keystrokes
 * inside that gap handed the field a string one letter old, and a text field given a plain
 * String works the selection out for itself — one place to the left, against a stale value.
 * Holding the clock still between keystrokes was the attempt to make that gap reliable, and it
 * does not: `performTextInput` synchronises inside itself, so the field is never actually handed
 * the stale value. It passed before the fix as well as after.
 *
 * What it is worth keeping for is the contract in its name, which nothing else checks. The fix
 * itself rests on the shape of the code — a field that never reads an external string cannot be
 * handed a stale one — and on somebody watching the caret jump on a real phone.
 */
@RunWith(AndroidJUnit4::class)
class SearchTypingTest {

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

    @Before
    fun seed() = runBlocking {
        app.repository.deleteAll()
        val now = app.clock.instant()
        // Something to search, so the round trip has real work to do on every keystroke.
        (1..20).forEach {
            app.repository.save(Reminder(id = "typing-$it", text = "Comprar cosas número $it", createdAt = now, updatedAt = now))
        }
    }

    @Test
    fun lettersLandInTheOrderTheyWereTyped() {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithContentDescription(s(R.string.home_search)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription(s(R.string.home_search)).performClick()
        rule.waitForIdle()

        // Held still between keystrokes: the attempt at the gap. See the note above.
        rule.mainClock.autoAdvance = false
        val field = rule.onNodeWithTag(HOME_SEARCH_TAG)
        "comprar".forEach { field.performTextInput(it.toString()) }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()

        field.assertTextContains("comprar")
    }
}
