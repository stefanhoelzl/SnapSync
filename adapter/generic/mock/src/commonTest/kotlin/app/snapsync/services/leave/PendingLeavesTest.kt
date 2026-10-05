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
    private val answers = mutableListOf<Pair<String, Boolean>>()
    private val notifier = LeaveNotifier { eventId, received ->
        sent += eventId
        answers += eventId to received
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

    @Test
    fun `a leave says whether it received everything and the latest record of it wins`() = runTest {
        val leaves = leaves()
        leaves.record("E")
        leaves.record("E", received = true)
        leaves.record("F")
        leaves.deliverAll()
        assertEquals(listOf("E" to true, "F" to false), answers)
    }

    @Test
    fun `the received answer survives the process`() = runTest {
        refused += "E"
        leaves().apply {
            record("E", received = true)
            deliverAll()
        }
        refused.clear()
        assertEquals(true, leaves().received("E"))
        leaves().deliverAll()
        assertEquals(listOf("E" to true, "E" to true), answers)
    }

    @Test
    fun `a record written before the answer existed reads as not received`() = runTest {
        shared["membership/pending-leaves.txt"] = "E\nF received".encodeToByteArray()
        leaves().deliverAll()
        assertEquals(listOf("E" to false, "F" to true), answers)
    }

    @Test
    fun `a delivery that raced a newer record of the same event leaves the newer one owed`() = runTest {
        lateinit var leaves: PendingLeaves
        val racing = LeaveNotifier { eventId, received ->
            answers += eventId to received
            if (!received) leaves.record(eventId, received = true)
            Result.success(Unit)
        }
        leaves = PendingLeaves(inMemoryFiles(shared = shared), racing)
        leaves.record("E")
        assertEquals(1, leaves.deliverAll())
        assertEquals(true, leaves.received("E"))
    }
}
