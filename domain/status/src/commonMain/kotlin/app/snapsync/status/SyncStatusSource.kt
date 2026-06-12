package app.snapsync.status

import kotlinx.coroutines.flow.StateFlow

/**
 * The snapshot seam between status and presentation: a level-triggered state holder whose
 * current value is always available synchronously, so consumers compute their first state
 * from the real value instead of guessing while waiting for an emission. Every value is
 * the whole truth (never event-folding). Implemented by [LedgerSyncStatusSource] — whose
 * factory reads the ledger before construction — and by the desktop harness's panel.
 */
interface SyncStatusSource {
    val status: StateFlow<SyncStatus>
}
