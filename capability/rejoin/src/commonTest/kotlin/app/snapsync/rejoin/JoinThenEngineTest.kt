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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

/**
 * The core no-re-upload guarantee end-to-end across the join → engine boundary: a row seeded by the
 * join's atomic `resetTo` (straight from the complete-asset listing) is read back through the real
 * [SyncEngine] as `AlreadyUploaded` (no job), while a resource absent from the listing — a
 * partially-stored or never-uploaded asset, cursor cleared — still uploads.
 */
class JoinThenEngineTest {

    private class FakeConfig(eventId: String) : ConfigSource {
        override val config: StateFlow<EventConfigPayload?> = MutableStateFlow(EventConfigPayload(eventId))
    }

    private object FakeProvider : UploadRequestProvider {
        override suspend fun provide(resource: Resource): UploadRequest =
            UploadRequest("https://edge.example/file/${resource.filename}", emptyMap(), resource)
    }

    private fun res(filename: String, assetId: String) =
        Resource(filename, assetId, "image/heic", emptyMap(), Unit)

    private fun join(ledger: FakeLedgerBackend, listed: List<RemoteAsset>) = JoinEvent(
        files = FakeFilesOnce(listed),
        ledger = ledger,
        config = FakeConfig("E1"),
        status = MutableEventStatusSource(),
        clearDiscoveryCursor = {},
    )

    @Test
    fun `a resource seeded by the join is skipped by the engine`() = runTest {
        val ledger = FakeLedgerBackend()
        join(ledger, listOf(RemoteAsset("A", listOf(RemoteResource("A-primary.heic"))))).ensureJoined()

        val engine = SyncEngine(FakeProvider, LedgerWriter(ledger))
        assertEquals(
            SyncDecision.AlreadyUploaded,
            engine.handle(SyncEvent.ResourceChanged(res("A-primary.heic", "A"))),
        )
    }

    @Test
    fun `a resource absent from the listing still uploads`() = runTest {
        val ledger = FakeLedgerBackend()
        // Only A is listed complete; B is not in the listing → not seeded → must upload.
        join(ledger, listOf(RemoteAsset("A", listOf(RemoteResource("A-primary.heic"))))).ensureJoined()

        val engine = SyncEngine(FakeProvider, LedgerWriter(ledger))
        val decision = engine.handle(SyncEvent.ResourceChanged(res("B-primary.heic", "B")))
        assertTrue(decision is SyncDecision.Upload, "un-seeded resource must upload, got $decision")
    }

    private class FakeFilesOnce(private val assets: List<RemoteAsset>) : EventFilesSource {
        override suspend fun list(eventId: String): Result<List<RemoteAsset>> = Result.success(assets)
    }
}
