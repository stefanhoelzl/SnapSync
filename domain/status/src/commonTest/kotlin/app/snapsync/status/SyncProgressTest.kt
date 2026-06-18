package app.snapsync.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The five-row classification decision table (sync-status spec) — each row plus the precedence
 * facts the order encodes.
 */
class SyncProgressTest {

    private val finishedAt = Instant.fromEpochMilliseconds(1_000_000)

    private fun status(
        pending: Int = 0,
        completed: Int = 0,
        failed: Int = 0,
        active: Boolean = true,
        lastFinishedAt: Instant? = null,
    ) = SyncProgress(pending, completed, failed, active, estimatedRemaining = null, lastFinishedAt)

    @Test
    fun `machinery off outranks everything`() {
        assertEquals(SyncState.SUSPENDED, status(active = false).state)
        assertEquals(
            SyncState.SUSPENDED,
            status(pending = 5, completed = 3, failed = 1, active = false, lastFinishedAt = finishedAt).state,
        )
    }

    @Test
    fun `outstanding work classifies as in progress`() {
        assertEquals(SyncState.IN_PROGRESS, status(pending = 1).state)
        // Outstanding work outranks history: a finished past does not soften an active pass.
        assertEquals(SyncState.IN_PROGRESS, status(pending = 1, completed = 9, lastFinishedAt = finishedAt).state)
    }

    @Test
    fun `virgin ledger classifies as never synced`() {
        assertEquals(SyncState.NEVER_SYNCED, status().state)
    }

    @Test
    fun `finished with casualties classifies as incomplete`() {
        assertEquals(
            SyncState.INCOMPLETE,
            status(completed = 31, failed = 3, lastFinishedAt = finishedAt).state,
        )
    }

    @Test
    fun `everything proven classifies as complete`() {
        assertEquals(
            SyncState.COMPLETE,
            status(completed = 34, lastFinishedAt = finishedAt).state,
        )
    }
}
