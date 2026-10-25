package app.snapsync.feature.download

import app.snapsync.data.membership.EventCheckRecord
import app.snapsync.data.time.WallClock
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.feature.support.InMemoryAssetPresence
import app.snapsync.feature.support.RecordingFiles
import app.snapsync.feature.support.SharedStore
import app.snapsync.feature.support.configService
import app.snapsync.feature.support.downloadJobs
import app.snapsync.feature.support.testIdentity
import app.snapsync.feature.support.testRuntime
import app.snapsync.feature.support.testShared
import app.snapsync.format.TimestampText
import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.model.EventConfig
import app.snapsync.model.UnionAsset
import app.snapsync.model.UnionPage
import app.snapsync.model.UnionTrigger
import app.snapsync.ports.GalleryImport
import app.snapsync.ports.GalleryImportRequest
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.PlatformAssetId
import app.snapsync.services.backend.EventUnionSource
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.gallery.GalleryImporter
import app.snapsync.services.staging.StagingService
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The end of a wake for what this device received (capability `event-lifetime`: a member is done once it has received
 * everything, and the app never leaves an event on its own): once the event has ended, the union position up to which
 * this device holds every photo of the others is reported — over the REAL membership and download controller, with the
 * union and the backend's answer played here.
 */
class ReceivedReportTest {

    private val joined = EventConfig(
        eventId = testEventId("E"),
        name = "Party",
        minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-06T12:00:00Z")),
        startsAt = EventStart(TimestampText.parse("2026-07-06T12:00:00Z")),
        endsAt = EventEnd(TimestampText.parse("2026-07-13T12:00:00Z")),
        maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-07-13T12:00:00Z")),
        deletesAt = DeletesAt(TimestampText.parse("2026-08-05T12:00:00Z")),
    )

    /** The union as the backend serves it at [position] — holding another member's photo while [missing]. */
    private class Union : EventUnionSource {
        var position = 4L
        var missing = false
        var fails = false
        var reads = 0

        /** Runs while the union is read — where a member's own join or leave lands when it lands during that read. */
        var during: suspend () -> Unit = {}

        override suspend fun union(eventId: EventId, cursor: Long?, trigger: UnionTrigger): Result<UnionPage> {
            reads++
            during()
            if (fails) return Result.failure(IllegalStateException("the union read failed"))
            val foreign = UnionAsset(testDeviceId("OTHER"), testAssetId("A_L0_1"), "2026-07-10T00:00:00Z", emptyList())
            return Result.success(UnionPage(if (missing) listOf(foreign) else emptyList(), position))
        }
    }

    private class NoImports : GalleryImport {
        override suspend fun import(request: GalleryImportRequest) = ImportOutcome.Imported(PlatformAssetId("LOCAL"))
    }

    /** One scenario: the membership at [now] over [store], the union, and what the backend was told. */
    private inner class World(scope: TestScope, now: String, membership: EventConfig?) {
        val store = SharedStore()
        val config = configService(membership, store, fixedClock(Instant.parse(now)))
        val union = Union()
        var accepted = true
        val reports = mutableListOf<Pair<EventId, Long>>()
        private val staging = StagingService(RecordingFiles())
        private val downloads = DownloadController(
            scope.testRuntime(), union, DownloadService(inMemoryDatabases()), downloadJobs(staging = staging),
            GalleryImporter(NoImports(), staging), InMemoryAssetPresence(),
            eventAlbum = { null },
            onImportedIntoAlbum = {},
            stagedBytes = staging,
            identity = testIdentity("ME"),
            arm = DownloadArm(enabled = { true }, keyHeld = { true }),
            checks = EventCheckRecord(testShared()),
            clock = WallClock(fixedClock(Instant.parse(now))),
            readyToImport = { true },
        )
        val report = ReceivedReport(config, downloads, { eventId, position ->
            reports += eventId to position
            accepted
        })

        /** [next] written as another trigger's join or leave writes it, and re-read. */
        fun becomes(next: EventConfig?) {
            store.persist(next)
            config.reload()
        }
    }

    private fun TestScope.world(now: String = "2026-07-14T00:00:00Z", membership: EventConfig? = joined) =
        World(this, now, membership)

    @Test
    fun `before the event has ended nothing is read or reported`() = runTest {
        val w = world(now = "2026-07-12T00:00:00Z")
        w.report.report()
        assertEquals(0, w.union.reads)
        assertEquals(emptyList(), w.reports)
    }

    @Test
    fun `a device holding everything reports its position and stays in the event`() = runTest {
        val w = world()
        w.report.report()
        assertEquals(listOf(testEventId("E") to 4L), w.reports)
        assertEquals(testEventId("E"), w.config.config.value?.eventId, "the app never leaves an event on its own")
    }

    @Test
    fun `a position already reported is not sent again and a higher one is`() = runTest {
        val w = world()
        w.report.report()
        w.report.report()
        w.union.position = 9
        w.report.report()
        assertEquals(listOf(testEventId("E") to 4L, testEventId("E") to 9L), w.reports)
    }

    @Test
    fun `a report the backend did not record is sent again by the next wake`() = runTest {
        val w = world().apply { accepted = false }
        w.report.report()
        w.accepted = true
        w.report.report()
        assertEquals(listOf(testEventId("E") to 4L, testEventId("E") to 4L), w.reports)
    }

    @Test
    fun `a device still missing another member's photo reports nothing`() = runTest {
        val w = world().apply { union.missing = true }
        w.report.report()
        assertEquals(emptyList(), w.reports)
    }

    @Test
    fun `a union that cannot be read reports nothing`() = runTest {
        val w = world().apply { union.fails = true }
        w.report.report()
        assertEquals(emptyList(), w.reports)
    }

    @Test
    fun `a join landing while the union is read is reported for nobody`() = runTest {
        val w = world()
        w.union.during = { w.becomes(joined.copy(eventId = testEventId("F"), name = "Next")) }
        w.report.report()
        assertEquals(emptyList(), w.reports)
    }

    @Test
    fun `a leave landing while the union is read is reported for nobody`() = runTest {
        val w = world()
        w.union.during = { w.becomes(null) }
        w.report.report()
        assertEquals(emptyList(), w.reports)
    }

    @Test
    fun `no membership and a deleted event read nothing`() = runTest {
        val none = world(membership = null)
        none.report.report()
        val deleted = world(membership = joined.copy(deleted = true))
        deleted.report.report()
        assertEquals(0, none.union.reads + deleted.union.reads)
    }
}
