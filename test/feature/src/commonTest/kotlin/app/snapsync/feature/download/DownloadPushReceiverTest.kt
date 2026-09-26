package app.snapsync.feature.download

import app.snapsync.fake.inMemoryDatabases
import app.snapsync.feature.support.InMemoryAssetPresence
import app.snapsync.feature.support.RecordingFiles
import app.snapsync.feature.support.configService
import app.snapsync.feature.support.downloadJobs
import app.snapsync.feature.support.membershipUnreadable
import app.snapsync.feature.support.testClock
import app.snapsync.model.AssetId
import app.snapsync.model.EventConfig
import app.snapsync.model.ImportRequest
import app.snapsync.model.ImportResult
import app.snapsync.model.UnionAsset
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.ports.GalleryImport
import app.snapsync.services.backend.EventUnionSource
import app.snapsync.services.config.ConfigService
import app.snapsync.services.downloads.DownloadService
import app.snapsync.services.gallery.GalleryImporter
import app.snapsync.services.staging.StagingService
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadPushReceiverTest {

    private val myDevice = "DEVICE-ME"
    private val eventA = "7a3f9c21-0000-4000-8000-00000000000a"
    private val eventB = "7a3f9c21-0000-4000-8000-00000000000b"

    /** Records the event ids reconcile asked the union for — the observable proof reconcile ran. */
    private class RecordingUnion : EventUnionSource {
        val requested = mutableListOf<String>()
        override suspend fun union(eventId: String): Result<List<UnionAsset>> {
            requested += eventId
            return Result.success(emptyList())
        }
    }

    private class NoopImporter : GalleryImport {
        override suspend fun import(request: ImportRequest) =
            ImportResult.Imported(AssetId("LOCAL"))
    }

    private fun TestScope.controller(union: RecordingUnion): DownloadController {
        val staging = StagingService(RecordingFiles())
        return DownloadController(
            union, DownloadService(inMemoryDatabases()), downloadJobs(backgroundScope, staging = staging),
            GalleryImporter(NoopImporter(), staging), InMemoryAssetPresence(),
            eventAlbum = { null },
            stagedBytes = staging,
            myDeviceId = myDevice,
            // These tests exercise the ACTIVE-EVENT guard, which is orthogonal to the direction gate
            // (capability `receiving-photos`) — so state a downloading membership explicitly. The gate no
            // longer defaults: a permissive default is what let "no membership" mean "download freely".
            downloadEnabled = { true },
        )
    }

    private fun TestScope.receiver(union: RecordingUnion, active: String?): DownloadPushReceiver =
        DownloadPushReceiver(configSource = membership(active), controller = controller(union))

    @Test
    fun push_for_the_active_event_reconciles_that_event() = runTest {
        val union = RecordingUnion()
        receiver(union, active = eventA).onSilentPush(eventA)
        // The suspend returned only after reconcile's union read completed (the await contract).
        assertEquals(listOf(eventA), union.requested)
    }

    @Test
    fun push_for_a_non_active_event_is_a_noop() = runTest {
        val union = RecordingUnion()
        receiver(union, active = eventA).onSilentPush(eventB) // e.g. a locally-left event still pushing
        assertTrue(union.requested.isEmpty(), "a push for a non-active event must not reconcile")
    }

    @Test
    fun push_while_the_membership_is_unreadable_is_deferred() = runTest {
        // A locked device: the push cannot be matched to an event we cannot read, so nothing is reconciled —
        // and the reason is logged as "unreadable", not as "not joined".
        val union = RecordingUnion()
        val files = RecordingFiles().apply { membershipUnreadable() }
        val receiver = DownloadPushReceiver(
            configSource = ConfigService(files, testClock()),
            controller = controller(union),
        )
        receiver.onSilentPush(eventA)
        assertTrue(union.requested.isEmpty(), "an unreadable membership must not reconcile")
    }

    @Test
    fun push_with_no_active_event_is_a_noop() = runTest {
        val union = RecordingUnion()
        receiver(union, active = null).onSilentPush(eventA)
        assertTrue(union.requested.isEmpty(), "a push with no event configured must not reconcile")
    }
}

/** The real membership service, holding [eventId]'s membership — or none. */
private fun membership(eventId: String?): ConfigService = configService(
    eventId?.let {
        EventConfig(it, "E", captureCutoff("2026-01-01T00:00:00Z"), maxPhotoDate = captureCeiling("2099-01-01T00:00:00Z"))
    },
)
