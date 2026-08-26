package dev.rwilco.diag

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.RwilcoApplication
import dev.rwilco.debug.DemoData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The report, built against a real phone: the permissions, the clock and the stores are the
 * ones only a device has. It leaves the whole thing in the app's files so it can be read as
 * somebody pasting it would see it — the point of the report is that a person can hand it over
 * and it is enough.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    @Test
    fun theReportSaysEnoughToDebugWith() = runBlocking {
        DemoData.seed(app.repository, app.clock)
        Diag.note("fire", "r=deadbeef dropped: nothing armed (armed=null now=test)")
        Diag.note("arm", "armed=7 missed=0 exact=y")
        // The note is written on the app's own scope; give it the moment it needs to land.
        Thread.sleep(500)

        val report = app.collectDiagnostics().report()

        for (section in listOf("== rwilco diagnostics ==", "-- what the phone allows --", "-- reminders:", "-- log:", "== end ==")) {
            assertTrue("missing $section", report.contains(section))
        }
        assertTrue("the log did not reach the report", report.contains("dropped: nothing armed"))
        File(context.filesDir, "screenshots").apply { mkdirs() }.resolve("diagnostics.txt").writeText(report)
    }
}
