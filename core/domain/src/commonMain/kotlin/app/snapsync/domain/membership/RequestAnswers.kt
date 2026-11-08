package app.snapsync.domain.membership

import app.snapsync.domain.service.ServiceFault

// What a member's request of the membership came to, as they are told it — each use case's outcome carries its answer.

/**
 * What a confirmed join came to, as the screen tells it: each refusal the member can act on differently is its own —
 * an event that is over is not a failure a retry heals.
 */
sealed interface JoinCommit {
    /** Joined — or joined already. */
    data object Committed : JoinCommit

    /** The event admits nobody any more. */
    data object EventOver : JoinCommit

    /** Not joined; a retry may. [fault] is how the service failed the join — `null` when it was not the cause. */
    data class Failed(val fault: ServiceFault? = null) : JoinCommit

    /** The server accepted no credential of this device's. */
    data object Unverified : JoinCommit

    /** The invite's key does not open the event. */
    data object WrongLink : JoinCommit
}

/** What a settings change came to. */
enum class SettingsResult {
    Saved,

    /** Nothing changed: it could not be saved. */
    SaveFailed,

    /** Nothing changed: the settings were for a membership no longer held. */
    NotCurrent,

    /** Nothing changed: the range breaks the share-range rule — no screen can produce one. */
    Refused,
}

/** What a rename came to. */
sealed interface RenameResult {
    data object Renamed : RenameResult

    /** The name is none the server takes. */
    data object InvalidName : RenameResult

    /** Nothing changed. [fault] is how the service failed — `null` for an answer from a working one. */
    data class Failed(val fault: ServiceFault? = null) : RenameResult

    /** The server answered that the event's photos were deleted: the event is over. */
    data object EventOver : RenameResult
}

/** What a reopened invite of the joined event did to its lost key. */
enum class RestoreAnswer {
    /** The key was lost, and the invite gave it back. */
    Restored,

    /** Nothing was lost, or the invite carried another key: nothing changed. */
    NothingChanged,

    /** The key could not be kept. */
    Failed,
}
