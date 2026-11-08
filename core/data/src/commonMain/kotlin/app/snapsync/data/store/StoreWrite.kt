package app.snapsync.data.store

/** What a write to a store came to — the store's own answer, so a use case reads it without the port's vocabulary. */
sealed interface StoreWrite {
    data object Written : StoreWrite

    /** Nothing was written. [detail] is diagnostics only. */
    data class Failed(val detail: String) : StoreWrite
}
