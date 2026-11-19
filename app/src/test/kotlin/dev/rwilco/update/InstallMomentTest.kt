package dev.rwilco.update

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** When a silent install may replace the running app (0.215.0). */
class InstallMomentTest {

    @Test
    fun `in the background it installs`() {
        assertTrue(installMayStartNow(manual = false, appOnScreen = false, openedScreenOnTop = false))
    }

    @Test
    fun `on screen, or under a screen it opened, it waits for the app to be left`() {
        assertFalse(installMayStartNow(manual = false, appOnScreen = true, openedScreenOnTop = false))
        assertFalse(installMayStartNow(manual = false, appOnScreen = false, openedScreenOnTop = true))
    }

    @Test
    fun `asked for by hand it installs whatever is on screen`() {
        assertTrue(installMayStartNow(manual = true, appOnScreen = true, openedScreenOnTop = true))
    }

    @Test
    fun `the manifest asks to update without asking`() {
        // Without it the platform ignores USER_ACTION_NOT_REQUIRED and every update prompts,
        // which is what it did for two hundred releases.
        val manifest = sequenceOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }
        assertTrue("android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION" in manifest.readText())
    }
}
