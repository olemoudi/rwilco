package dev.rwilco

import dev.rwilco.model.UpdateChannel

/**
 * Where a release lives.
 *
 * These names are baked into install links and into every copy of the app already on a phone, so
 * they never change: the stage is user-facing text and belongs in the version label, not in a
 * filename that has to keep resolving forever.
 */
object Distribution {

    /**
     * Always the newest *beta* release's APK. What a sideload link points at.
     *
     * Still `latest` and still right now that alpha builds exist, because those are published as
     * GitHub pre-releases and `latest` skips them.
     */
    const val APK_URL = "https://github.com/olemoudi/rwilco/releases/latest/download/rwilco.apk"

    /**
     * The manifest an install published before the channels existed still polls.
     *
     * Kept resolving on purpose. Those copies read whatever `latest` names, which is the beta
     * channel, so they land on it by themselves at their next check with nothing to do — and the
     * build they land on is the first one that knows what a channel is.
     */
    const val VERSION_JSON_URL = "https://github.com/olemoudi/rwilco/releases/latest/download/version.json"

    /**
     * What each channel currently serves, as a file committed to the repository.
     *
     * Not a release asset, because "the newest release on GitHub" and "what this channel should
     * serve" stopped being the same question the moment alpha builds started shipping between
     * betas. A committed manifest is explicit, reviewable and revertible, and the APK it names is
     * pinned to a tag rather than to a `latest` that can move between reading the manifest and
     * fetching the file it named.
     */
    fun manifestUrl(channel: UpdateChannel): String = when (channel) {
        UpdateChannel.BETA -> "$MANIFEST_BASE/beta.json"
        UpdateChannel.ALPHA -> "$MANIFEST_BASE/alpha.json"
    }

    private const val MANIFEST_BASE =
        "https://raw.githubusercontent.com/olemoudi/rwilco/main/channels"
}
