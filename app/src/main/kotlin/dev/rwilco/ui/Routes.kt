package dev.rwilco.ui

import kotlinx.serialization.Serializable

/** Type-safe destinations. Sheets, the place picker and the alert preview are state, not routes. */
object Routes {
    @Serializable
    data object Home

    /** Null id = a new reminder. */
    @Serializable
    data class Editor(val reminderId: String? = null)

    @Serializable
    data object Done

    @Serializable
    data object Settings
}
