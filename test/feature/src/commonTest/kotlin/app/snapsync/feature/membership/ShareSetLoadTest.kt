package app.snapsync.feature.membership

import app.snapsync.feature.support.CapturingLogWriter
import app.snapsync.feature.support.TestLedger
import app.snapsync.feature.support.testIdentity
import app.snapsync.feature.support.unreadableIdentity
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.model.AssetId
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.StoredResource
import app.snapsync.ports.DbOpen
import app.snapsync.services.backend.DeviceFilesSource
import app.snapsync.services.backend.DeviceListingShapeException
import app.snapsync.services.ledger.LEDGER_DB_NAME
import co.touchlab.kermit.Severity
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The join-time load (capability `photo-sharing`): the joined event's rows become exactly the device's stored
 * resources in it, and every other event's rows are purged; a failed listing purges the other events and keeps
 * what this device already knew of the joined one (change `event-scoped-local-state`).
 */
class ShareSetLoadTest {

    private val deviceId = "11111111-1111-4111-8111-111111111111"
    private val eventId = "7a3f9c21-0000-4000-8000-000000000001"
    private val earlierEvent = "7a3f9c21-0000-4000-8000-000000000000"

    private class FakeFiles(var result: Result<List<StoredResource>>) : DeviceFilesSource {
        var lastDeviceId: String? = null
        var lastEventId: String? = null
        override suspend fun list(eventId: String, deviceId: String): Result<List<StoredResource>> {
            lastEventId = eventId
            lastDeviceId = deviceId
            return result
        }
    }

    /**
     * What a device carries into a join: an earlier event's rows — work in flight, work waiting, a stale belief —
     * and, for a rejoin, one row of the event being joined. The ledger is joined to [eventId], as it will be once
     * the join saves its config.
     */
    private suspend fun leftovers() = TestLedger(joined = eventId).apply {
        service.resetTo(
            earlierEvent,
            listOf(
                LedgerEntry("R-primary.heic", AssetId("R"), LedgerState.REQUESTED),
                LedgerEntry("D-primary.heic", AssetId("D"), LedgerState.DISCOVERED),
                LedgerEntry("S-primary.heic", AssetId("S"), LedgerState.COMPLETED),
            ),
        )
        service.recordUnlessSettled(LedgerEntry("J-primary.heic", AssetId("J"), LedgerState.COMPLETED))
    }

    @Test
    fun `a confirmed listing becomes exactly the joined event's rows and purges every other event`() = runTest {
        val files = FakeFiles(
            Result.success(
                listOf(StoredResource("A-primary.heic", AssetId("A")), StoredResource("A-live.mov", AssetId("A"))),
            ),
        )
        val ledger = leftovers()

        ShareSetLoad(files, ledger.service, testIdentity(deviceId)).load(eventId)

        // Listed for this device IN THE JOINED EVENT: another event's bytes are not this one's.
        assertEquals(eventId to deviceId, files.lastEventId to files.lastDeviceId)
        assertEquals(setOf("A-primary.heic", "A-live.mov"), ledger.rows().keys)
        assertTrue(ledger.rows().values.all { it.state == LedgerState.COMPLETED })
        assertEquals(setOf(eventId), ledger.eventsHeld(), "the earlier event's rows are purged")
    }

    @Test
    fun `the seeded assetId is the one the backend reported never parsed from the key`() = runTest {
        val files = FakeFiles(Result.success(listOf(StoredResource("K-primary.heic", AssetId("reported")))))
        val ledger = TestLedger(joined = eventId).service

        ShareSetLoad(files, ledger, testIdentity(deviceId)).load(eventId)

        assertEquals(AssetId("reported"), ledger.get("K-primary.heic")?.assetId)
    }

    @Test
    fun `seeded rows are bare`() = runTest {
        // A listing carries no capture date; the first walk after the join fills the detail.
        val files = FakeFiles(Result.success(listOf(StoredResource("A-primary.heic", AssetId("A")))))
        val ledger = TestLedger(joined = eventId).service

        ShareSetLoad(files, ledger, testIdentity(deviceId)).load(eventId)

        assertEquals("", ledger.get("A-primary.heic")?.creationDate)
    }

    @Test
    fun `an empty listing leaves the joined event empty`() = runTest {
        val ledger = leftovers()
        ShareSetLoad(FakeFiles(Result.success(emptyList())), ledger.service, testIdentity(deviceId)).load(eventId)
        assertTrue(ledger.service.manifestRows().isEmpty())
        assertTrue(ledger.eventsHeld().isEmpty())
    }

    @Test
    fun `a transport failure purges other events keeps the joined event's rows and warns`() = runTest {
        val recorder = CapturingLogWriter()
        val ledger = leftovers()

        ShareSetLoad(
            FakeFiles(Result.failure(Exception("offline"))),
            ledger.service,
            testIdentity(deviceId),
            recorder.logger("ShareSetLoadTest"),
        ).load(eventId)

        assertKeptOnlyTheJoinedEvent(ledger)
        assertEquals(listOf(Severity.Warn), recorder.severities)
    }

    @Test
    fun `a listing this build cannot read purges other events and is an error`() = runTest {
        val recorder = CapturingLogWriter()
        val ledger = leftovers()
        val files = FakeFiles(Result.failure(DeviceListingShapeException("v1 shape")))

        ShareSetLoad(files, ledger.service, testIdentity(deviceId), recorder.logger("ShareSetLoadTest")).load(eventId)

        assertKeptOnlyTheJoinedEvent(ledger)
        assertEquals(listOf(Severity.Error), recorder.severities)
    }

    @Test
    fun `a listing that never answers times out purges other events and warns`() = runTest {
        val recorder = CapturingLogWriter()
        val ledger = leftovers()
        val hanging = object : DeviceFilesSource {
            override suspend fun list(
                eventId: String,
                deviceId: String,
            ): Result<List<StoredResource>> = awaitCancellation()
        }

        ShareSetLoad(hanging, ledger.service, testIdentity(deviceId), recorder.logger("ShareSetLoadTest")).load(eventId)

        assertKeptOnlyTheJoinedEvent(ledger)
        assertEquals(listOf(Severity.Warn), recorder.severities)
    }

    @Test
    fun `a device-id read that throws is a failed fetch not a failed join`() = runTest {
        val ledger = leftovers()
        ShareSetLoad(FakeFiles(Result.success(emptyList())), ledger.service, unreadableIdentity()).load(eventId)
        assertKeptOnlyTheJoinedEvent(ledger)
    }

    @Test
    fun `a ledger that cannot be opened is an error not a failed join`() = runTest {
        val recorder = CapturingLogWriter()
        val ledger = TestLedger(inMemoryDatabases(mapOf(LEDGER_DB_NAME to DbOpen.Failed("locked"))), joined = eventId)
        val files = FakeFiles(Result.success(listOf(StoredResource("A-primary.heic", AssetId("A")))))

        ShareSetLoad(files, ledger.service, testIdentity(deviceId), recorder.logger("ShareSetLoadTest")).load(eventId)

        assertEquals(listOf(Severity.Error), recorder.severities, "the load returns, having said why it seeded nothing")
        assertTrue(ledger.eventsHeld().isEmpty())
    }

    private suspend fun assertKeptOnlyTheJoinedEvent(ledger: TestLedger) {
        assertEquals(setOf(eventId), ledger.eventsHeld())
        assertEquals(setOf("J-primary.heic"), ledger.rows().keys)
    }
}
