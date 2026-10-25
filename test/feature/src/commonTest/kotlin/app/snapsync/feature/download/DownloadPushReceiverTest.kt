package app.snapsync.feature.download

import app.snapsync.data.membership.EventCheckRecord
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.time.WallClock
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.feature.support.InMemoryAssetPresence
import app.snapsync.feature.support.RecordingFiles
import app.snapsync.feature.support.SharedStore
import app.snapsync.feature.support.configService
import app.snapsync.feature.support.downloadJobs
import app.snapsync.feature.support.testClock
import app.snapsync.feature.support.testIdentity
import app.snapsync.feature.support.testRuntime
import app.snapsync.feature.support.testShared
import app.snapsync.format.TimestampText
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.model.EventConfig
import app.snapsync.model.UnionPage
import app.snapsync.model.UnionTrigger
import app.snapsync.ports.GalleryImport
import app.snapsync.ports.GalleryImportRequest
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.PlatformAssetId
import app.snapsync.services.backend.EventUnionSource
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.gallery.GalleryImporter
import app.snapsync.services.membership.MembershipProjection
import app.snapsync.services.staging.StagingService
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadPushReceiverTest {

    private val myDevice = "DEVICE-ME"
    private val eventA = testEventId("7a3f9c21-0000-4000-8000-00000000000a")
    private val eventB = testEventId("7a3f9c21-0000-4000-8000-00000000000b")

    /**
     * Records the event ids reconcile asked the union for — the observable proof reconcile ran — and from where; it
     * answers an empty union at [position].
     */
    private class RecordingUnion(private val position: Long = 0) : EventUnionSource {
        val requested = mutableListOf<EventId>()
        val from = mutableListOf<Long?>()
        override suspend fun union(eventId: EventId, cursor: Long?, trigger: UnionTrigger): Result<UnionPage> {
            requested += eventId
            from += cursor
            return Result.success(UnionPage(emptyList(), position))
        }
    }

    private class NoopImporter : GalleryImport {
        override suspend fun import(request: GalleryImportRequest) =
            ImportOutcome.Imported(PlatformAssetId("LOCAL"))
    }

    private fun TestScope.controller(union: RecordingUnion): DownloadController {
        val staging = StagingService(RecordingFiles())
        return DownloadController(
            testRuntime(),
            union,
            DownloadService(inMemoryDatabases()),
            downloadJobs(staging = staging),
            GalleryImporter(NoopImporter(), staging),
            InMemoryAssetPresence(),
            eventAlbum = { null },
            onImportedIntoAlbum = {},
            stagedBytes = staging,
            identity = testIdentity(myDevice),
            // These tests exercise the ACTIVE-EVENT guard, which is orthogonal to the direction gate
            // (capability `receiving-photos`) — so state a downloading membership explicitly. The gate no
            // longer defaults: a permissive default is what let "no membership" mean "download freely".
            arm = DownloadArm(enabled = { true }, keyHeld = { true }),
            checks = EventCheckRecord(testShared()),
            clock = WallClock(testClock()),
            readyToImport = { true },
        )
    }

    private fun TestScope.receiver(union: RecordingUnion, active: String?): DownloadPushReceiver =
        DownloadPushReceiver(configSource = membership(active), controller = controller(union))

    @Test
    fun push_for_the_active_event_reconciles_that_event() = runTest {
        val union = RecordingUnion()
        receiver(union, active = eventA.value).onSilentPush(eventA)
        // The suspend returned only after reconcile's union read completed (the await contract).
        assertEquals(listOf(eventA), union.requested)
    }

    @Test
    fun push_for_a_non_active_event_is_a_noop() = runTest {
        val union = RecordingUnion()
        receiver(union, active = eventA.value).onSilentPush(eventB) // e.g. a locally-left event still pushing
        assertTrue(union.requested.isEmpty(), "a push for a non-active event must not reconcile")
    }

    @Test
    fun push_while_the_membership_is_unreadable_is_deferred() = runTest {
        // A locked device: the push cannot be matched to an event we cannot read, so nothing is reconciled —
        // and the reason is logged as "unreadable", not as "not joined".
        val union = RecordingUnion()
        val store = SharedStore().apply { membershipUnreadable() }
        val receiver = DownloadPushReceiver(
            configSource = MembershipProjection(MembershipRepository(store.db), testClock()),
            controller = controller(union),
        )
        receiver.onSilentPush(eventA)
        assertTrue(union.requested.isEmpty(), "an unreadable membership must not reconcile")
    }

    @Test
    fun a_push_announcing_a_position_already_read_reads_nothing() = runTest {
        // Decision record `changes/incremental-union`, D6: a burst of pushes after one read costs no more reads.
        val union = RecordingUnion(position = 9)
        val receiver = receiver(union, active = eventA.value)
        receiver.onSilentPush(
            testEventId(eventA.value),
            announced = 5,
        ) // no position stored yet: reads the whole union, stores 9
        receiver.onSilentPush(testEventId(eventA.value), announced = 9)
        receiver.onSilentPush(testEventId(eventA.value), announced = 7)
        assertEquals(listOf(eventA), union.requested, "only the first push read")
        receiver.onSilentPush(testEventId(eventA.value), announced = 10)
        assertEquals(listOf<Long?>(null, 9), union.from, "a later announcement reads from the stored position")
    }

    @Test
    fun a_push_is_read_from_its_payload_and_one_naming_no_event_receives_nothing() = runTest {
        val union = RecordingUnion()
        val receiver = receiver(union, active = eventA.value)
        receiver.onSilentPush(mapOf<Any?, Any?>("aps" to mapOf("content-available" to 1)))
        assertTrue(union.requested.isEmpty(), "a payload with no event id runs nothing")
        receiver.onSilentPush(mapOf<Any?, Any?>("eventId" to eventA.value))
        assertEquals(listOf(eventA), union.requested)
    }

    @Test
    fun the_joined_event_is_reconciled_and_nothing_while_not_joined() = runTest {
        val union = RecordingUnion()
        val controller = controller(union)
        controller.reconcileJoined(null, app.snapsync.model.UnionTrigger.FOREGROUND)
        assertTrue(union.requested.isEmpty())
        controller.reconcileJoined(testEventId(eventA.value), app.snapsync.model.UnionTrigger.FOREGROUND)
        assertEquals(listOf(eventA), union.requested)
    }

    @Test
    fun push_with_no_active_event_is_a_noop() = runTest {
        val union = RecordingUnion()
        receiver(union, active = null).onSilentPush(eventA)
        assertTrue(union.requested.isEmpty(), "a push with no event configured must not reconcile")
    }
}

/** The real membership service, holding [eventId]'s membership — or none. */
private fun membership(eventId: String?): MembershipProjection = configService(
    eventId?.let {
        EventConfig(
            testEventId(it),
            "E",
            CaptureCutoff(TimestampText.parse("2026-01-01T00:00:00Z")),
            maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-01-31T00:00:00Z")),
            endsAt = EventEnd(TimestampText.parse("2099-12-31T00:00:00Z")),
            deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
        )
    },
)
