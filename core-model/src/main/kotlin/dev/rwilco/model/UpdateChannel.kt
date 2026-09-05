package dev.rwilco.model

import kotlinx.serialization.Serializable

/**
 * Which stream of builds this phone follows.
 *
 * Two, and neither is the absence of the other: [BETA] is what the app is for everybody, [ALPHA]
 * is what it is being written into. The names are the ones the tags carry (`v1.2.3-beta`,
 * `v1.2.3-alpha`), because the one place this has to be unambiguous is between a tag, a manifest
 * and the APK a phone has just downloaded — three files written at three different times.
 *
 * In `core-model` rather than next to the updater because [AppSettings] is serialized here and a
 * setting cannot name a type the settings blob cannot see.
 */
@Serializable
enum class UpdateChannel {
    /** The tested one, and the default: what a phone gets by being a phone. */
    BETA,

    /** Builds as they are written. Nobody else has run them. */
    ALPHA,
}

/** The suffix a build on this channel carries in its version name, and in its tag. */
val UpdateChannel.suffix: String get() = when (this) {
    UpdateChannel.BETA -> "-beta"
    UpdateChannel.ALPHA -> "-alpha"
}

/**
 * Whether [versionName] is a build of [channel].
 *
 * Asked of the *downloaded APK*, never of the manifest that named it. A manifest is a file on
 * the internet and one edited wrongly — a copy-paste between the two channels, a promotion that
 * pointed beta at a test build — would otherwise move somebody onto a lineage they never chose,
 * silently, which is the single failure having channels at all exists to prevent. The suffix is
 * inside the APK and the APK cannot be talked out of it.
 *
 * A version name with no suffix predates the channels and belongs to neither, so it is refused
 * by both rather than accepted by both.
 */
fun belongsToChannel(versionName: String, channel: UpdateChannel): Boolean =
    versionName.endsWith(channel.suffix)

/** What choosing a channel will actually do, which is not the same in both directions. */
sealed interface ChannelSwitch {

    /** There is something newer on the chosen channel; the next check installs it. */
    data class Immediate(val versionName: String) : ChannelSwitch

    /**
     * The chosen channel is behind what is installed, so nothing can happen yet.
     *
     * Android refuses to install a lower version code over a higher one and there is no way to
     * ask it nicely — the only route is uninstalling, which takes every reminder with it. So
     * this is not a failure to retry, it is a wait to explain: the phone rejoins the channel at
     * its next release, and anybody in a hurry saves a vault and installs the other APK by hand.
     */
    data class WaitsForNextRelease(val channelVersionName: String) : ChannelSwitch

    /**
     * The chosen channel serves exactly the build that is already running.
     *
     * Distinct from [WaitsForNextRelease] on purpose: the screen paints that one in red, and
     * lumping the two together would tell every up-to-date phone on beta — which is nearly all
     * of them — that it is stranded ahead of its own channel. Being current is not a wait.
     */
    data object AlreadyOnIt : ChannelSwitch

    /** Nothing is known about the chosen channel yet: no manifest has been read. */
    data object Unknown : ChannelSwitch
}

/**
 * What following a channel means for a phone on [installedVersionCode], given what that channel
 * currently offers. [channelVersionCode] of 0 means no manifest has been read.
 */
fun channelSwitch(
    installedVersionCode: Int,
    channelVersionCode: Int,
    channelVersionName: String,
): ChannelSwitch = when {
    channelVersionCode <= 0 -> ChannelSwitch.Unknown
    channelVersionCode > installedVersionCode -> ChannelSwitch.Immediate(channelVersionName)
    channelVersionCode == installedVersionCode -> ChannelSwitch.AlreadyOnIt
    else -> ChannelSwitch.WaitsForNextRelease(channelVersionName)
}
