package app.snapsync.feature.membership

import app.snapsync.feature.support.CapturingLogWriter
import app.snapsync.feature.support.ConfigWrites
import app.snapsync.feature.support.SharedStore
import app.snapsync.feature.support.testShared
import app.snapsync.mock.fixedClock
import app.snapsync.model.AssetId
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.EventCompletionState
import app.snapsync.model.EventConfig
import app.snapsync.model.EventLookup
import app.snapsync.model.ManifestResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.UploadedManifest
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.encodeToJson
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.services.backend.EventDirectory
import app.snapsync.services.databases.SharedDatabase
import app.snapsync.services.leave.PendingLeaves
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.services.wake.EventCheck
import app.snapsync.services.wake.EventChecks
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The end-of-wake step (capability `event-lifetime`: a member is done once it has received everything, and the app
 * never leaves an event on its own): over the REAL config service and the REAL [MembershipRefresh], with the backend
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

    private fun details(completed: Boolean = false) = EventLookup.Found(
        joined.name,
        joined.startsAt,
        ends,
        joined.deletesAt,
        EventCompletionState(completed),
    )

    private fun manifest(final: Boolean) = UploadedManifest(
        "E",
        1,
        DeviceManifest(
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
        ).encodeToJson(),
    )

    /** One scenario's world, over the real services: what the backend answers, what was published, what is pending. */
    private inner class World(
        private val now: String,
        initial: EventConfig? = joined,
        recorded: UploadedManifest? = manifest(final = true),
        leaves: SharedDatabase = testShared(),
    ) {
        val writes = ConfigWrites()
        val config = writes.service(initial, fixedClock(Instant.parse(now)))
        var answer: EventLookup = details()
        val manifestRecord = DeviceManifestService(testShared()).apply { recorded?.let(::saveLastUploaded) }

        /** The position this device holds everything up to, or `null` while it does not. */
        var position: Long? = 4

        /** Runs while the union is read — where a member's own join or leave lands when it lands during that read. */
        var duringReceivedCheck: suspend () -> Unit = {}
        var fetches = 0
        var finalPublishes = 0
        var publishFails = false
        var reportAccepted = true
        val reports = mutableListOf<Pair<String, Long>>()
        var transfersStopped = 0
        val leavesSent = mutableListOf<String>()
        val pendingLeaves = PendingLeaves(leaves, { id, _ ->
            leavesSent += id
            Result.success(Unit)
        })
        var clock: Instant = Instant.parse(now)
        val checks = EventChecks(testShared(), now = { clock })

        fun completion(
            log: Logger = Logger.withTag("EventCompletion"),
            receivedPosition: suspend (String) -> Long? = {
                duringReceivedCheck()
                position
            },
        ) = EventCompletion(
            config = config,
            refresh = MembershipRefresh(config, stopTransfers = { transfersStopped++ }),
            directory = EventDirectory {
                fetches++
                answer
            },
            manifestRecord = manifestRecord,
            pendingLeaves = pendingLeaves,
            publishFinal = {
                finalPublishes++
                if (publishFails) error("the manifest publish failed")
            },
            receivedPosition = receivedPosition,
            reporter = { eventId, position ->
                reports += eventId to position
                reportAccepted
            },
            checks = checks,
            log = log,
        )
    }

    @Test
    fun `before the range has ended nothing is fetched or reported and outstanding leaves are still delivered`() =
        runTest {
            val w = World(now = "2026-07-12T00:00:00Z")
            w.pendingLeaves.record("OLD")
            assertEquals(CompletionOutcome.NOT_ENDED, w.completion().finish())
            assertEquals(0, w.fetches)
            assertEquals(emptyList(), w.reports)
            assertEquals(listOf("OLD"), w.leavesSent)
        }

    @Test
    fun `after the end an unsettled share is settled once more`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply { manifestRecord.saveLastUploaded(manifest(final = false)) }
        w.completion().finish()
        assertEquals(1, w.finalPublishes)
    }

    @Test
    fun `a device holding everything reports its position and stays in the event`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z")
        assertEquals(CompletionOutcome.REPORTED, w.completion().finish())
        assertEquals(listOf("E" to 4L), w.reports)
        assertEquals("E", w.config.config.value?.eventId, "the app never leaves an event on its own")
    }

    @Test
    fun `a position already reported is not sent again and a higher one is`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z")
        val completion = w.completion()
        completion.finish()
        assertEquals(CompletionOutcome.WAITING, completion.finish())
        w.position = 9
        assertEquals(CompletionOutcome.REPORTED, completion.finish())
        assertEquals(listOf("E" to 4L, "E" to 9L), w.reports)
    }

    @Test
    fun `a report the backend did not record is sent again by the next wake`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply { reportAccepted = false }
        val completion = w.completion()
        assertEquals(CompletionOutcome.WAITING, completion.finish())
        w.reportAccepted = true
        assertEquals(CompletionOutcome.REPORTED, completion.finish())
        assertEquals(listOf("E" to 4L, "E" to 4L), w.reports)
    }

    @Test
    fun `a device still missing another member's photo reports nothing and stays`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply { position = null }
        assertEquals(CompletionOutcome.WAITING, w.completion().finish())
        assertEquals(emptyList(), w.reports)
        assertEquals("E", w.config.config.value?.eventId)
    }

    @Test
    fun `a join landing while the union is read is reported for nobody`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z")
        val next = joined.copy(eventId = "F", name = "Next")
        w.duringReceivedCheck = { w.config.save(next) }
        assertEquals(CompletionOutcome.WAITING, w.completion().finish())
        assertEquals(emptyList(), w.reports)
        assertEquals(next, w.config.config.value)
    }

    @Test
    fun `a leave landing while the union is read is reported for nobody`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z")
        w.duringReceivedCheck = { w.config.clear() }
        assertEquals(CompletionOutcome.WAITING, w.completion().finish())
        assertEquals(emptyList(), w.reports)
    }

    @Test
    fun `a completed event is marked deleted and kept - whatever is outstanding`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply {
            answer = details(completed = true)
            position = null
        }
        assertEquals(CompletionOutcome.DELETED, w.completion().finish())
        assertEquals(true, w.config.config.value?.deleted, "the member stays, shown the event is over")
        assertEquals(1, w.transfersStopped)
        assertEquals(emptyList(), w.reports)
    }

    @Test
    fun `a membership already marked deleted asks nothing more`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z", initial = joined.copy(deleted = true))
        assertEquals(CompletionOutcome.DELETED, w.completion().finish())
        assertEquals(0, w.fetches)
        assertEquals(0, w.finalPublishes)
        assertEquals(emptyList(), w.reports)
    }

    @Test
    fun `a failed read keeps the member and still reports what it holds`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply { answer = EventLookup.Failed }
        assertEquals(CompletionOutcome.REPORTED, w.completion().finish())
        assertFalse(w.config.config.value?.deleted ?: true)
    }

    @Test
    fun `no membership is nothing to finish`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z", initial = null)
        assertEquals(CompletionOutcome.NOT_JOINED, w.completion().finish())
        assertEquals(0, w.fetches)
    }

    @Test
    fun `a gone event past its deadline is marked deleted through the refresh rule`() = runTest {
        val w = World(now = "2026-08-06T00:00:00Z").apply { answer = EventLookup.NotFound }
        assertEquals(CompletionOutcome.DELETED, w.completion().finish())
        assertEquals(true, w.config.config.value?.deleted)
    }

    @Test
    fun `a gone event before its deadline is disbelieved`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply { answer = EventLookup.NotFound }
        w.completion().finish()
        assertFalse(w.config.config.value?.deleted ?: true)
        assertEquals(0, w.transfersStopped)
    }

    @Test
    fun `a record of another event’s manifest is no settled share here`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply {
            manifestRecord.saveLastUploaded(manifest(final = true).copy(eventId = "OTHER"))
        }
        w.completion().finish()
        assertEquals(1, w.finalPublishes, "a share not settled for this event is settled once more")
    }

    @Test
    fun `an unreadable manifest record settles the share once more`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply {
            manifestRecord.saveLastUploaded(UploadedManifest("E", 1, "not json"))
        }
        w.completion().finish()
        assertEquals(1, w.finalPublishes)
    }

    @Test
    fun `a failing download answer reports nothing and keeps the member`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z")
        assertEquals(
            CompletionOutcome.WAITING,
            w.completion(receivedPosition = { error("the union read failed") }).finish(),
        )
        assertEquals(emptyList(), w.reports)
        assertEquals("E", w.config.config.value?.eventId)
    }

    // ---- the bounded read of the event's state (decision record `changes/timely-background-receiving`, D5) ----------

    @Test
    fun `a bounded wake within the hour of any read of the event reads none and reports nothing`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z")
        w.completion().finish(bounded = false) // a push, an opening or a join: it reads, and stamps
        w.clock = w.clock + 59.minutes
        w.position = 9
        assertEquals(CompletionOutcome.WAITING, w.completion().finish(bounded = true))
        assertEquals(1, w.fetches, "the bounded wake read nothing")
        w.clock = w.clock + 1.minutes
        w.completion().finish(bounded = true)
        assertEquals(2, w.fetches, "an hour after the last read it reads again")
    }

    @Test
    fun `an unbounded step reads the event however recently it was read`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z")
        w.completion().finish(bounded = false)
        w.completion().finish(bounded = false)
        assertEquals(2, w.fetches)
    }

    @Test
    fun `before the end no bound is consulted and nothing is read`() = runTest {
        val w = World(now = "2026-07-01T00:00:00Z")
        assertEquals(CompletionOutcome.NOT_ENDED, w.completion().finish(bounded = true))
        assertEquals(0, w.fetches)
        assertTrue(w.checks.due(EventCheck.CLOSE, joined.eventId), "and no time was stamped")
    }

    // ---- every doubt resolves toward staying joined, and the step never throws ------------------------------------

    @Test
    fun `a device that never recorded a published manifest settles its share once more`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z", recorded = null)
        w.completion().finish()
        assertEquals(1, w.finalPublishes)
        assertEquals("E", w.config.config.value?.eventId)
    }

    @Test
    fun `a failing settle still reads the event and reports`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply {
            manifestRecord.saveLastUploaded(manifest(final = false))
            publishFails = true
        }
        assertEquals(CompletionOutcome.REPORTED, w.completion().finish())
        assertEquals(1, w.fetches, "the failed settle costs the wake nothing else")
    }

    @Test
    fun `a failing settle never stops a completed event from being marked deleted`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z").apply {
            manifestRecord.saveLastUploaded(manifest(final = false))
            publishFails = true
            answer = details(completed = true)
        }
        assertEquals(CompletionOutcome.DELETED, w.completion().finish())
        assertEquals(true, w.config.config.value?.deleted)
    }

    @Test
    fun `a pending-leave record that cannot be read does not stop the end-of-wake step`() = runTest {
        val locked = SharedStore().apply { membershipUnreadable() }.db
        val w = World(now = "2026-07-14T00:00:00Z", leaves = locked)
        assertEquals(CompletionOutcome.REPORTED, w.completion().finish())
    }

    // ---- the end of a whole-pass wake: the bounded photo check, then the step (`changes/timely-background-receiving`, D4) ----

    @Test
    fun `a wake that checks photos runs the check for the joined event and then the step`() = runTest {
        val w = World(now = "2026-07-12T00:00:00Z")
        val checked = mutableListOf<String>()
        val outcome = w.completion().endOfWake(checksPhotos = true, bounded = false) { checked += it }
        assertEquals(listOf("E"), checked)
        assertEquals(CompletionOutcome.NOT_ENDED, outcome)
    }

    @Test
    fun `a wake that does not check photos runs no check`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z")
        val checked = mutableListOf<String>()
        val outcome = w.completion().endOfWake(checksPhotos = false, bounded = false) { checked += it }
        assertEquals(emptyList(), checked)
        assertEquals(CompletionOutcome.REPORTED, outcome)
    }

    @Test
    fun `an unjoined wake runs no photo check`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z", initial = null)
        val checked = mutableListOf<String>()
        val outcome = w.completion().endOfWake(checksPhotos = true, bounded = false) { checked += it }
        assertEquals(emptyList(), checked)
        assertEquals(CompletionOutcome.NOT_JOINED, outcome)
    }

    @Test
    fun `a deleted event runs no photo check`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z", initial = joined.copy(deleted = true))
        val checked = mutableListOf<String>()
        w.completion().endOfWake(checksPhotos = true, bounded = false) { checked += it }
        assertEquals(emptyList(), checked)
    }

    @Test
    fun `a failing photo check is logged and the step still runs`() = runTest {
        val w = World(now = "2026-07-14T00:00:00Z")
        val recorder = CapturingLogWriter()
        val outcome = w.completion(recorder.logger())
            .endOfWake(checksPhotos = true, bounded = false) { error("the union read failed") }
        assertEquals(CompletionOutcome.REPORTED, outcome)
        assertEquals(1, w.fetches)
        assertTrue(recorder.lines.any { (severity, line) -> severity == Severity.Warn && "photo check failed" in line })
    }
}
