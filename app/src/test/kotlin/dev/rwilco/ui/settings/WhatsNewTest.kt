package dev.rwilco.ui.settings

import dev.rwilco.BuildConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WhatsNewTest {

    private val releases = listOf(
        Release(5, "0.5.0", 0),
        Release(3, "0.3.0", 0),
        Release(2, "0.2.0", 0),
    )

    @Test
    fun `only the releases between last seen and now, newest first`() {
        assertEquals(listOf(5, 3), entriesFor(lastSeenVersionCode = 2, currentVersionCode = 5, releases).map { it.versionCode })
        assertEquals(listOf(3), entriesFor(lastSeenVersionCode = 2, currentVersionCode = 4, releases).map { it.versionCode })
    }

    @Test
    fun `the build at hand is the first release on the list`() {
        // The list went silent at 0.20.0 for forty-five releases and nobody saw it, because a
        // sheet with nothing to say says nothing. A version is not cut without its line.
        assertEquals(BuildConfig.VERSION_CODE, RELEASES.first().versionCode, "add this build's notes to RELEASES")
        assertEquals(RELEASES.map { it.versionCode }.sortedDescending(), RELEASES.map { it.versionCode }, "newest first")
        assertEquals(RELEASES.size, RELEASES.distinctBy { it.versionCode }.size, "one entry per build")
    }

    @Test
    fun `a fresh install and an up-to-date phone are shown nothing`() {
        assertTrue(entriesFor(lastSeenVersionCode = 0, currentVersionCode = 5, releases).isEmpty())
        assertTrue(entriesFor(lastSeenVersionCode = 5, currentVersionCode = 5, releases).isEmpty())
    }
}
