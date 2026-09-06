package dev.rwilco.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.R
import dev.rwilco.ui.alert.AlertContent
import dev.rwilco.ui.alert.AlertScreen
import dev.rwilco.ui.theme.RwilcoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * A routine's alert is not a reminder's.
 *
 * The screen that takes a phone over is the one place where "which of the two is this?" has to
 * be answered before anything is read, and the answer is a colour and a word: the lamp and the
 * eyebrow wear the routines' own rose rather than the amber that means what fires next, and the
 * eyebrow says RUTINA instead of the app's name. A picture, because that is how a colour is
 * judged; the word is asserted, because that one can be.
 */
@RunWith(AndroidJUnit4::class)
class AlertRoutineTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val today = LocalDate.of(2026, 9, 6)

    private fun content(text: String, routine: Boolean) = AlertContent(
        text = text,
        tags = listOf("coche"),
        trigger = null,
        family = dev.rwilco.model.TriggerFamily.TIME,
        today = today,
        defaultTime = LocalTime.of(9, 0),
        routine = routine,
    )

    @Test
    fun aRoutineRingsUnderItsOwnWord() {
        rule.setContent {
            RwilcoTheme(darkTheme = true) {
                AlertScreen(
                    content = content("Mover el coche", routine = true),
                    preview = false,
                    onDone = {},
                    onSnooze = {},
                    onView = {},
                )
            }
        }
        rule.waitForIdle()
        assertTrue(
            "a routine's alert says so above the words",
            rule.onAllNodesWithText(context.getString(R.string.alert_routine_label), ignoreCase = true, substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        shot("alert-routine")
    }

    @Test
    fun aReminderKeepsTheAppsOwnWord() {
        rule.setContent {
            RwilcoTheme(darkTheme = true) {
                AlertScreen(
                    content = content("Comprar filtros", routine = false),
                    preview = false,
                    onDone = {},
                    onSnooze = {},
                    onView = {},
                )
            }
        }
        rule.waitForIdle()
        assertTrue(
            "and everything else keeps the app's name and its amber",
            rule.onAllNodesWithText(context.getString(R.string.app_name), ignoreCase = true, substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        shot("alert-not-a-routine")
    }

    private fun shot(name: String) {
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
