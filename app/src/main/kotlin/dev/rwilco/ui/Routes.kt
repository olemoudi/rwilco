package dev.rwilco.ui

import kotlinx.serialization.Serializable

/** Type-safe destinations. Sheets, the place picker and the alert preview are state, not routes. */
object Routes {
    @Serializable
    data object Home

    /**
     * The editor, wearing one of three hats. No ids at all is a blank reminder;
     * [reminderId] edits one that exists; [fromPresetId] starts a new reminder with a preset's
     * shape; [editPresetId] edits the preset itself.
     */
    @Serializable
    data class Editor(
        val reminderId: String? = null,
        val fromPresetId: String? = null,
        val editPresetId: String? = null,
        /** A blank form that starts as a preset: the way in from "add a preset button". */
        val newPreset: Boolean = false,
    )

    @Serializable
    data object Done

    @Serializable
    data object Settings

    /** The place watch's own account of itself, behind a button in Settings. */
    @Serializable
    data object WatchLog
}
