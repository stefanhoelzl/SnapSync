package app.snapsync.application.membership

import app.snapsync.application.EntryPointCall
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.store.LEDGER_DB_NAME
import app.snapsync.data.upload.DeviceListing
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.upload.LedgerState
import app.snapsync.domain.upload.UploadRow
import app.snapsync.feature.support.ScriptedBackend
import app.snapsync.feature.support.TestLedger
import app.snapsync.feature.support.testIdentity
import app.snapsync.feature.support.unreadableIdentity
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.LogLevel
import app.snapsync.inbound.LogLine
import app.snapsync.inbound.LogWriter
import app.snapsync.levers.WriteKind
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.ports.DbOpen
import app.snapsync.ports.HttpResult
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The join-time load (capability `photo-sharing`): the joined event's rows become exactly the device's stored
 * resources in it, and every other event's rows are purged; a failed listing purges the other events and keeps
 * what this device already knew of the joined one (change `event-scoped-local-state`).
 */
@OptIn(EntryPointCall::class, CompositionOnly::class)
class LoadShareSetTest {

    /** The severities of the load's log lines. */
    private class Recorder {
        val severities = mutableListOf<LogLevel>()
    }

    /** The load, its lines recorded into [recorder] while it runs. */
    private class Loading(private val share: LoadShareSet, private val recorder: Recorder) {
        suspend fun load(eventId: app.snapsync.domain.event.EventId): ShareSetLoaded {
            Log.install(
                listOf(object : LogWriter {
                    override fun write(line: LogLine) {
                        val told = line.tag == "LoadShareSet" && line.level != LogLevel.INFO
                        if (told) recorder.severities += line.level
                    }
                }),
            )
            return try {
                share(eventId)
            } finally {
                Log.install(emptyList())
            }
        }
    }

    private val deviceId = "11111111-1111-4111-8111-111111111111"
    private val eventId = "7a3f9c21-0000-4000-8000-000000000001"
    private val earlierEvent = "7a3f9c21-0000-4000-8000-000000000000"

    private fun listing(body: String) = ScriptedBackend { ScriptedBackend.json(200, body) }

    private fun load(
        backend: ScriptedBackend,
        ledger: TestLedger,
        identity: DeviceIdentity = testIdentity(deviceId),
        recorder: Recorder = Recorder(),
    ) = Loading(LoadShareSet(DeviceListing(backend.gateway, identity), ledger.reset), recorder)

    /**
     * What a device carries into a join: an earlier event's rows — work in flight, work waiting, a stale belief —
     * and, for a rejoin, two rows of the event being joined: one stored, one whose upload the leave stopped.
     */
    private fun leftovers() = TestLedger().apply {
        put(earlierEvent, "R-primary.heic", LedgerState.REQUESTED)
        put(earlierEvent, "D-primary.heic", LedgerState.DISCOVERED)
        put(earlierEvent, "S-primary.heic", LedgerState.COMPLETED)
        put(eventId, "J-primary.heic", LedgerState.COMPLETED)
        put(eventId, "W-primary.heic", LedgerState.REQUESTED) // on its way when the leave stopped every upload
    }

    @Test
    fun `a confirmed listing becomes exactly the joined event's rows and purges every other event`() = runTest {
        val backend = listing(
            """[{"assetId":"A","role":"primary","filename":"IMG.HEIC"},{"assetId":"A","role":"live","filename":"IMG.MOV"}]""",
        )
        val ledger = leftovers()

        load(backend, ledger).load(testEventId(eventId))

        // Listed for this device IN THE JOINED EVENT: another event's bytes are not this one's.
        val asked = backend.requests.single().url
        assertTrue(testEventId(eventId).value in asked && testDeviceId(deviceId).value in asked, asked)
        assertEquals(setOf("A-primary.heic", "A-live.mov"), ledger.rowsOf(eventId).keys)
        assertTrue(ledger.rowsOf(eventId).values.all { it is UploadRow.Completed })
        assertEquals(setOf(testEventId(eventId).value), ledger.eventsHeld(), "the earlier event's rows are purged")
    }

    @Test
    fun `a seeded row is keyed by the asset the backend reported and is bare`() = runTest {
        // A listing carries no capture date; the first walk after the join fills the detail.
        val ledger = TestLedger()

        load(listing("""[{"assetId":"reported","role":"primary","filename":"K.HEIC"}]"""), ledger)
            .load(testEventId(eventId))

        val row = ledger.rowsOf(eventId).values.single()
        assertEquals(testAssetId("reported"), row.key.asset)
        assertEquals(CaptureDate.Undated, row.detail.captured)
    }

    @Test
    fun `an empty listing leaves the joined event empty`() = runTest {
        val ledger = leftovers()
        load(listing("[]"), ledger).load(testEventId(eventId))
        assertTrue(ledger.eventsHeld().isEmpty())
    }

    @Test
    fun `a purge the store refuses is an error, never a failed join`() = runTest {
        val recorder = Recorder()
        val device = DatabasesMock()
        val ledger = TestLedger(device.port())
        ledger.put(earlierEvent, "R-primary.heic", LedgerState.REQUESTED)
        device.fail(LEDGER_DB_NAME, "ledgerRow", WriteKind.DELETE)

        load(ScriptedBackend { HttpResult.Unreachable("offline") }, ledger, recorder = recorder)
            .load(testEventId(eventId))

        assertEquals(
            listOf(LogLevel.WARN, LogLevel.ERROR),
            recorder.severities,
            "the listing's failure, then the purge's",
        )
    }

    @Test
    fun `a transport failure purges other events keeps the joined event's rows and warns`() = runTest {
        val recorder = Recorder()
        val ledger = leftovers()

        load(ScriptedBackend { HttpResult.Unreachable("offline") }, ledger, recorder = recorder)
            .load(testEventId(eventId))

        assertKeptOnlyTheJoinedEvent(ledger)
        assertEquals(listOf(LogLevel.WARN), recorder.severities)
    }

    @Test
    fun `a listing this build cannot read purges other events and is an error`() = runTest {
        val recorder = Recorder()
        val ledger = leftovers()

        load(listing("""[{"assetId":"bad id!","role":"primary","filename":"x"}]"""), ledger, recorder = recorder)
            .load(testEventId(eventId))

        assertKeptOnlyTheJoinedEvent(ledger)
        assertEquals(listOf(LogLevel.ERROR), recorder.severities)
    }

    @Test
    fun `a listing that never answers times out purges other events and warns`() = runTest {
        val recorder = Recorder()
        val ledger = leftovers()

        load(ScriptedBackend { awaitCancellation() }, ledger, recorder = recorder).load(testEventId(eventId))

        assertKeptOnlyTheJoinedEvent(ledger)
        assertEquals(listOf(LogLevel.WARN), recorder.severities)
    }

    @Test
    fun `a device id that cannot be read is a failed listing not a failed join`() = runTest {
        val ledger = leftovers()
        load(listing("[]"), ledger, identity = unreadableIdentity()).load(testEventId(eventId))
        assertKeptOnlyTheJoinedEvent(ledger)
    }

    @Test
    fun `a ledger that cannot be opened is an error not a failed join`() = runTest {
        val recorder = Recorder()
        val ledger = TestLedger(inMemoryDatabases(mapOf(LEDGER_DB_NAME to DbOpen.Failed("locked"))))

        load(listing("""[{"assetId":"A","role":"primary","filename":"A.HEIC"}]"""), ledger, recorder = recorder)
            .load(testEventId(eventId))

        assertEquals(listOf(LogLevel.ERROR), recorder.severities, "the load returns, having said why it seeded nothing")
        assertTrue(ledger.eventsHeld().isEmpty())
    }

    private fun assertKeptOnlyTheJoinedEvent(ledger: TestLedger) {
        assertEquals(setOf(testEventId(eventId).value), ledger.eventsHeld())
        val kept = ledger.rowsOf(eventId)
        assertEquals(setOf("J-primary.heic", "W-primary.heic"), kept.keys)
        assertIs<UploadRow.Completed>(kept.getValue("J-primary.heic"), "what was stored stays stored")
        assertIs<UploadRow.Discovered>(kept.getValue("W-primary.heic"), "the stranded upload needs a job again")
    }
}
