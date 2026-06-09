package app.snapsync.sync

/**
 * Snapshot of the sync engine's current truth (design.md §2.3). Fields are demand-driven:
 * they exist only once a consumer renders them and a slice defines their semantics.
 */
data class SyncStatus(
    val pending: Int,
    val completed: Int,
)
