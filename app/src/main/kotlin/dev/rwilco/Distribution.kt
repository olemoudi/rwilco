package dev.rwilco

/** Distribution constants. Keep the asset names stable so existing installs keep updating. */
object Distribution {
    const val APK_URL = "https://github.com/olemoudi/rwilco/releases/latest/download/rwilco.apk"

    /** Small JSON published by CI describing the latest release (version code + apk url). */
    const val VERSION_JSON_URL = "https://github.com/olemoudi/rwilco/releases/latest/download/version.json"
}
