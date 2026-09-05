package dev.rwilco.ui

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.model.Condition
import dev.rwilco.model.Period
import dev.rwilco.model.Presence
import dev.rwilco.model.Recurrence
import dev.rwilco.model.RecurrenceUnit
import dev.rwilco.model.RepeatUnit
import dev.rwilco.model.RuleMatch
import dev.rwilco.model.Trigger
import dev.rwilco.model.TriggerRule
import dev.rwilco.ui.editor.ReminderSentence
import dev.rwilco.ui.editor.sentenceParts
import dev.rwilco.ui.theme.RwilcoTheme
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Every "when" the app can hold, said as the sentence over the save button.
 *
 * A phrase is right or wrong by ear, not by test: "Casa mientras no estoy" passes any assertion
 * you could write about it and is still not something a person would say. So this renders one
 * line per shape, in Spanish, and leaves a picture to read.
 */
@RunWith(AndroidJUnit4::class)
class SentenceTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val today = LocalDate.of(2026, 8, 28)

    companion object {
        @BeforeClass
        @JvmStatic
        fun useSpanish() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("es-ES")
        }
    }

    private val home = Trigger.Location(40.4169, -3.7035, 150, Presence.OUTSIDE, "Casa")
    private val office = Trigger.Location(40.42, -3.70, 200, Presence.INSIDE, "la oficina", onCrossing = true)
    private val gym = Trigger.Location(40.43, -3.69, 200, Presence.INSIDE, "el gimnasio", onCrossing = true, dwellMinutes = 10)
    private val evening = Trigger.Interval(LocalTime.of(18, 30), LocalTime.of(20, 0), setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
    private val nine = Trigger.AtDateTime(LocalDateTime.of(2026, 8, 29, 9, 0))
    private val everyDayAtEight = Trigger.AtTime(LocalTime.of(20, 30), DayOfWeek.entries.toSet())
    private val threeMinutes = Trigger.Countdown(minutes = 3)
    private val twiceADay = Trigger.Random(2, Period.DAY, LocalTime.of(10, 0), LocalTime.of(20, 0), emptySet())

    @Test
    fun everyShapeReadsLikeSomethingAPersonWouldSay() {
        val drafts = listOf(
            // A place read as a state, which is the one that used to read backwards.
            Triple("Coger el paraguas", listOf(TriggerRule(home)), Recurrence.None),
            Triple("Fichar la salida", listOf(TriggerRule(office)), Recurrence.None),
            // A doorway asked to be stayed at: the crossing is no longer the moment, so the
            // sentence must not go on calling it one.
            Triple("Ducharme", listOf(TriggerRule(gym)), Recurrence.None),
            Triple("Regar las plantas", listOf(TriggerRule(evening)), Recurrence.None),
            Triple("Llamar al dentista", listOf(TriggerRule(nine)), Recurrence.None),
            Triple("Pastillas", listOf(TriggerRule(everyDayAtEight)), Recurrence.After(8, RecurrenceUnit.HOURS)),
            Triple("Beber agua", listOf(TriggerRule(twiceADay)), Recurrence.None),
            // A fence on a rule, which is the other place the words used to run together.
            Triple(
                "Comprar filtros",
                listOf(
                    TriggerRule(
                        threeMinutes,
                        listOf(
                            Condition.TimeWindow(LocalTime.of(18, 0), LocalTime.of(22, 0)),
                            Condition.AtPlace(40.4, -3.7, 150, "Casa", inside = true),
                        ),
                    ),
                ),
                Recurrence.None,
            ),
        )
        rule.setContent {
            RwilcoTheme(darkTheme = true) {
                Surface {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        for ((text, rules, recurrence) in drafts) {
                            ReminderSentence(
                                parts = sentenceParts(text, rules, RuleMatch.ANY, recurrence),
                                today = today,
                                defaultTime = LocalTime.of(9, 0),
                            )
                        }
                        // And the three ways two rules can be joined, which is the other half of
                        // what the sentence is for.
                        for (match in RuleMatch.entries) {
                            ReminderSentence(
                                parts = sentenceParts(
                                    "Llamar a Marta",
                                    listOf(TriggerRule(home), TriggerRule(nine)),
                                    match,
                                    Recurrence.Calendar(Trigger.Repeat(startsOn = today, every = 2, unit = RepeatUnit.WEEK, time = LocalTime.of(9, 0), days = setOf(DayOfWeek.FRIDAY))),
                                ),
                                today = today,
                                defaultTime = LocalTime.of(9, 0),
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        // The one line here worth an assertion as well as an eye: a rate reads as the stay and
        // not as the door, and getting that wrong is a sentence that promises the wrong thing.
        assertTrue(
            "the rate should read as the stay",
            rule.onAllNodesWithText("al llevar 10 min en el gimnasio", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        val bitmap: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        File(dir, "sentences.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
