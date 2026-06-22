package app.snapsync.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The three-row classification decision table (sync-status spec): driven by the live total `N`
 * versus the clamped synced count `n`; ledger `pending` is ignored.
 */
class SyncProgressTest {

    private val finishedAt = Instant.fromEpochMilliseconds(1_000_000)

    private fun status(
        pending: Int = 0,
        completed: Int = 0,
        total: Int = 0,
        failed: Int = 0,
        active: Boolean = true,
        lastFinishedAt: Instant? = null,
    ) = SyncProgress(pending, completed, total, failed, active, estimatedRemaining = null, lastFinishedAt)

    @Test
    fun `no in-scope photos classifies as nothing to sync`() {
        assertEquals(SyncState.NOTHING_TO_SYNC, status(total = 0).state)
        // Regardless of completed/pending history.
        assertEquals(
            SyncState.NOTHING_TO_SYNC,
            status(pending = 0, completed = 9, total = 0, lastFinishedAt = finishedAt).state,
        )
    }

    @Test
    fun `fewer synced than present classifies as in progress`() {
        val s = status(completed = 12, total = 47)
        assertEquals(SyncState.IN_PROGRESS, s.state)
        assertEquals(12, s.synced)
    }

    @Test
    fun `virgin ledger with photos classifies as in progress`() {
        val s = status(completed = 0, total = 5, lastFinishedAt = null)
        assertEquals(SyncState.IN_PROGRESS, s.state)
        assertEquals(0, s.synced)
    }

    @Test
    fun `pending is ignored - a deleted-but-unpruned photo does not pin in progress`() {
        // total reflects the live library (4 photos), completed counts all 4; a stale pending
        // ledger row for a deleted photo must NOT keep this IN_PROGRESS.
        assertEquals(SyncState.COMPLETE, status(pending = 1, completed = 4, total = 4).state)
    }

    @Test
    fun `all present photos synced classifies as complete`() {
        assertEquals(SyncState.COMPLETE, status(completed = 47, total = 47, lastFinishedAt = finishedAt).state)
    }

    @Test
    fun `completed overshooting total clamps and classifies as complete`() {
        val s = status(completed = 6, total = 5)
        assertEquals(SyncState.COMPLETE, s.state)
        assertEquals(5, s.synced)
    }
}
