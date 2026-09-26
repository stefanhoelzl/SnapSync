package app.snapsync.feature.membership

import app.snapsync.feature.support.TestLedger
import app.snapsync.feature.support.testIdentity
import app.snapsync.feature.support.unreadableIdentity
import app.snapsync.model.AssetId
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.services.backend.DeviceFilesSource
import app.snapsync.services.backend.DeviceListingShapeException
import app.snapsync.model.StoredResource
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The join-time load (capability `photo-sharing`): a new membership starts from exactly the
 * device's stored resources, or from nothing — never from what the ledger held before the join.
 */
class ShareSetLoadTest {

    private val deviceId = "11111111-1111-4111-8111-111111111111"

    private class FakeFiles(var result: Result<List<StoredResource>>) : DeviceFilesSource {
        var lastDeviceId: String? = null
        override suspend fun list(deviceId: String): Result<List<StoredResource>> {
            lastDeviceId = deviceId
            return result
        }
    }

    /** Records what was logged, so a severity can be asserted rather than a message. */
    private class Recorder : LogWriter() {
        val severities = mutableListOf<Severity>()
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            severities += severity
        }
        fun logger() = Logger(loggerConfigInit(this), "ShareSetLoadTest")
    }

    private suspend fun ledgerHolding(vararg rows: LedgerEntry) =
        TestLedger().service.apply { resetTo(rows.toList()) }

    /** What a device carries into a join: work in flight, work waiting, and a stale belief inside the window. */
    private suspend fun leftovers() = ledgerHolding(
        LedgerEntry("R-primary.heic", AssetId("R"), LedgerState.REQUESTED),
        LedgerEntry("D-primary.heic", AssetId("D"), LedgerState.DISCOVERED),
        LedgerEntry("S-primary.heic", AssetId("S"), LedgerState.COMPLETED),
    )

    @Test
    fun `a confirmed listing becomes exactly the ledger`() = runTest {
        val files = FakeFiles(
            Result.success(listOf(StoredResource("A-primary.heic", AssetId("A")), StoredResource("A-live.mov", AssetId("A")))),
        )
        val ledger = leftovers()

        ShareSetLoad(files, ledger, testIdentity(deviceId)).load()

        assertEquals(deviceId, files.lastDeviceId) // listed by DEVICE, not event
        assertEquals(LedgerState.COMPLETED, ledger.get("A-primary.heic")?.state)
        assertEquals(LedgerState.COMPLETED, ledger.get("A-live.mov")?.state)
        // The leftovers are gone, including the stale COMPLETED that would have suppressed an upload.
        assertNull(ledger.get("R-primary.heic"))
        assertNull(ledger.get("D-primary.heic"))
        assertNull(ledger.get("S-primary.heic"))
    }

    @Test
    fun `the seeded assetId is the one the backend reported never parsed from the key`() = runTest {
        val files = FakeFiles(Result.success(listOf(StoredResource("K-primary.heic", AssetId("reported")))))
        val ledger = TestLedger().service

        ShareSetLoad(files, ledger, testIdentity(deviceId)).load()

        assertEquals(AssetId("reported"), ledger.get("K-primary.heic")?.assetId)
    }

    @Test
    fun `seeded rows are bare`() = runTest {
        // A listing carries no capture date; the first walk after the join fills the detail.
        val files = FakeFiles(Result.success(listOf(StoredResource("A-primary.heic", AssetId("A")))))
        val ledger = TestLedger().service

        ShareSetLoad(files, ledger, testIdentity(deviceId)).load()

        assertEquals("", ledger.get("A-primary.heic")?.creationDate)
    }

    @Test
    fun `an empty listing leaves an empty ledger`() = runTest {
        val ledger = leftovers()
        ShareSetLoad(FakeFiles(Result.success(emptyList())), ledger, testIdentity(deviceId)).load()
        assertTrue(ledger.manifestRows().isEmpty())
    }

    @Test
    fun `a transport failure clears the ledger and warns`() = runTest {
        val recorder = Recorder()
        val ledger = leftovers()

        ShareSetLoad(FakeFiles(Result.failure(Exception("offline"))), ledger, testIdentity(deviceId), recorder.logger()).load()

        assertTrue(ledger.manifestRows().isEmpty())
        assertEquals(listOf(Severity.Warn), recorder.severities)
    }

    @Test
    fun `a listing this build cannot read clears the ledger and is an error`() = runTest {
        val recorder = Recorder()
        val ledger = leftovers()
        val files = FakeFiles(Result.failure(DeviceListingShapeException("v1 shape")))

        ShareSetLoad(files, ledger, testIdentity(deviceId), recorder.logger()).load()

        assertTrue(ledger.manifestRows().isEmpty())
        assertEquals(listOf(Severity.Error), recorder.severities)
    }

    @Test
    fun `a listing that never answers times out clears the ledger and warns`() = runTest {
        val recorder = Recorder()
        val ledger = leftovers()
        val hanging = object : DeviceFilesSource {
            override suspend fun list(deviceId: String): Result<List<StoredResource>> = awaitCancellation()
        }

        ShareSetLoad(hanging, ledger, testIdentity(deviceId), recorder.logger()).load()

        assertTrue(ledger.manifestRows().isEmpty())
        assertEquals(listOf(Severity.Warn), recorder.severities)
    }

    @Test
    fun `a device-id read that throws is a failed fetch not a failed join`() = runTest {
        val ledger = leftovers()
        ShareSetLoad(FakeFiles(Result.success(emptyList())), ledger, unreadableIdentity()).load()
        assertTrue(ledger.manifestRows().isEmpty())
    }

    @Test
    fun `a load signals watchers`() = runTest {
        val ledger = TestLedger().service
        val signalled = async { ledger.changes.first() }
        testScheduler.runCurrent()

        ShareSetLoad(FakeFiles(Result.success(emptyList())), ledger, testIdentity(deviceId)).load()

        signalled.await()
    }
}
