package app.snapsync.services.upload

import app.snapsync.mock.inMemoryDatabases
import app.snapsync.model.AssetId
import app.snapsync.model.CycleResult
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.services.databases.SharedDatabase
import app.snapsync.services.ledger.LedgerService
import co.touchlab.kermit.Logger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The OS-driven tier's pending→re-invocation rule (`requeueWhilePending`, capability `background-upload`): a drained
 * cycle with rows still pending answers `PROCESSING` so the OS re-invokes and their completions are recorded promptly;
 * everything else passes through untouched.
 */
class RequeueWhilePendingTest {

    private val ledger = LedgerService(SharedDatabase(inMemoryDatabases(), legacy = null)) { "E-1" }
    private val log = Logger.withTag("test")

    private suspend fun pending(vararg keys: String) = keys.forEach {
        ledger.recordUnlessSettled(LedgerEntry("$it.heic", AssetId(it), LedgerState.DISCOVERED))
    }

    @Test
    fun `a completed cycle with pending rows requeues as PROCESSING`() = runTest {
        pending("A", "B")
        assertEquals(CycleResult.PROCESSING, CycleResult.COMPLETED.requeueWhilePending(ledger, log))
    }

    @Test
    fun `a fully drained completed cycle stays COMPLETED so the system rests`() = runTest {
        assertEquals(CycleResult.COMPLETED, CycleResult.COMPLETED.requeueWhilePending(ledger, log))
    }

    @Test
    fun `non-completed results pass through - whatever is pending`() = runTest {
        pending("A")
        for (result in listOf(CycleResult.SKIPPED, CycleResult.FAILED, CycleResult.PROCESSING)) {
            assertEquals(result, result.requeueWhilePending(ledger, log))
        }
    }
}
