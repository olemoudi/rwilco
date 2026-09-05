package dev.rwilco

import androidx.test.runner.AndroidJUnitRunner
import dev.rwilco.ui.Disclaimer

/**
 * The suite's runner, which exists to answer one dialog.
 *
 * The launch notice sits over Home until somebody presses OK, and every screen this suite drives
 * is behind it — while none of these tests is about it. Answered once for the whole run, here,
 * rather than in twenty-two `@Before` methods and in every test written after this one.
 * `DisclaimerTest` unanswers it for itself, which is what makes this honest rather than a
 * production branch that knows it is being tested.
 */
class RwilcoTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        Disclaimer.readThisRun = true
        super.onStart()
    }
}
