package app.snapsync.services.leave

import app.snapsync.mock.inMemoryFiles
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.ports.Files
import app.snapsync.services.backend.LeaveNotifier
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A pending-leave record that exists but cannot be READ right now — while a write still lands — is never written over:
 * that would drop every leave it holds, and each one's event would wait for its clock.
 *
 * The in-memory mock's `denied` lever refuses reads AND writes together (what data protection does), so it cannot
 * reach this state; [ReadFailing] fails only the reads, as a transient `Failed` (an I/O error) would.
 */
class PendingLeavesReadFailureTest {

    private class ReadFailing(private val inner: Files) : Files by inner {
        var failReads = false
        override fun read(area: FileArea, path: String): FileResult<ByteArray> =
            if (failReads) FileResult.Failed("EIO: transient read failure") else inner.read(area, path)
    }

    private val shared = mutableMapOf<String, ByteArray>()
    private val files = ReadFailing(inMemoryFiles(shared = shared))
    private val offline = LeaveNotifier { _, _ -> Result.failure(IllegalStateException("offline")) }

    @Test
    fun `recording a leave while the record is unreadable keeps the leaves already recorded`() = runTest {
        val leaves = PendingLeaves(files, offline)
        leaves.record("A")
        leaves.record("B")

        files.failReads = true
        leaves.record("C")
        files.failReads = false
        leaves.record("D")

        assertEquals(setOf("A", "B", "C", "D"), PendingLeaves(files, offline).outstanding())
    }

    @Test
    fun `a leave recorded while the record is unreadable is still sent`() = runTest {
        val sent = mutableListOf<String>()
        val leaves = PendingLeaves(files, { eventId, _ -> sent += eventId; Result.success(Unit) })
        files.failReads = true

        leaves.record("C")
        assertEquals(0, leaves.deliverAll())

        assertEquals(listOf("C"), sent)
        files.failReads = false
        assertEquals(emptySet(), leaves.outstanding())
    }

    @Test
    fun `a delivery whose second read fails keeps the leaves the backend did not confirm`() = runTest {
        // The backend confirms nothing; between deliverAll's two reads the record becomes unreadable.
        val notifier = LeaveNotifier { _, _ -> files.failReads = true; Result.failure(IllegalStateException("offline")) }
        val leaves = PendingLeaves(files, notifier)
        leaves.record("A")
        leaves.record("B")

        assertEquals(2, leaves.deliverAll())
        files.failReads = false

        assertEquals(setOf("A", "B"), PendingLeaves(files, offline).outstanding())
    }
}
