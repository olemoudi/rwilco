package dev.rwilco.ui

import android.app.LocaleManager
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.MainActivity
import dev.rwilco.R
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A switch that cannot do what it says, saying so.
 *
 * Android gates every app's touch feedback behind its own "vibración al tocar", and there is no
 * asking it nicely: with that off, this app's switch can only ever turn the tick *off*. Somebody
 * turns it on, nothing happens, and the app looks broken — which is a worse thing to ship than
 * the missing tick. So it gets the same red row and the same one button as every other silently
 * failing state on that screen.
 */
@RunWith(AndroidJUnit4::class)
class HapticsWarningTest {

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

    private var was = "1"

    private fun s(resId: Int): String = rule.activity.getString(resId)

    /** The one thing only the shell can do: this is a system setting, not the app's. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    private fun systemHaptics(on: Boolean) {
        shell("settings put system haptic_feedback_enabled ${if (on) 1 else 0}")
        Thread.sleep(400)
    }

    @Before
    fun remember() {
        was = shell("settings get system haptic_feedback_enabled").trim()
    }

    @After
    fun putItBack() {
        shell("settings put system haptic_feedback_enabled ${if (was == "0") 0 else 1}")
    }

    private fun openLook() {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithContentDescription(s(R.string.home_settings)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription(s(R.string.home_settings)).performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText(s(R.string.settings_group_look), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(s(R.string.settings_group_look), useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test
    fun theSwitchOwnsUpWhenAndroidHasTheLastWord() {
        systemHaptics(false)
        openLook()
        rule.onNodeWithText(s(R.string.settings_haptics_system_off), useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun nothingIsSaidWhenThereIsNothingWrong() {
        systemHaptics(true)
        openLook()
        rule.waitForIdle()
        val warned = rule.onAllNodesWithText(s(R.string.settings_haptics_system_off), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        assert(!warned) { "it warned about a phone that is perfectly willing" }
    }
}
