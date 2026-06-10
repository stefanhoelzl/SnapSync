package app.snapsync.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class SyncStatusTest {

    private val someInstant = Instant.fromEpochMilliseconds(1_000_000)

    private fun status(
        pending: Int = 0,
        completed: Int = 0,
        failed: Int = 0,
        active: Boolean = false,
        lastFinishedAt: Instant? = null,
    ) = SyncStatus(pending, completed, failed, active, estimatedRemaining = null, lastFinishedAt = lastFinishedAt)

    @Test
    fun `active pass classifies as IN_PROGRESS`() {
        assertEquals(SyncState.IN_PROGRESS, status(pending = 22, completed = 12, active = true).state)
    }

    @Test
    fun `inactive pass classifies as SUSPENDED`() {
        assertEquals(SyncState.SUSPENDED, status(pending = 22, completed = 12, active = false).state)
    }

    @Test
    fun `clean finished pass classifies as COMPLETE`() {
        assertEquals(SyncState.COMPLETE, status(completed = 34, lastFinishedAt = someInstant).state)
    }

    @Test
    fun `zero-item finished pass classifies as COMPLETE`() {
        assertEquals(SyncState.COMPLETE, status(lastFinishedAt = someInstant).state)
    }

    @Test
    fun `partial-yield pass classifies as INCOMPLETE`() {
        assertEquals(SyncState.INCOMPLETE, status(completed = 31, failed = 3, lastFinishedAt = someInstant).state)
    }

    @Test
    fun `zero-yield pass classifies as FAILED`() {
        assertEquals(SyncState.FAILED, status(failed = 34, lastFinishedAt = someInstant).state)
    }

    @Test
    fun `virgin snapshot classifies as NEVER_SYNCED`() {
        assertEquals(SyncState.NEVER_SYNCED, status().state)
    }

    @Test
    fun `first pass in progress with no finished pass classifies as IN_PROGRESS`() {
        assertEquals(SyncState.IN_PROGRESS, status(pending = 34, active = true).state)
    }
}
