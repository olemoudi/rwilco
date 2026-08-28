package dev.rwilco.ui

import android.app.LocaleManager
import android.content.ClipboardManager
import android.content.Context
import android.os.LocaleList
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.rwilco.MainActivity
import dev.rwilco.R
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bug in the corner: one tap, and the report is on the clipboard.
 *
 * It has always existed three screens deep in the settings, which is the wrong depth for the
 * thing somebody reaches for at the exact moment the app has just done something inexplicable —
 * by the time they have found it the log has moved on. This walks the whole way: the tap, the
 * report actually building, the clipboard, and the line that says so.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsButtonTest {

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

    private fun s(resId: Int): String = rule.activity.getString(resId)

    @Test
    fun oneTapPutsTheWholeReportOnTheClipboard() {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithContentDescription(s(R.string.home_diagnostics)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription(s(R.string.home_diagnostics)).performClick()

        // The line that says it happened. Its absence would mean the report threw on the way.
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText(s(R.string.home_diagnostics_copied), substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        var pasted: String? = null
        rule.activityRule.scenario.onActivity { activity ->
            val clip = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            pasted = clip.primaryClip?.getItemAt(0)?.text?.toString()
        }
        val report = pasted.orEmpty()
        assertTrue("nothing reached the clipboard", report.isNotBlank())
        assertTrue("not the report: $report", report.startsWith("== rwilco diagnostics =="))
        assertTrue("the report was cut short", report.trimEnd().endsWith("== end =="))
    }
}
