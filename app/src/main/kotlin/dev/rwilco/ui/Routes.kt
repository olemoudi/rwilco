package dev.rwilco.ui

import kotlinx.serialization.Serializable

/** Type-safe destinations. Sheets, the place picker and the alert preview are state, not routes. */
object Routes {
    @Serializable
    data object Home

    /**
     * The editor, wearing one of four hats. No ids at all is a blank reminder;
     * [reminderId] edits one that exists; [fromPresetId] starts a new reminder with a preset's
     * shape; [cloneOfId] starts one with another reminder's shape and no words;
     * [editPresetId] edits the preset itself.
     */
    @Serializable
    data class Editor(
        val reminderId: String? = null,
        val fromPresetId: String? = null,
        /** A new reminder shaped like this one, waiting for its own words. */
        val cloneOfId: String? = null,
        val editPresetId: String? = null,
        /** A blank form that starts as a preset: the way in from "add a preset button". */
        val newPreset: Boolean = false,
        /** Words a new reminder starts with: a line shared from another app. */
        val sharedText: String? = null,
        /** A blank form that starts as a routine (see `Routines.kt`): the way in from the routines screen. */
        val routine: Boolean = false,
    )

    @Serializable
    data object Done

    /**
     * The routines — what counts time since the last time it was done. See `Routines.kt`.
     *
     * [focus] is the routine to bring into view: Home's overdue rows each name one, and a row
     * tapped there has to land on the routine it names rather than at the top of a list.
     */
    @Serializable
    data class Routines(val focus: String? = null)

    @Serializable
    data object Settings

    /** The place watch's own account of itself, behind a button in Settings. */
    @Serializable
    data object WatchLog

    /** The encrypted backup: off by default, set up and managed behind Settings. */
    @Serializable
    data object Backup

    /** What the app knows about itself, for when something did not happen. */
    @Serializable
    data object Diagnostics
}
