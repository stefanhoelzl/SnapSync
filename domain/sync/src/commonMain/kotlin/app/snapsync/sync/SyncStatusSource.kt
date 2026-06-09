package app.snapsync.sync

import kotlinx.coroutines.flow.Flow

/**
 * The snapshot seam between sync and presentation: every emission is the whole truth,
 * so consumers depend only on the latest value (level-triggered, never event-folding).
 * Implemented by the real engine later; by the desktop harness's panel until then.
 */
interface SyncStatusSource {
    val status: Flow<SyncStatus>
}
