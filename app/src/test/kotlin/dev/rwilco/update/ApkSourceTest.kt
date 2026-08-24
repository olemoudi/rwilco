package dev.rwilco.update

import dev.rwilco.Distribution
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApkSourceTest {

    @Test
    fun `the release assets are trusted`() {
        assertTrue(Updater.trustedApkUrl(Distribution.APK_URL))
        assertTrue(Updater.trustedApkUrl("https://github.com/olemoudi/rwilco/releases/download/v0.2.0/rwilco.apk"))
        assertTrue(Updater.trustedApkUrl("https://GitHub.COM/olemoudi/rwilco/releases/latest/download/rwilco.apk"), "hosts match case-insensitively, as the network does")
    }

    @Test
    fun `anything off the release host or repository is refused`() {
        assertFalse(Updater.trustedApkUrl("http://github.com/olemoudi/rwilco/releases/latest/download/rwilco.apk"))
        assertFalse(Updater.trustedApkUrl("https://example.com/olemoudi/rwilco/rwilco.apk"))
        assertFalse(Updater.trustedApkUrl("https://github.com.evil.example/olemoudi/rwilco/rwilco.apk"))
        assertFalse(Updater.trustedApkUrl("https://github.com/someone/rwilco/releases/latest/download/rwilco.apk"))
        assertFalse(Updater.trustedApkUrl("https://github.com/olemoudi/rwilco-evil/releases/latest/download/rwilco.apk"))
        assertFalse(Updater.trustedApkUrl("https://github.com/olemoudi-evil/rwilco/releases/latest/download/rwilco.apk"))
        assertFalse(Updater.trustedApkUrl("ftp://github.com/olemoudi/rwilco/rwilco.apk"))
        assertFalse(Updater.trustedApkUrl(""))
        assertFalse(Updater.trustedApkUrl("not a url"))
    }

    @Test
    fun `dot segments cannot walk out of the repository`() {
        // OkHttp normalises this to /someone/evil.apk before the request is made.
        assertFalse(Updater.trustedApkUrl("https://github.com/olemoudi/rwilco/../../someone/evil.apk"))
    }
}
