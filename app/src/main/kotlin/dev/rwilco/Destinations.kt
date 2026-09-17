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

    fun of(
        action: String?,
        type: String?,
        extraDestination: String?,
        sharedText: String?,
        presetId: String? = null,
        /** `EXTRA_SUBJECT`: where a browser puts the page's title, beside the link and not in it. */
        subject: String? = null,
    ): String? = when {
        extraDestination != null -> extraDestination
        action == ACTION_NEW -> NEW
        action == ACTION_PRESET && !presetId.isNullOrBlank() -> PRESET_PREFIX + presetId
        action == ACTION_SEND && type?.startsWith("text/") == true && !sharedText.isNullOrBlank() -> NEW_TEXT_PREFIX + sharedWords(sharedText, subject)
        else -> null
    }

    /** As long as a reminder's words may be (`MAX_TEXT_LENGTH`); the editor cuts there anyway. */
    const val MAX_SHARED_LENGTH = 500
    private const val TITLE_JOIN = " — "
    private val LONE_LINK = Regex("^(?:https?://|www\\.)\\S+$", RegexOption.IGNORE_CASE)

    /**
     * The words a share becomes. **A link shared from a browser keeps the page's title** (0.135.0):
     * the link travels alone in the text and the title in the subject, which was never read, so
     * the reminder was a bare URL — and a bare URL says nothing on a card three days later.
     * Only when the text is a link and nothing else: words of somebody's own *are* the reminder,
     * and a subject is somebody else's heading for them. The title is what gives way to the cap,
     * never the link, which is the half that still works when cut short in no place at all.
     */
    private fun sharedWords(text: String, subject: String?): String {
        val link = text.trim()
        val title = subject?.trim().orEmpty()
        if (title.isEmpty() || title.equals(link, ignoreCase = true) || !LONE_LINK.matches(link)) return link
        val room = MAX_SHARED_LENGTH - link.length - TITLE_JOIN.length
        if (room < 1) return link
        return title.take(room).trimEnd() + TITLE_JOIN + link
    }

    /** The preset a launcher shortcut asks Home to write, or null for any other landing. */
    fun presetIdIn(destination: String?): String? =
        destination?.takeIf { it.startsWith(PRESET_PREFIX) }?.removePrefix(PRESET_PREFIX)

    /** The words a shared line asks the new reminder to start with, or null for any other landing. */
    fun sharedTextIn(destination: String?): String? =
        destination?.takeIf { it.startsWith(NEW_TEXT_PREFIX) }?.removePrefix(NEW_TEXT_PREFIX)
}
