package app.snapsync.status

import kotlinx.coroutines.flow.StateFlow

/**
 * The snapshot seam between status and presentation: a level-triggered state holder whose
 * current value is always available synchronously. That value is always a real [SyncStatus]
 * — [SyncStatus.Ready] with the whole truth, or [SyncStatus.Loading] (persisted state not
 * yet read) — never a placeholder, guess, or default. Every [SyncStatus.Ready] value is the
 * whole truth (never event-folding). The seam does NOT promise a synchronously-available
 * [SyncProgress]: a source backed by persisted state reports [SyncStatus.Loading] at construction.
 * Implemented by [LedgerSyncStatusSource] (seeds Loading) and by the desktop harness's panel
 * (knows its truth synchronously, so seeds Ready).
 */
interface SyncStatusSource {
    val status: StateFlow<SyncStatus>
}
