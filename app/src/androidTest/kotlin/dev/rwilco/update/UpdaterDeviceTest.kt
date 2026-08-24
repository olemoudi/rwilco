package dev.rwilco.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The one step of the updater only a device can answer: the platform parsing what the download
 * turned out to be. A captive portal's login page served with a 200 once caused an unreadable
 * install failure every few hours for as long as the phone stayed on that Wi-Fi.
 */
@RunWith(AndroidJUnit4::class)
class UpdaterDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val updater = Updater(context)

    // A separate file name: the live app's own checks would delete Updater.APK_FILE under us.
    private val file = File(context.cacheDir, "updater-device-test.apk")

    @Test
    fun readsWhatARealApkSaysItIs() {
        File(context.applicationInfo.sourceDir).copyTo(file, overwrite = true)
        val identity = updater.apkIdentity(file)!!
        assertEquals(context.packageName, identity.packageName)
        assertEquals(BuildConfig.VERSION_CODE, identity.versionCode)
        assertEquals(BuildConfig.VERSION_NAME, identity.versionName)
    }

    @Test
    fun anythingThatIsNotAnApkIsRefused() {
        file.writeText("<html><body>Sign in to use this Wi-Fi network</body></html>")
        assertNull(updater.apkIdentity(file))
    }

    @Test
    fun aTruncatedDownloadIsRefused() {
        val bytes = File(context.applicationInfo.sourceDir).readBytes()
        file.writeBytes(bytes.copyOf(minOf(bytes.size, 1024 * 1024)))
        assertNull(updater.apkIdentity(file))
    }

    @Test
    fun anEmptyOrMissingFileIsRefused() {
        file.writeBytes(ByteArray(0))
        assertNull(updater.apkIdentity(file))
        file.delete()
        assertNull(updater.apkIdentity(file))
    }
}
