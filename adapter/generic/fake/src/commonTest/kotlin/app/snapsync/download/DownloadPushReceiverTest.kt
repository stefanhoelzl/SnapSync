package app.snapsync.download

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.PermissionStatus
import app.snapsync.model.EventConfig
import app.snapsync.ports.PhotoAccessStatusSource
import app.snapsync.ports.ConfigSource
import app.snapsync.model.MembershipRead
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.download.DownloadPushReceiver
import app.snapsync.ports.EventUnionSource
import app.snapsync.model.ImportResult
import app.snapsync.ports.PhotoDownloadJobs
import app.snapsync.ports.PhotoLibraryImporter
import app.snapsync.ports.UnionAsset

import app.snapsync.model.AssetRef
import app.snapsync.fake.InMemoryAssetPresence
import app.snapsync.fake.InMemoryDownloadStore
import app.snapsync.model.PendingDownload
import app.snapsync.model.StagedResource
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

    private class NoopJobs : PhotoDownloadJobs {
        override suspend fun enqueue(downloads: List<PendingDownload>) {}
        override suspend fun cancelAll() {}
    }

    private class NoopImporter : PhotoLibraryImporter {
        override suspend fun import(ref: AssetRef, resources: List<StagedResource>, creationDate: String) =
            ImportResult.Imported("LOCAL")
    }

    private fun controller(union: RecordingUnion) = DownloadController(
        union, InMemoryDownloadStore(), NoopJobs(), NoopImporter(), InMemoryAssetPresence(),
        myDeviceId = myDevice,
        // These tests exercise the ACTIVE-EVENT guard, which is orthogonal to the direction gate
        // (capability `receiving-photos`) — so state a downloading membership explicitly. The gate no
        // longer defaults: a permissive default is what let "no membership" mean "download freely".
        downloadEnabled = { true },
    )

    private fun receiver(union: RecordingUnion, active: String?): DownloadPushReceiver =
        DownloadPushReceiver(configSource = liveMembership { active }, controller = controller(union))

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
        val receiver = DownloadPushReceiver(
            configSource = object : ConfigSource {
                override val config: StateFlow<EventConfig?> = MutableStateFlow(null)
                override val membership: MembershipRead = MembershipRead.Unreadable
            },
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

/** A membership fake whose answer is read at every access, so a test's `var` drives it live. */
private fun liveMembership(eventId: () -> String?): ConfigSource = object : ConfigSource {
    override val config: StateFlow<EventConfig?>
        get() = MutableStateFlow(
            eventId()?.let {
                EventConfig(it, "E", captureCutoff("2026-01-01T00:00:00Z"), maxPhotoDate = captureCeiling("2099-01-01T00:00:00Z"))
            },
        )
}

