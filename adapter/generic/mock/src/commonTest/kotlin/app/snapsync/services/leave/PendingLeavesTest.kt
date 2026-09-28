package app.snapsync.services.leave

import app.snapsync.mock.inMemoryFiles
import app.snapsync.services.backend.LeaveNotifier
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The leaves the backend has not confirmed (capability `event-lifetime`, "A leave made offline still counts"), over the
 * honest in-memory [app.snapsync.ports.Files] and a scripted [LeaveNotifier].
 */
class PendingLeavesTest {

    private val shared = mutableMapOf<String, ByteArray>()
    private val refused = mutableSetOf<String>()
    private val sent = mutableListOf<String>()
    private val notifier = LeaveNotifier { eventId ->
        sent += eventId
        if (eventId in refused) Result.failure(IllegalStateException("offline")) else Result.success(Unit)
    }

    private fun leaves() = PendingLeaves(inMemoryFiles(shared = shared), notifier)

    @Test
    fun `a confirmed leave is forgotten`() = runTest {
        val leaves = leaves()
        leaves.record("E")
        assertEquals(0, leaves.deliverAll())
        assertEquals(emptySet(), leaves.outstanding())
    }

    @Test
    fun `an unconfirmed leave survives the process and is delivered by a later wake`() = runTest {
        refused += "E"
        leaves().apply {
            record("E")
            assertEquals(1, deliverAll())
        }
        refused.clear()
        // A fresh instance over the same shared area: what a relaunch sees.
        val later = leaves()
        assertEquals(setOf("E"), later.outstanding())
        assertEquals(0, later.deliverAll())
        assertEquals(listOf("E", "E"), sent)
    }

    @Test
    fun `recording twice sends once`() = runTest {
        val leaves = leaves()
        leaves.record("E")
        leaves.record("E")
        leaves.deliverAll()
        assertEquals(listOf("E"), sent)
    }

    @Test
    fun `nothing recorded sends nothing`() = runTest {
        assertEquals(0, leaves().deliverAll())
        assertEquals(emptyList(), sent)
    }
}
