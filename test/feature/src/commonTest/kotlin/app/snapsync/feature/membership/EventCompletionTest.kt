package app.snapsync.feature.membership

import app.snapsync.feature.support.CapturingLogWriter
import app.snapsync.feature.support.ConfigWrites
import app.snapsync.feature.support.LEDGER_EVENT
import app.snapsync.feature.support.TestLedger
import app.snapsync.feature.support.inertPendingLeaves
import app.snapsync.mock.fakeCrypto
import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.model.AssetId
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.EventCompletionState
import app.snapsync.model.EventConfig
import app.snapsync.model.EventLookup
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.ManifestResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.encodeToJson
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.ports.DbOpen
import app.snapsync.ports.Files
import app.snapsync.services.backend.EventDirectory
import app.snapsync.services.crypto.EventKeys
import app.snapsync.services.leave.PendingLeaves
import app.snapsync.services.ledger.LEDGER_DB_NAME
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.services.wake.EventCheck
import app.snapsync.services.wake.EventChecks
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The end-of-wake step (capability `manage-membership`, "The app leaves on its own once the event is finished for
 * it"): over the REAL config service and the REAL [MembershipRefresh] and [LeaveEvent], with the backend, the ledger
 * and the downloads played by the fields below.
 */
class EventCompletionTest {

    private val asset = AssetId("A_L0_1")
    private val ends = eventEnd("2026-07-13T12:00:00Z")
    private val joined = EventConfig(
        eventId = "E",
        name = "Party",
        minPhotoDate = captureCutoff("2026-07-06T12:00:00Z"),
        startsAt = eventStart("2026-07-06T12:00:00Z"),
        endsAt = ends,
        maxPhotoDate = captureCeiling("2026-07-13T12:00:00Z"),
        deletesAt = deletesAt("2026-08-05T12:00:00Z"),
    )

    private fun details(closed: Boolean = false, completed: Boolean = false) = EventLookup.Found(
        joined.name,
        joined.startsAt,
        ends,
        joined.deletesAt,
        EventCompletionState(closed, completed),
    )

    private fun manifest(final: Boolean) = "E " + DeviceManifest(
        deviceId = "D",
        assets = listOf(
            DeviceManifestAsset(
                asset,
                "2026-07-10T00:00:00Z",
                listOf(ManifestResource(ResourceRole.PRIMARY, "image/heic", "k", "IMG.HEIC")),
            ),
        ),
        version = 1,
        final = final,
    ).encodeToJson()

    /** One scenario's world, over the real services: what the backend answers, what was published, what is pending. */
    private inner class World(
        private val now: String,
        initial: EventConfig? = joined,
        recorded: String? = manifest(final = true),
        val ledger: LedgerService = TestLedger().service,
        leavesFiles: Files = inMemoryFiles(),
    ) {
        val writes = ConfigWrites()
        val config = writes.service(initial, fixedClock(Instant.parse(now)))
        var answer: EventLookup = details()
        val manifestRecord = DeviceManifestService(inMemoryFiles()).apply { recorded?.let(::saveLastUploaded) }
        var received = true

        /** Runs while the union is read — where a member's own join or leave lands when it lands during that read. */
        var duringReceivedCheck: suspend () -> Unit = {}
        var fetches = 0
        var finalPublishes = 0
        var publishFails = false
        val leavesSent = mutableListOf<String>()
        val pendingLeaves = PendingLeaves(leavesFiles, { id, _ ->
            leavesSent += id
            Result.success(Unit)
        })
        var clock: Instant = Instant.parse(now)
        val checks = EventChecks(inMemoryPreferences(), now = { clock })

        suspend fun pending(asset: AssetId) =
            ledger.resetTo(
                LEDGER_EVENT,
                listOf(LedgerEntry("${asset.value}-primary.heic", asset, LedgerState.REQUESTED)),
            )

        fun TestScope.completion(log: Logger = Logger.withTag("EventCompletion")) = EventCompletion(
            config = config,
            refresh = MembershipRefresh(config, leave()),
            leaveEvent = leave(),
            directory = EventDirectory {
                fetches++
                answer
            },
            manifestRecord = manifestRecord,
            ledger = ledger,
            pendingLeaves = pendingLeaves,
            publishFinal = {
                finalPublishes++
                if (publishFails) error("the manifest publish failed")
            },
            everythingReceived = {
                duringReceivedCheck()
                received
            },
            checks = checks,
            log = log,
        )

        fun TestScope.leave() = LeaveEvent(
            keys = EventKeys(fakeCrypto(), inMemorySecureStore()),
            config = config,
            stopUploads = {},
            notifyLeave = { _, _ -> },
            everythingReceived = { false },
            scope = this,
            pendingLeaves = inertPendingLeaves(),
        )
    }

    @Test
    fun `before the range has ended nothing is fetched and outstanding leaves are still delivered`() = runTest {
        val w = World(now = "2026-07-12T00:00:00Z")
        w.pendingLeaves.record("OLD")
        val outcome = with(w) { completion() }.finish()
        assertEquals(CompletionOutcome.NOT_ENDED, outcome)
        assertEquals(0, w.fetches)
        assertEquals(listOf("OLD"), w.leavesSent)
    }

    @Test
    fun `after the end an unsettled share is settled once more`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply { manifestRecord.saveLastUploaded(manifest(final = false)) }
        with(w) { completion() }.finish()
        assertEquals(1, w.finalPublishes)
    }

    @Test
    fun `an open event keeps the member`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z")
        assertEquals(CompletionOutcome.WAITING, with(w) { completion() }.finish())
        assertEquals("E", w.config.config.value?.eventId)
    }

    @Test
    fun `a closed event with everything here is left`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply { answer = details(closed = true) }
        assertEquals(CompletionOutcome.LEFT, with(w) { completion() }.finish())
        assertNull(w.config.config.value)
    }

    @Test
    fun `a join landing while the union is read is not torn down by the finished event's leave`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply { answer = details(closed = true) }
        val next = joined.copy(eventId = "F", name = "Next")
        w.duringReceivedCheck = { w.config.save(next) }
        assertEquals(CompletionOutcome.WAITING, with(w) { completion() }.finish())
        assertEquals(next, w.config.config.value)
    }

    @Test
    fun `a leave landing while the union is read is not reported as this step's own`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply { answer = details(closed = true) }
        w.duringReceivedCheck = { w.config.clear() }
        assertEquals(CompletionOutcome.WAITING, with(w) { completion() }.finish())
        assertNull(w.config.config.value)
    }

    @Test
    fun `a closed event keeps a member whose own photo is still uploading`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply {
            answer = details(closed = true)
            pending(asset)
        }
        assertEquals(CompletionOutcome.WAITING, with(w) { completion() }.finish())
        assertEquals(true, w.config.config.value?.closed)
    }

    @Test
    fun `a closed event keeps a member still missing another member’s photo`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply {
            answer = details(closed = true)
            received = false
        }
        assertEquals(CompletionOutcome.WAITING, with(w) { completion() }.finish())
    }

    @Test
    fun `a closed event keeps a member whose share was never published as settled`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply {
            answer = details(closed = true)
            manifestRecord.saveLastUploaded(manifest(final = false))
        }
        assertEquals(CompletionOutcome.WAITING, with(w) { completion() }.finish())
    }

    @Test
    fun `a completed event is left whatever is outstanding`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply {
            answer = details(closed = true, completed = true)
            pending(asset)
            received = false
        }
        assertEquals(CompletionOutcome.LEFT, with(w) { completion() }.finish())
        assertNull(w.config.config.value)
    }

    @Test
    fun `a failed read keeps the member`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply { answer = EventLookup.Failed }
        assertEquals(CompletionOutcome.WAITING, with(w) { completion() }.finish())
        assertTrue(w.config.config.value != null)
    }

    @Test
    fun `no membership is nothing to finish`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z", initial = null)
        assertEquals(CompletionOutcome.NOT_JOINED, with(w) { completion() }.finish())
        assertEquals(0, w.fetches)
    }

    @Test
    fun `a gone event past its deadline is left through the refresh rule`() = runTest {
        val w = World(now = "2026-08-06T00:00:00Z").apply { answer = EventLookup.NotFound }
        assertEquals(CompletionOutcome.LEFT, with(w) { completion() }.finish())
    }

    @Test
    fun `a record of another event’s manifest is no settled share here`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply {
            answer = details(closed = true)
            manifestRecord.saveLastUploaded(manifest(final = true).replaceFirst("E ", "OTHER "))
        }
        assertEquals(CompletionOutcome.WAITING, with(w) { completion() }.finish())
        assertEquals(1, w.finalPublishes, "a share not settled for this event is settled once more")
    }

    @Test
    fun `an unreadable manifest record keeps the member`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply {
            answer = details(closed = true)
            manifestRecord.saveLastUploaded("E not json")
        }
        assertEquals(CompletionOutcome.WAITING, with(w) { completion() }.finish())
    }

    @Test
    fun `a failing download answer keeps the member`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply { answer = details(closed = true) }
        val completion = with(w) {
            EventCompletion(
                config = config,
                refresh = MembershipRefresh(config, leave()),
                leaveEvent = leave(),
                directory = EventDirectory { answer },
                manifestRecord = manifestRecord,
                ledger = ledger,
                pendingLeaves = pendingLeaves,
                publishFinal = {},
                everythingReceived = { error("the union read failed") },
                checks = checks,
            )
        }
        assertEquals(CompletionOutcome.WAITING, completion.finish())
    }

    // ---- the bounded read of the event's state (decision record `changes/timely-background-receiving`, D5) ----------

    @Test
    fun `a bounded wake within the hour of any read of the event reads none`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z")
        with(w) { completion() }.finish(bounded = false) // a push, an opening or a join: it reads, and stamps
        w.clock = w.clock + 59.minutes
        assertEquals(CompletionOutcome.WAITING, with(w) { completion() }.finish(bounded = true))
        assertEquals(1, w.fetches, "the bounded wake read nothing")
        w.clock = w.clock + 1.minutes
        with(w) { completion() }.finish(bounded = true)
        assertEquals(2, w.fetches, "an hour after the last read it reads again")
    }

    @Test
    fun `an unbounded step reads the event however recently it was read`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z")
        with(w) { completion() }.finish(bounded = false)
        with(w) { completion() }.finish(bounded = false)
        assertEquals(2, w.fetches, "a close push must be heard")
    }

    @Test
    fun `before the end no bound is consulted and nothing is read`() = runTest {
        val w = World(now = "2026-07-01T00:00:00Z")
        assertEquals(CompletionOutcome.NOT_ENDED, with(w) { completion() }.finish(bounded = true))
        assertEquals(0, w.fetches)
        assertTrue(w.checks.due(EventCheck.CLOSE, joined.eventId), "and no time was stamped")
    }

    // ---- every doubt resolves toward staying joined, and the step never throws ------------------------------------

    @Test
    fun `a device that never recorded a published manifest settles its share once more`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z", recorded = null).apply { answer = details(closed = true) }
        assertEquals(CompletionOutcome.WAITING, with(w) { completion() }.finish())
        assertEquals(1, w.finalPublishes)
        assertEquals("E", w.config.config.value?.eventId, "no settled share is no leave")
    }

    @Test
    fun `a failing settle still reads the event and keeps the member`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply {
            manifestRecord.saveLastUploaded(manifest(final = false))
            publishFails = true
            answer = details(closed = true)
        }
        assertEquals(CompletionOutcome.WAITING, with(w) { completion() }.finish())
        assertEquals(1, w.fetches, "the failed settle costs the wake nothing else")
        assertEquals(true, w.config.config.value?.closed, "the close is still recorded")
    }

    @Test
    fun `a failing settle never stops a completed event from ending the membership`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply {
            manifestRecord.saveLastUploaded(manifest(final = false))
            publishFails = true
            answer = details(closed = true, completed = true)
        }
        assertEquals(CompletionOutcome.LEFT, with(w) { completion() }.finish())
        assertNull(w.config.config.value)
    }

    @Test
    fun `an unreadable upload ledger keeps the member of a closed event`() = runTest {
        val unopenable = TestLedger(inMemoryDatabases(mapOf(LEDGER_DB_NAME to DbOpen.Failed("locked")))).service
        val w = World(now = "2026-07-14T00:00:00Z", ledger = unopenable).apply { answer = details(closed = true) }
        assertEquals(CompletionOutcome.WAITING, with(w) { completion() }.finish())
        assertEquals("E", w.config.config.value?.eventId)
    }

    @Test
    fun `a pending-leave record that throws does not stop the end-of-wake step`() = runTest {
        val throwing = object : Files by inMemoryFiles() {
            override fun read(area: FileArea, path: String): FileResult<ByteArray> = error("the store threw")
        }
        val w = World(now = "2026-07-14T00:00:00Z", leavesFiles = throwing).apply { answer = details(closed = true) }
        assertEquals(CompletionOutcome.LEFT, with(w) { completion() }.finish())
        assertNull(w.config.config.value)
    }

    // ---- the end of a whole-pass wake: the bounded photo check, then the step (`changes/timely-background-receiving`, D4) ----

    @Test
    fun `a wake that checks photos runs the check for the joined event and then the step`() = runTest {
        val w = World(now = "2026-07-12T00:00:00Z")
        val checked = mutableListOf<String>()
        val outcome = with(w) { completion() }.endOfWake(checksPhotos = true, bounded = false) { checked += it }
        assertEquals(listOf("E"), checked)
        assertEquals(CompletionOutcome.NOT_ENDED, outcome)
    }

    @Test
    fun `a wake that does not check photos runs no check`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z")
        val checked = mutableListOf<String>()
        val outcome = with(w) { completion() }.endOfWake(checksPhotos = false, bounded = false) { checked += it }
        assertEquals(emptyList(), checked)
        assertEquals(CompletionOutcome.WAITING, outcome)
    }

    @Test
    fun `an unjoined wake runs no photo check`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z", initial = null)
        val checked = mutableListOf<String>()
        val outcome = with(w) { completion() }.endOfWake(checksPhotos = true, bounded = false) { checked += it }
        assertEquals(emptyList(), checked)
        assertEquals(CompletionOutcome.NOT_JOINED, outcome)
    }

    @Test
    fun `a failing photo check is logged and the step still runs`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply { answer = details(closed = true) }
        val recorder = CapturingLogWriter()
        val outcome = with(w) { completion(recorder.logger()) }
            .endOfWake(checksPhotos = true, bounded = false) { error("the union read failed") }
        assertEquals(CompletionOutcome.LEFT, outcome)
        assertEquals(1, w.fetches)
        assertTrue(recorder.lines.any { (severity, line) -> severity == Severity.Warn && "photo check failed" in line })
    }
}
