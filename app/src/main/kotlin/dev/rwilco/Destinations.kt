package dev.rwilco

/**
 * Where an intent wants the app to land, worked out from the intent's parts alone so it can be
 * tested without one: a notification's own extra, the launcher shortcut, or a line of text
 * shared from another app — which becomes a new reminder with that line as its words.
 */
object Destinations {
    /** The launcher shortcut's action: a blank reminder, straight into the form. */
    const val ACTION_NEW = "dev.rwilco.action.NEW"
    /** A pinned preset's own launcher shortcut: the reminder written, or its words asked for. */
    const val ACTION_PRESET = "dev.rwilco.action.PRESET"
    const val NEW = "new"
    private const val NEW_TEXT_PREFIX = "new:"
    private const val PRESET_PREFIX = "preset:"
    private const val ACTION_SEND = "android.intent.action.SEND"

    fun of(action: String?, type: String?, extraDestination: String?, sharedText: String?, presetId: String? = null): String? = when {
        extraDestination != null -> extraDestination
        action == ACTION_NEW -> NEW
        action == ACTION_PRESET && !presetId.isNullOrBlank() -> PRESET_PREFIX + presetId
        action == ACTION_SEND && type?.startsWith("text/") == true && !sharedText.isNullOrBlank() -> NEW_TEXT_PREFIX + sharedText.trim()
        else -> null
    }

    /** The preset a launcher shortcut asks Home to write, or null for any other landing. */
    fun presetIdIn(destination: String?): String? =
        destination?.takeIf { it.startsWith(PRESET_PREFIX) }?.removePrefix(PRESET_PREFIX)

    /** The words a shared line asks the new reminder to start with, or null for any other landing. */
    fun sharedTextIn(destination: String?): String? =
        destination?.takeIf { it.startsWith(NEW_TEXT_PREFIX) }?.removePrefix(NEW_TEXT_PREFIX)
}
