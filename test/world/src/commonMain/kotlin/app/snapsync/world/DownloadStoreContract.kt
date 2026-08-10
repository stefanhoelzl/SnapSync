package app.snapsync.world

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

    /**
     * The unconfirmed row — the state the duplicate-import defect lives in (capability `download-store`).
     * The marker is written inside the platform's change block and the confirmation never arrives, so an
     * asset exists that the row does not know about. Every property below is what stops that asset being
     * imported a second time and then uploaded back into the event.
     */
    @Test
    fun an_unconfirmed_row_is_adjudicated_not_imported_and_never_loses_its_marker() = runTest {
        val s = createStore()
        s.plan(ref, "2026-06-30T10:00:00Z", resources())
        s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        s.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")
        assertEquals(listOf(ref), s.importableAssets().map { it.ref }) // ordinary work, before the marker

        s.recordCreatedLocalId(ref, "LOCAL-CREATED")

        // Out of the ordinary import queue: importing it again is the duplicate.
        assertTrue(s.importableAssets().isEmpty(), "a row carrying a marker is not ordinary import work")
        // ...and into the adjudication queue instead.
        assertEquals(listOf(ref to "LOCAL-CREATED"), s.unconfirmedImports().map { it.ref to it.createdLocalId })
        // Suppressed from the moment the marker exists — the asset is observable before it is confirmed.
        assertEquals(setOf("LOCAL-CREATED"), s.suppressedLocalIds())
        assertFalse(s.isImported(ref), "still unconfirmed")

        // The marker is the only record that this asset must not be uploaded: a prune must not take it.
        assertTrue(s.pruneNonTerminal(protecting = emptySet()).isEmpty(), "it strands no files either")
        assertEquals(setOf("LOCAL-CREATED"), s.suppressedLocalIds(), "the marker survives a prune")
        assertEquals(listOf(ref), s.unconfirmedImports().map { it.ref }, "and so does the row")
        assertEquals(2, s.stagedResources(ref).size, "and its staged bytes stay reachable for the retry")

        // Cleared (the library says the asset never existed) → ordinary work again.
        assertTrue(s.clearCreatedLocalId(ref, "LOCAL-CREATED"), "the clear names the marker the row holds")
        assertTrue(s.unconfirmedImports().isEmpty())
        assertEquals(listOf(ref), s.importableAssets().map { it.ref })
        assertTrue(s.suppressedLocalIds().isEmpty())
    }

    /**
     * The guard on the FAILURE mirror, and the harm it prevents (capability `download-store`).
     *
     * A row settled as *present* by adjudication is terminal while its transaction may still be open. If
     * that transaction then reports failure, an unguarded clear strips the marker off a terminal row — and
     * a terminal row is never adjudicated or re-imported again, so the asset stays in the library with
     * nothing recording that it must not be uploaded. That loss is permanent.
     */
    @Test
    fun a_late_clear_cannot_strip_a_settled_rows_marker() = runTest {
        val s = createStore()
        s.plan(ref, "2026-06-30T10:00:00Z", resources())
        s.recordCreatedLocalId(ref, "LOCAL-CREATED")
        s.confirmCreatedLocalId(ref, "LOCAL-CREATED") // adjudication (or the completion) settles it

        assertFalse(
            s.clearCreatedLocalId(ref, "LOCAL-CREATED"),
            "a clear against a settled row applies to nothing",
        )
        assertTrue(s.isImported(ref), "the row is still terminal")
        assertEquals(
            setOf("LOCAL-CREATED"),
            s.suppressedLocalIds(),
            "and its asset is still suppressed — stripping this handle is unrecoverable",
        )
    }

    /** The other half of the same guard: a clear naming a marker the row has moved on from. */
    @Test
    fun a_clear_naming_a_stale_marker_leaves_the_current_one_intact() = runTest {
        val s = createStore()
        s.plan(ref, "2026-06-30T10:00:00Z", resources())
        s.recordCreatedLocalId(ref, "FIRST")
        s.clearCreatedLocalId(ref, "FIRST")
        s.recordCreatedLocalId(ref, "SECOND")

        assertFalse(s.clearCreatedLocalId(ref, "FIRST"), "the abandoned transaction's clear applies to nothing")
        assertEquals(
            listOf(ref to "SECOND"),
            s.unconfirmedImports().map { it.ref to it.createdLocalId },
            "the marker the row now holds is intact",
        )
    }

    /**
     * The marker write's report, and why `false` is an emergency (capability `download-store`).
     *
     * Matching no row means the row was deleted between this import being selected and its change block
     * running — the failure the prune's `protecting` set exists to prevent. The asset the block goes on to
     * create then has no suppression handle at all, and this Boolean is the only evidence it ever happened.
     */
    @Test
    fun a_marker_write_onto_a_row_that_is_gone_reports_it() = runTest {
        val s = createStore()

        assertFalse(
            s.recordCreatedLocalId(AssetRef("DEVICE-Z", "NEVER-PLANNED"), "LOCAL-CREATED"),
            "no row, so the marker landed on nothing",
        )

        s.plan(ref, "2026-06-30T10:00:00Z", resources())
        assertTrue(s.recordCreatedLocalId(ref, "LOCAL-CREATED"), "an ordinary write reports that it landed")
    }

    /** Staged bytes are released only once a row is settled — releasing earlier loses the photo. */
    @Test
    fun staged_paths_are_offered_only_for_settled_rows() = runTest {
        val s = createStore()
        s.plan(ref, "2026-06-30T10:00:00Z", resources())
        s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        s.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")

        // Unconfirmed: neither releasable as confirmed, nor as prunable — the retry needs these bytes.
        s.recordCreatedLocalId(ref, "LOCAL-CREATED")
        assertTrue(s.stagedPathsOfImportedAssets().isEmpty())
        assertTrue(
            s.pruneNonTerminal(protecting = emptySet()).isEmpty(),
            "a marker-carrying row is never prunable, so its bytes are never stranded",
        )

        s.markImported(ref, "LOCAL-CREATED")
        assertEquals(
            setOf("/stage/primary.heic", "/stage/live.mov"),
            s.stagedPathsOfImportedAssets().toSet(),
            "confirmed → the bytes are redundant",
        )

        // Dropping the resource rows is what makes a release pass self-extinguishing.
        s.dropResources(ref)
        assertTrue(s.stagedPathsOfImportedAssets().isEmpty(), "a second pass finds nothing")
        assertTrue(s.isImported(ref), "while the row and its marker remain")
        assertEquals(setOf("LOCAL-CREATED"), s.suppressedLocalIds())
    }

    /**
     * The completion's own confirming write (capability `download-store`). The callback that learns the
     * outcome settles the row, so an import whose wait was abandoned needs no later library lookup to
     * discover what the completion already knew.
     */
    @Test
    fun a_completion_settles_its_row_against_the_marker_it_holds() = runTest {
        val s = createStore()
        s.plan(ref, "2026-06-30T10:00:00Z", resources())
        s.recordCreatedLocalId(ref, "LOCAL-CREATED")

        s.confirmCreatedLocalId(ref, "LOCAL-CREATED")

        assertTrue(s.isImported(ref), "settled by the party that learned the outcome")
        assertEquals(setOf("LOCAL-CREATED"), s.suppressedLocalIds(), "against the marker it already held")
        assertTrue(s.unconfirmedImports().isEmpty(), "and it no longer awaits adjudication")
    }

    /**
     * The guard on that write. A completion arriving after its marker was cleared and the asset
     * re-imported must not mark the row terminal against an identifier it no longer describes — that
     * would drop the asset the row NOW points at out of the suppression set.
     */
    @Test
    fun a_late_completion_cannot_settle_a_row_whose_marker_moved_on() = runTest {
        val s = createStore()
        s.plan(ref, "2026-06-30T10:00:00Z", resources())
        s.recordCreatedLocalId(ref, "FIRST")
        s.clearCreatedLocalId(ref, "FIRST")
        s.recordCreatedLocalId(ref, "SECOND")

        assertFalse(
            s.confirmCreatedLocalId(ref, "FIRST"), // the abandoned transaction reports at last
            "and it reports that it applied to nothing — the caller gates a byte release on this",
        )

        assertFalse(s.isImported(ref), "the stale completion settled nothing")
        assertEquals(
            listOf(ref to "SECOND"),
            s.unconfirmedImports().map { it.ref to it.createdLocalId },
            "and the marker the row now holds is intact",
        )
    }

    @Test
    fun prune_drops_non_terminal_keeps_imported() = runTest {
        val s = createStore()
        val imported = AssetRef("DEVICE-A", "DONE")
        s.plan(imported, "2026-01-01T00:00:00Z", listOf(PlannedResource("DONE-primary.heic", "u", "primary", "image/heic", "D.HEIC")))
        s.markStaged(imported, "DONE-primary.heic", "/d")
        s.markImported(imported, "LOCAL-DONE")
        s.plan(ref, "2026-06-30T10:00:00Z", resources()) // a fresh, non-terminal asset

        s.pruneNonTerminal(protecting = emptySet())

        assertTrue(s.isImported(imported)) // terminal row preserved (delete-proof, cross-event dedup)
        assertEquals(setOf("LOCAL-DONE"), s.suppressedLocalIds())
        assertFalse(s.isImported(ref))
        assertTrue(s.pendingDownloads().isEmpty()) // the non-terminal asset's resources were dropped
    }

    /**
     * The prune frees exactly what it stranded (capability `download-store`). Read and delete are one
     * operation, so the paths returned describe the rows this call actually dropped — not the rows that
     * looked prunable at some earlier instant.
     */
    @Test
    fun prune_returns_the_staged_paths_it_stranded() = runTest {
        val s = createStore()
        s.plan(ref, "2026-06-30T10:00:00Z", resources())
        s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        s.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")

        val stranded = s.pruneNonTerminal(protecting = emptySet())

        assertEquals(setOf("/stage/primary.heic", "/stage/live.mov"), stranded.toSet())
        // `pendingDownloads` would pass either way (it filters on stagedPath IS NULL, and both are staged),
        // so assert on the resource rows themselves.
        assertTrue(s.stagedResources(ref).isEmpty(), "and the rows that referenced them are gone")
    }

    /**
     * The `protecting` set, and the state that makes it necessary (capability `download-store`).
     *
     * An import is claimed BEFORE its change block runs, so its row is non-terminal and carries no marker —
     * indistinguishable, by state alone, from ordinary prunable work. Dropping it makes the change block's
     * marker write land on nothing, and the asset it creates is then uploaded back into the event with no
     * suppression handle. Nothing else in the store can express "this row's marker is still coming".
     */
    @Test
    fun prune_spares_a_claimed_row_that_has_no_marker_yet() = runTest {
        val s = createStore()
        s.plan(ref, "2026-06-30T10:00:00Z", resources())
        s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        s.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")

        val stranded = s.pruneNonTerminal(protecting = setOf(ref))

        assertTrue(stranded.isEmpty(), "a protected row's files are not stranded")
        assertEquals(2, s.stagedResources(ref).size, "and its bytes are still there for the import to read")
        // The whole point: the change block that runs next still finds a row to write its marker onto.
        assertTrue(s.recordCreatedLocalId(ref, "LOCAL-CREATED"), "the marker write lands on a row that exists")
        assertEquals(setOf("LOCAL-CREATED"), s.suppressedLocalIds())
    }

    /** An unprotected sibling is still dropped in the same call — protection is per-ref, not a global off. */
    @Test
    fun prune_protects_only_the_refs_it_was_given() = runTest {
        val s = createStore()
        val other = AssetRef("DEVICE-B", "ASSET-R")
        s.plan(ref, "2026-06-30T10:00:00Z", resources())
        s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        s.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")
        s.plan(other, "2026-06-30T11:00:00Z", listOf(PlannedResource("ASSET-R-primary.heic", "u", "primary", "image/heic", "R.HEIC")))
        s.markStaged(other, "ASSET-R-primary.heic", "/stage/r.heic")

        val stranded = s.pruneNonTerminal(protecting = setOf(ref))

        assertEquals(listOf("/stage/r.heic"), stranded, "only the unprotected row's files are stranded")
        assertEquals(2, s.stagedResources(ref).size)
        assertTrue(s.stagedResources(other).isEmpty(), "and only its rows are gone")
    }
}
