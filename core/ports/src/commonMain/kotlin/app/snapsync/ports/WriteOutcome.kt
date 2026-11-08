package app.snapsync.ports

/** The outcome of a write to a store that may refuse it. [Failed.detail] is diagnostics only. */
sealed interface WriteOutcome {
    data object Ok : WriteOutcome
    data class Failed(val detail: String) : WriteOutcome
}
