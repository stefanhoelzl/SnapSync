package app.snapsync.model

/**
 * What a reconfigure did (capability `reconfigure-membership`) — named, so the settings surface can tell a save
 * that landed from one that did not.
 */
enum class ReconfigureOutcome {
    /** The new settings were saved; the arms were re-driven best-effort. */
    Saved,

    /** The config save failed: nothing after it ran, and the persisted settings are unchanged. */
    SaveFailed,

    /** The surface was opened for a membership that is no longer current; nothing was changed. */
    NotCurrent,
}
