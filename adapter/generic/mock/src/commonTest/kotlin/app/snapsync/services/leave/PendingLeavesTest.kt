package app.snapsync.services.leave

import app.snapsync.mock.DatabasesMock
import app.snapsync.services.CapturingLogWriter
import app.snapsync.services.backend.LeaveNotifier
import app.snapsync.services.databases.sharedOver
import co.touchlab.kermit.Severity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The leaves the backend has not confirmed (capability `event-lifetime`, "A leave made offline still counts"), over the
 * shared database on in-memory SQLite and a scripted [LeaveNotifier]. Each [leaves] is a process of its own.
 */
class PendingLeavesTest {

    private val databases = DatabasesMock()
    private val refused = mutableSetOf<String>()
    private val sent = mutableListOf<String>()
    private val answers = mutableListOf<Pair<String, Boolean>>()
    private val notifier = LeaveNotifier { eventId, received ->
        sent += eventId
        answers += eventId to received
        if (eventId in refused) Result.failure(IllegalStateException("offline")) else Result.success(Unit)
    }

    private fun leaves() = PendingLeaves(sharedOver(databases.port()), notifier)

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
        // A fresh instance over the same database: what a relaunch sees.
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
    fun `a delivery that raced a newer record of the same event leaves the newer one owed`() = runTest {
        lateinit var leaves: PendingLeaves
        val racing = LeaveNotifier { eventId, received ->
            answers += eventId to received
            if (!received) leaves.record(eventId, received = true)
            Result.success(Unit)
        }
        leaves = PendingLeaves(sharedOver(databases.port()), racing)
        leaves.record("E")
        assertEquals(1, leaves.deliverAll())
        assertEquals(true, leaves.received("E"))
    }

    @Test
    fun `a confirmed leave is recorded delivered and forgotten in silence`() = runTest {
        val log = CapturingLogWriter()
        val leaves = PendingLeaves(sharedOver(databases.port()), notifier, log.logger())
        leaves.leave("E", received = true)
        assertEquals(listOf("E" to true), answers)
        assertEquals(emptySet(), leaves.outstanding())
        assertTrue(log.lines.isEmpty(), "${log.lines}")
    }

    @Test
    fun `an unconfirmed leave stays recorded and says it is retried`() = runTest {
        refused += "E"
        val log = CapturingLogWriter()
        val leaves = PendingLeaves(sharedOver(databases.port()), notifier, log.logger())
        leaves.leave("E", received = false)
        assertEquals(setOf("E"), leaves.outstanding())
        assertTrue(
            log.lines.any { (severity, line) -> severity == Severity.Info && "1 retried on the next wake" in line },
            "${log.lines}",
        )
    }

    @Test
    fun `a locked record answers nothing outstanding and raises nothing`() = runTest {
        leaves().record("E")
        databases.operator.lock()
        val log = CapturingLogWriter()
        val leaves = PendingLeaves(sharedOver(databases.port()), notifier, log.logger())

        assertEquals(emptySet(), leaves.outstanding())
        assertEquals(null, leaves.received("E"))
        assertEquals(0, leaves.deliverAll(), "nothing it could read is sent")
        assertTrue(
            log.lines.any { (severity, line) -> severity == Severity.Warn && "unreadable" in line },
            "${log.lines}",
        )

        databases.operator.lock(false)
        assertEquals(setOf("E"), leaves.outstanding(), "the record was left as it was")
    }

    @Test
    fun `a leave that cannot be recorded is logged and still sent this once`() = runTest {
        databases.operator.lock()
        val log = CapturingLogWriter()
        val leaves = PendingLeaves(sharedOver(databases.port()), notifier, log.logger())

        leaves.leave("E", received = true)

        assertEquals(listOf("E" to true), answers, "the leave reached the backend")
        assertTrue(
            log.lines.any { (severity, line) -> severity == Severity.Warn && "not recorded" in line },
            "${log.lines}",
        )
    }
}
