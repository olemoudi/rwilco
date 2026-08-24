package dev.rwilco.update

import android.content.pm.PackageInstaller
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StagedApkTest {

    private val ours = "dev.rwilco"

    @Test
    fun `a newer build of this app is installable`() {
        assertTrue(apkIsInstallable(ours, 12, ours, 11))
        assertTrue(apkIsInstallable(ours, 15, ours, 11), "the release moved on mid-download: it is ours and it is newer")
    }

    @Test
    fun `anything that did not parse, is not us, or is not newer is refused`() {
        assertFalse(apkIsInstallable(null, 12, ours, 11))
        assertFalse(apkIsInstallable("com.example.other", 12, ours, 11))
        assertFalse(apkIsInstallable(ours, 11, ours, 11))
        assertFalse(apkIsInstallable(ours, 10, ours, 11))
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
