package app.snapsync.sync

import kotlinx.coroutines.flow.StateFlow

/**
 * The snapshot seam between sync and presentation: a level-triggered state holder whose
 * current value is always available synchronously, so consumers compute their first state
 * from the real value instead of guessing while waiting for an emission. Every value is
 * the whole truth (never event-folding). Implemented by the real engine later — which must
 * read its bookkeeping store before construction — and by the desktop harness's panel
 * until then.
 */
interface SyncStatusSource {
    val status: StateFlow<SyncStatus>
}
