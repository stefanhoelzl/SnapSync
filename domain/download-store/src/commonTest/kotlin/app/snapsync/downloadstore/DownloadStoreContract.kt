package app.snapsync.downloadstore

import app.snapsync.ports.AssetRef
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.PlannedResource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Shared contract for every [DownloadStore] impl — run on both the in-memory fake (commonTest, JVM +
 * simulator) and the SQLDelight store (jvmTest over a JDBC driver). Exercises the download→stage→
 * import lifecycle, the suppression projection, idempotency, and leave/switch pruning.
 */
abstract class DownloadStoreContract {

    abstract fun createStore(): DownloadStore

    private val ref = AssetRef("DEVICE-A", "ASSET-Q")
    private fun resources() = listOf(
        PlannedResource("ASSET-Q-primary.heic", "https://e/primary", "primary", "image/heic", "IMG.HEIC"),
        PlannedResource("ASSET-Q-live.mov", "https://e/live", "live", "video/quicktime", "IMG.MOV"),
    )

    @Test
    fun plan_then_pending_lists_every_resource() = runTest {
        val s = createStore()
        s.plan(ref, "2026-06-30T10:00:00Z", resources())
        assertEquals(2, s.pendingDownloads().size)
        assertFalse(s.isImported(ref))
        assertTrue(s.importableAssets().isEmpty()) // nothing staged yet
    }

    @Test
    fun importable_only_when_all_resources_staged() = runTest {
        val s = createStore()
        s.plan(ref, "2026-06-30T10:00:00Z", resources())
        s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        assertTrue(s.importableAssets().isEmpty()) // live still missing
        s.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")
        assertEquals(listOf(ref), s.importableAssets().map { it.ref })
        assertEquals(2, s.stagedResources(ref).size)
        assertEquals(setOf("primary", "live"), s.stagedResources(ref).map { it.role }.toSet())
    }

    @Test
    fun in_flight_counts_assets_with_an_enqueued_not_yet_staged_resource() = runTest {
        val s = createStore()
        s.plan(ref, "2026-06-30T10:00:00Z", resources())
        assertEquals(0, s.inFlightCount()) // planned, but nothing sent to the OS yet

        s.markEnqueued(ref, "ASSET-Q-primary.heic")
        assertEquals(1, s.inFlightCount()) // a resource sent → the asset is in flight

        s.markEnqueued(ref, "ASSET-Q-live.mov")
        assertEquals(1, s.inFlightCount()) // asset-counted, not one per resource

        s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        assertEquals(1, s.inFlightCount()) // live still enqueued-not-staged

        s.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")
        assertEquals(0, s.inFlightCount()) // every resource staged → no longer in flight
    }

    @Test
    fun import_records_suppression_and_idempotency() = runTest {
        val s = createStore()
        s.plan(ref, "2026-06-30T10:00:00Z", resources())
        s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        s.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")
        s.markImported(ref, "LOCAL-NEW/L0/001")

        assertTrue(s.isImported(ref))
        assertEquals(setOf("LOCAL-NEW/L0/001"), s.suppressedLocalIds())
        assertEquals(1, s.importedCount())
        assertTrue(s.importableAssets().isEmpty()) // imported, no longer importable
        assertTrue(s.pendingDownloads().isEmpty()) // imported asset's resources are not re-queued
    }

    @Test
    fun replan_refreshes_url_of_unstaged_resources_only() = runTest {
        val s = createStore()
        s.plan(ref, "2026-06-30T10:00:00Z", resources())
        s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic") // primary now staged, live pending

        // A later union read re-plans with freshly presigned (rotated) urls for both resources.
        s.plan(
            ref,
            "2026-06-30T10:00:00Z",
            listOf(
                PlannedResource("ASSET-Q-primary.heic", "https://e/primary?sig=NEW", "primary", "image/heic", "IMG.HEIC"),
                PlannedResource("ASSET-Q-live.mov", "https://e/live?sig=NEW", "live", "video/quicktime", "IMG.MOV"),
            ),
        )

        // The not-yet-staged resource (live) picks up the fresh url; the staged one is not re-queued.
        val pending = s.pendingDownloads()
        assertEquals(1, pending.size)
        assertEquals("ASSET-Q-live.mov", pending.single().resource.resourceKey)
        assertEquals("https://e/live?sig=NEW", pending.single().resource.url)

        // The staged resource (primary) keeps its staging untouched (no re-download).
        assertEquals(listOf("ASSET-Q-primary.heic"), s.stagedResources(ref).map { it.resourceKey })
        assertEquals("/stage/primary.heic", s.stagedResources(ref).single().stagedPath)
    }

    @Test
    fun plan_never_downgrades_an_imported_asset() = runTest {
        val s = createStore()
        s.plan(ref, "2026-06-30T10:00:00Z", resources())
        s.markStaged(ref, "ASSET-Q-primary.heic", "/p")
        s.markStaged(ref, "ASSET-Q-live.mov", "/l")
        s.markImported(ref, "LOCAL-NEW")
        s.plan(ref, "2026-06-30T10:00:00Z", resources()) // a later union read re-offers it
        assertTrue(s.isImported(ref))
        assertEquals(1, s.importedCount())
    }

    @Test
    fun prune_drops_non_terminal_keeps_imported() = runTest {
        val s = createStore()
        val imported = AssetRef("DEVICE-A", "DONE")
        s.plan(imported, "2026-01-01T00:00:00Z", listOf(PlannedResource("DONE-primary.heic", "u", "primary", "image/heic", "D.HEIC")))
        s.markStaged(imported, "DONE-primary.heic", "/d")
        s.markImported(imported, "LOCAL-DONE")
        s.plan(ref, "2026-06-30T10:00:00Z", resources()) // a fresh, non-terminal asset

        s.pruneNonTerminal()

        assertTrue(s.isImported(imported)) // terminal row preserved (delete-proof, cross-event dedup)
        assertEquals(setOf("LOCAL-DONE"), s.suppressedLocalIds())
        assertFalse(s.isImported(ref))
        assertTrue(s.pendingDownloads().isEmpty()) // the non-terminal asset's resources were dropped
    }
}

/** The in-memory fake satisfies the contract (also the harness/integration impl). */
class InMemoryDownloadStoreTest : DownloadStoreContract() {
    override fun createStore(): DownloadStore = InMemoryDownloadStore()
}
