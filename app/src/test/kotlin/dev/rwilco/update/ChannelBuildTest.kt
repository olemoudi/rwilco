package dev.rwilco.update

import dev.rwilco.BuildConfig
import dev.rwilco.model.UpdateChannel
import dev.rwilco.model.belongsToChannel
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The build at hand has to name a channel.
 *
 * The release workflow checks this too, but by then the tag has been pushed — and a tag that
 * fails leaves a version nobody can install and a tag that has to be deleted before it can be
 * cut again. Here it fails on the machine where the number was typed.
 *
 * It is not a style rule. A phone refuses a downloaded APK whose version name does not carry
 * its channel's suffix ([apkIsInstallable]), so a release named without one would be published,
 * downloaded by every phone on that channel, and thrown away — for ever.
 */
class ChannelBuildTest {

    @Test
    fun `this build names the channel it belongs to`() {
        val named = UpdateChannel.entries.filter { belongsToChannel(BuildConfig.VERSION_NAME, it) }
        assertTrue(named.size == 1) {
            "versionName '${BuildConfig.VERSION_NAME}' has to end in -beta or -alpha; it names $named"
        }
    }
}
