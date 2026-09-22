package app.snapsync.feature.upload

import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.TerminalOutcome
import app.snapsync.ports.DeviceFilesSource
import app.snapsync.ports.DeviceListingShapeException
import app.snapsync.ports.StoredResource
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The foreground settle (capability `upload-state-reconciliation`, "Foreground settles in-flight rows the backend
 * already stores"): a `REQUESTED` row whose bytes the per-device listing names becomes `COMPLETED`, through the
 * guarded terminal write, and nothing else changes — whatever the listing says or fails to say.
 */
class StoredUploadSettleTest {

    private val deviceId = "11111111-1111-4111-8111-111111111111"

    private class FakeFiles(private val result: Result<List<StoredResource>>) : DeviceFilesSource {
        var calls = 0
        override suspend fun list(deviceId: String): Result<List<StoredResource>> {
            calls++
            return result
        }
    }

    private class Recorder : LogWriter() {
        val severities = mutableListOf<Severity>()
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            severities += severity
        }
        fun logger() = Logger(loggerConfigInit(this), "StoredUploadSettleTest")
    }

    private fun listing(vararg keys: String) =
        FakeFiles(Result.success(keys.map { StoredResource(it, it.substringBefore('-')) }))

    private suspend fun ledgerHolding(vararg rows: Pair<String, LedgerState>) = InMemoryLedgerStore().apply {
        resetTo(rows.map { (key, state) -> LedgerEntry(key, key.substringBefore('-'), state) })
    }

    @Test
    fun `an in-flight row whose bytes are stored is settled`() = runTest {
        // The measured downgrade: the bytes landed, and the withheld extension was never presented the job.
        val ledger = ledgerHolding("A-primary.heic" to LedgerState.REQUESTED)

        StoredUploadSettle(listing("A-primary.heic"), ledger, { deviceId }).settle()

        assertEquals(LedgerState.COMPLETED, ledger.get("A-primary.heic")?.state)
    }

    @Test
    fun `an in-flight row without stored bytes is left in flight`() = runTest {
        val ledger = ledgerHolding("A-primary.heic" to LedgerState.REQUESTED)

        StoredUploadSettle(listing("B-primary.heic"), ledger, { deviceId }).settle()

        assertEquals(LedgerState.REQUESTED, ledger.get("A-primary.heic")?.state, "nothing is done without its bytes")
    }

    @Test
    fun `only in-flight rows are settled`() = runTest {
        // A listed DISCOVERED row has no job in flight to settle; it is the cycle's, and the guard leaves it.
        val ledger = ledgerHolding("D-primary.heic" to LedgerState.DISCOVERED)

        StoredUploadSettle(listing("D-primary.heic"), ledger, { deviceId }).settle()

        assertEquals(LedgerState.DISCOVERED, ledger.get("D-primary.heic")?.state)
        assertNull(ledger.get("X-primary.heic"), "a listed key with no row is never created")
    }

    @Test
    fun `nothing pending means no request`() = runTest {
        val files = listing("A-primary.heic")
        val ledger = ledgerHolding("A-primary.heic" to LedgerState.COMPLETED)

        StoredUploadSettle(files, ledger, { deviceId }).settle()

        assertEquals(0, files.calls)
    }

    @Test
    fun `a failed listing changes nothing and warns`() = runTest {
        val recorder = Recorder()
        val ledger = ledgerHolding("A-primary.heic" to LedgerState.REQUESTED)

        StoredUploadSettle(FakeFiles(Result.failure(RuntimeException("offline"))), ledger, { deviceId }, recorder.logger())
            .settle()

        assertEquals(LedgerState.REQUESTED, ledger.get("A-primary.heic")?.state)
        assertEquals(listOf(Severity.Warn), recorder.severities)
    }

    @Test
    fun `a listing this build cannot read is an error`() = runTest {
        val recorder = Recorder()
        val ledger = ledgerHolding("A-primary.heic" to LedgerState.REQUESTED)
        val files = FakeFiles(Result.failure(DeviceListingShapeException("shape")))

        StoredUploadSettle(files, ledger, { deviceId }, recorder.logger()).settle()

        assertEquals(LedgerState.REQUESTED, ledger.get("A-primary.heic")?.state)
        assertEquals(listOf(Severity.Error), recorder.severities)
    }

    @Test
    fun `a listing that never answers times out and changes nothing`() = runTest {
        val recorder = Recorder()
        val ledger = ledgerHolding("A-primary.heic" to LedgerState.REQUESTED)
        val hanging = object : DeviceFilesSource {
            override suspend fun list(deviceId: String): Result<List<StoredResource>> = awaitCancellation()
        }

        StoredUploadSettle(hanging, ledger, { deviceId }, recorder.logger()).settle()

        assertEquals(LedgerState.REQUESTED, ledger.get("A-primary.heic")?.state)
        assertEquals(listOf(Severity.Warn), recorder.severities)
    }

    @Test
    fun `a device-id read that throws is a failed fetch`() = runTest {
        val ledger = ledgerHolding("A-primary.heic" to LedgerState.REQUESTED)

        StoredUploadSettle(listing("A-primary.heic"), ledger, { error("keychain locked") }).settle()

        assertEquals(LedgerState.REQUESTED, ledger.get("A-primary.heic")?.state)
    }

    @Test
    fun `a late acknowledgement after the settle is a no-op`() = runTest {
        val ledger = ledgerHolding("A-primary.heic" to LedgerState.REQUESTED)

        StoredUploadSettle(listing("A-primary.heic"), ledger, { deviceId }).settle()

        assertFalse(ledger.markTerminal("A-primary.heic", TerminalOutcome.COMPLETED), "the guard applies to nothing")
        assertFalse(ledger.markTerminal("A-primary.heic", TerminalOutcome.FAILED), "nor does a late failure undo it")
        assertEquals(LedgerState.COMPLETED, ledger.get("A-primary.heic")?.state)
    }
}
