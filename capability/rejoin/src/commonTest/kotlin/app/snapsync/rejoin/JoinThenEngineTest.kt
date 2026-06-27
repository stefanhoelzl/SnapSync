package app.snapsync.rejoin

import app.snapsync.config.ConfigSource
import app.snapsync.config.EventConfigPayload
import app.snapsync.engine.LedgerWriter
import app.snapsync.engine.Resource
import app.snapsync.engine.SyncDecision
import app.snapsync.engine.SyncEngine
import app.snapsync.engine.SyncEvent
import app.snapsync.engine.UploadRequest
import app.snapsync.engine.UploadRequestProvider
import app.snapsync.eventstatus.MutableEventStatusSource
import app.snapsync.gallery.InMemoryGalleryResourceEnumerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

/**
 * The core no-re-upload guarantee end-to-end across the join → engine boundary: a row seeded by the
 * join's atomic `resetTo` is read back through the real [SyncEngine] as `AlreadyUploaded` (no job),
 * while an un-seeded resource (the migration-shaped case, cursor cleared) still uploads.
 */
class JoinThenEngineTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_000)
    }

    private class FakeConfig(eventId: String) : ConfigSource {
        override val config: StateFlow<EventConfigPayload?> = MutableStateFlow(EventConfigPayload(eventId))
    }

    private object FakeProvider : UploadRequestProvider {
        override suspend fun provide(resource: Resource): UploadRequest =
            UploadRequest("https://edge.example/file/${resource.filename}", emptyMap(), resource)
    }

    private fun res(filename: String, assetId: String, version: String) =
        Resource(filename, assetId, "image/heic", version, emptyMap(), Unit)

    @Test
    fun `a photo seeded by the join is skipped by the engine`() = runTest {
        val ledger = FakeLedgerBackend()
        val joined = res("A-ios.photo.heic", "A", "v1")
        JoinEvent(
            files = FakeFilesOnce(listOf(RemoteFile("A-ios.photo.heic", "2026-06-20T10:31:00Z"))),
            enumerator = InMemoryGalleryResourceEnumerator(listOf(joined)),
            ledger = ledger,
            config = FakeConfig("E1"),
            status = MutableEventStatusSource(),
            clearDiscoveryCursor = {},
            clock = fixedClock,
        ).ensureJoined()

        val engine = SyncEngine(FakeProvider, LedgerWriter(ledger))
        assertEquals(SyncDecision.AlreadyUploaded, engine.handle(SyncEvent.ResourceChanged(joined)))
    }

    @Test
    fun `an un-seeded photo still uploads`() = runTest {
        val ledger = FakeLedgerBackend()
        // Only A is stored remotely; B exists locally but is not in the manifest → not seeded.
        JoinEvent(
            files = FakeFilesOnce(listOf(RemoteFile("A-ios.photo.heic", null))),
            enumerator = InMemoryGalleryResourceEnumerator(
                listOf(res("A-ios.photo.heic", "A", "v1"), res("B-ios.photo.heic", "B", "v2")),
            ),
            ledger = ledger,
            config = FakeConfig("E1"),
            status = MutableEventStatusSource(),
            clearDiscoveryCursor = {},
            clock = fixedClock,
        ).ensureJoined()

        val engine = SyncEngine(FakeProvider, LedgerWriter(ledger))
        val decision = engine.handle(SyncEvent.ResourceChanged(res("B-ios.photo.heic", "B", "v2")))
        assertTrue(decision is SyncDecision.Upload, "un-seeded resource must upload, got $decision")
    }

    private class FakeFilesOnce(private val files: List<RemoteFile>) : EventFilesSource {
        override suspend fun list(eventId: String): Result<List<RemoteFile>> = Result.success(files)
    }
}
