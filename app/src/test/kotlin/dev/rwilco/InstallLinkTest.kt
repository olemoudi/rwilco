package dev.rwilco

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The link other people install through.
 *
 * The README carries it twice — as a tappable link and inside the QR code beside it — and the app
 * carries it a third time in [Distribution.APK_URL]. Three copies of one string, two of them
 * hand-edited and one of them a PNG nobody can read by looking at it, is how a download link
 * quietly starts pointing somewhere else.
 *
 * The QR itself cannot be decoded from here without a barcode library, so it is not asserted; it
 * was decoded once, by hand, and it encodes [Distribution.APK_URL] exactly. It is a frozen image
 * of a string that must never change, which is what makes that acceptable — and what this test
 * guards is the half that *is* edited: the words around it drifting from the constant.
 */
class InstallLinkTest {

    private val readme = File("../README.md")

    @Test
    fun `the README hands out the same url the app was built around`() {
        val links = Regex("""\]\((https://github\.com/olemoudi/rwilco/[^)]*rwilco\.apk)\)""")
            .findAll(readme.readText())
            .map { it.groupValues[1] }
            .toList()
        assertEquals(listOf(Distribution.APK_URL), links, "the README's download link and Distribution.APK_URL")
    }

    /**
     * And that it is the *beta* one. `releases/latest` skips pre-releases, and every alpha is
     * published as one (`release.yml`), so this url is the newest beta and nothing else. A tag
     * pinned here, or a channel manifest, would break every install already in the world.
     */
    @Test
    fun `the url everyone installs through goes via releases-latest`() {
        assertTrue(Distribution.APK_URL.contains("/releases/latest/download/")) {
            "${Distribution.APK_URL} no longer resolves through the pre-release-skipping path"
        }
        assertTrue(Distribution.VERSION_JSON_URL.contains("/releases/latest/download/")) {
            "the manifest pre-channel installs poll has to keep resolving to a beta"
        }
    }
}
