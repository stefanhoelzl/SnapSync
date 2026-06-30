package app.snapsync.downloadstore

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
        s.plan(ref, resources())
        assertEquals(2, s.pendingDownloads().size)
        assertFalse(s.isImported(ref))
        assertTrue(s.importableAssets().isEmpty()) // nothing staged yet
    }

    @Test
    fun importable_only_when_all_resources_staged() = runTest {
        val s = createStore()
        s.plan(ref, resources())
        s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        assertTrue(s.importableAssets().isEmpty()) // live still missing
        s.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")
        assertEquals(listOf(ref), s.importableAssets())
        assertEquals(2, s.stagedResources(ref).size)
        assertEquals(setOf("primary", "live"), s.stagedResources(ref).map { it.role }.toSet())
    }

    @Test
    fun import_records_suppression_and_idempotency() = runTest {
        val s = createStore()
        s.plan(ref, resources())
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
    fun plan_never_downgrades_an_imported_asset() = runTest {
        val s = createStore()
        s.plan(ref, resources())
        s.markStaged(ref, "ASSET-Q-primary.heic", "/p")
        s.markStaged(ref, "ASSET-Q-live.mov", "/l")
        s.markImported(ref, "LOCAL-NEW")
        s.plan(ref, resources()) // a later union read re-offers it
        assertTrue(s.isImported(ref))
        assertEquals(1, s.importedCount())
    }

    @Test
    fun prune_drops_non_terminal_keeps_imported() = runTest {
        val s = createStore()
        val imported = AssetRef("DEVICE-A", "DONE")
        s.plan(imported, listOf(PlannedResource("DONE-primary.heic", "u", "primary", "image/heic", "D.HEIC")))
        s.markStaged(imported, "DONE-primary.heic", "/d")
        s.markImported(imported, "LOCAL-DONE")
        s.plan(ref, resources()) // a fresh, non-terminal asset

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
