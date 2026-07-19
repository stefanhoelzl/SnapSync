package app.snapsync.ports

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The OS-driven tier's pending→re-invocation rule (`requeueWhilePending`, capability
 * `ios-photokit-upload`) — drained from the untested extension root at the migration finale: a
 * drained cycle with in-flight rows answers `PROCESSING` so the OS re-invokes and their
 * completions are recorded promptly; everything else passes through untouched, without even
 * reading the ledger.
 */
class RequeueWhilePendingTest {

    @Test
    fun `a completed cycle with pending rows requeues as PROCESSING and reports the count`() = runTest {
        var reported: Int? = null
        val out = CycleResult.COMPLETED.requeueWhilePending(pending = { 3 }, onRequeue = { reported = it })
        assertEquals(CycleResult.PROCESSING, out)
        assertEquals(3, reported)
    }

    @Test
    fun `a fully drained completed cycle stays COMPLETED so the system rests`() = runTest {
        var requeued = false
        val out = CycleResult.COMPLETED.requeueWhilePending(pending = { 0 }, onRequeue = { requeued = true })
        assertEquals(CycleResult.COMPLETED, out)
        assertFalse(requeued)
    }

    @Test
    fun `non-completed results pass through without consulting the ledger`() = runTest {
        // SKIPPED/FAILED/PROCESSING already carry their re-arm answer; the ledger is not even read.
        var read: Boolean? = null
        for (result in listOf(CycleResult.SKIPPED, CycleResult.FAILED, CycleResult.PROCESSING)) {
            read = null
            val out = result.requeueWhilePending(pending = { read = true; 5 })
            assertEquals(result, out)
            assertNull(read, "$result must not read the ledger")
        }
    }
}
