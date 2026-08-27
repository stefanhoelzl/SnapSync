package app.snapsync.feature.upload

import app.snapsync.model.captureCutoff
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.selectionRulesFor
import app.snapsync.model.Resource
import app.snapsync.model.SelectionScope
import app.snapsync.model.UploadRequest
import app.snapsync.ports.BackgroundTransfer
import app.snapsync.ports.CreateResult
import app.snapsync.ports.Discovery
import app.snapsync.ports.PlatformUploadJob
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The read-discipline gate (capability `limited-photo-access`): under a [SelectionScope.Scoped],
 * discovery consumes the snapshot with NO platform read; under [SelectionScope.Unrestricted] it
 * delegates unchanged. The walk cursor is preserved across the scoped period, and a snapshot is
 * never a full enumeration (it must not drive ledger pruning).
 */
/** An admitting policy over [cutoff] — the shape the cycle hands the transfer. */
private suspend fun admitting(cutoff: String): SelectionPolicy =
    SelectionPolicy(selectionRulesFor(includesUpload = true, cutoff = captureCutoff(cutoff), ceiling = null, suppressedAssetIds = { emptySet() }, albumExcludedAssetIds = { emptySet() }))

class SelectionScopedTransferTest {

    private class RecordingDelegate : BackgroundTransfer {
        var discoverCalls = 0
        var resolveCalls = 0
        override suspend fun fetchRetryJobs(): List<PlatformUploadJob> = emptyList()
        override suspend fun drainTerminals(): List<PlatformUploadJob> = emptyList()
        override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) = Unit
        override suspend fun discoverResources(sinceToken: ByteArray?, policy: SelectionPolicy): Discovery {
            discoverCalls++
            return Discovery(emptyList(), byteArrayOf(7))
        }
        override suspend fun resourcesFor(keys: Set<String>): List<Resource> {
            resolveCalls++
            return emptyList()
        }
        override suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult =
            CreateResult.CREATED
    }

    private fun resource(name: String) =
        Resource(filename = name, assetId = name, contentType = "image/jpeg", metadata = emptyMap(), data = Unit)

    @Test
    fun unrestricted_delegates_to_the_platform_walk() = runTest {
        val delegate = RecordingDelegate()
        val transfer = SelectionScopedTransfer(delegate) { SelectionScope.Unrestricted }

        val discovery = transfer.discoverResources(byteArrayOf(1), admitting("2026-01-01T00:00:00Z"))

        assertEquals(1, delegate.discoverCalls)
        assertContentEquals(byteArrayOf(7), discovery.nextToken)
    }

    @Test
    fun scoped_returns_the_snapshot_without_any_platform_read() = runTest {
        val delegate = RecordingDelegate()
        val snapshot = listOf(resource("A"), resource("B"))
        val transfer = SelectionScopedTransfer(delegate) { SelectionScope.Scoped(snapshot) }

        val discovery = transfer.discoverResources(byteArrayOf(1, 2), admitting("2026-01-01T00:00:00Z"))

        assertEquals(0, delegate.discoverCalls)
        assertEquals(
            snapshot.map { it.assetId },
            discovery.candidates.map { it.facts.assetId },
            "the snapshot crosses verbatim — wrapped as HELD candidates, nothing re-read",
        )
        // The walk cursor survives the scoped period: a later full-access walk resumes incrementally.
        assertContentEquals(byteArrayOf(1, 2), discovery.nextToken)
        // A snapshot is not the whole-library key-set — it must never drive ledger pruning.
        assertFalse(discovery.fullEnumeration)
        assertTrue(discovery.removedAssetIds.isEmpty())
    }

    @Test
    fun scoped_with_no_prior_cursor_yields_an_empty_token() = runTest {
        val delegate = RecordingDelegate()
        val transfer = SelectionScopedTransfer(delegate) { SelectionScope.Scoped(emptyList()) }

        val discovery = transfer.discoverResources(null, admitting("2026-01-01T00:00:00Z"))

        assertEquals(0, delegate.discoverCalls)
        assertTrue(discovery.candidates.isEmpty())
        assertContentEquals(ByteArray(0), discovery.nextToken)
    }
}
