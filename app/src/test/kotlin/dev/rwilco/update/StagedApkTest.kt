package dev.rwilco.update

import android.content.pm.PackageInstaller
import dev.rwilco.model.UpdateChannel
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StagedApkTest {

    private val ours = "dev.rwilco"

    private fun installable(
        pkg: String? = ours,
        code: Int = 12,
        name: String = "1.2.0-beta",
        installed: Int = 11,
        channel: UpdateChannel = UpdateChannel.BETA,
    ) = apkIsInstallable(pkg, code, name, ours, installed, channel)

    @Test
    fun `a newer build of this app on this channel is installable`() {
        assertTrue(installable())
        assertTrue(installable(code = 15), "the release moved on mid-download: it is ours and it is newer")
        assertTrue(installable(name = "1.2.0-alpha", channel = UpdateChannel.ALPHA))
    }

    @Test
    fun `anything that did not parse, is not us, or is not newer is refused`() {
        assertFalse(installable(pkg = null))
        assertFalse(installable(pkg = "com.example.other"))
        assertFalse(installable(code = 11))
        assertFalse(installable(code = 10))
    }

    /**
     * The check that makes a channel a channel. A manifest is a file on the internet and a
     * copy-paste between the two would otherwise move a phone onto a lineage nobody chose; the
     * suffix is inside the APK, and the APK cannot be talked out of it.
     */
    @Test
    fun `a build of the other channel is refused, and so is one that names neither`() {
        assertFalse(installable(name = "1.2.0-alpha"), "an alpha build on a phone following beta")
        assertFalse(installable(name = "1.2.0-beta", channel = UpdateChannel.ALPHA))
        assertFalse(installable(name = "1.2.0"), "a build from before the channels belongs to neither")
        assertFalse(installable(name = ""))
    }

    @Test
    fun `the apk survives a cancelled or blocked install and nothing else`() {
        assertTrue(keepsApkAfterFailure(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertTrue(keepsApkAfterFailure(PackageInstaller.STATUS_FAILURE_BLOCKED))
        for (status in listOf(
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_STORAGE,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
        )) {
            assertFalse(keepsApkAfterFailure(status), "status $status")
        }
    }
}
