package app.snapsync.services.downloads

import app.snapsync.mock.inMemoryDatabases
import app.snapsync.model.AdoptedAsset
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.PlannedAsset
import app.snapsync.model.PlannedResource
import app.snapsync.model.SuppressionReadiness
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The download store (capability `receiving-photos`): every foreign photo of the joined events' unions, from planned
 * through staged to imported, and — the part a lost or echoed photo turns on — the created-asset marker, which is
 * the only record that an imported asset must not be uploaded back into the event.
 *
 * Over the `Databases` mock — real SQLite, bound to the same `Databases` contract as every platform's adapter; that
 * each platform's SQLite runs every statement of the store is that contract's schema clauses.
 */
class DownloadServiceTest {

    private val store = DownloadService(inMemoryDatabases())

    private val ref = AssetRef("DEVICE-A", AssetId("ASSET-Q"))

    private fun resources() = listOf(
        PlannedResource("ASSET-Q-primary.heic", "https://e/primary", "primary", "image/heic", "IMG.HEIC"),
        PlannedResource("ASSET-Q-live.mov", "https://e/live", "live", "video/quicktime", "IMG.MOV"),
    )

    private fun planned(ref: AssetRef) = PlannedAsset(ref, "2026-06-30T10:00:00Z", resources())

    /** The app's own store is always ready: a read-write open creates and migrates, so it never meets an old schema. */
    @Test
    fun `the app's store is always ready to suppress`() = runTest {
        assertEquals(SuppressionReadiness.Ready, store.readiness())
        assertEquals(emptySet(), store.suppressedLocalIds())
    }

    // ---- planning: what the union offers, recorded once ----------------------------------------------------------

    @Test
    fun `a planned asset lists every resource as pending and is not yet settled`() = runTest {
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        assertEquals(2, store.pendingDownloads().size)
        assertFalse(store.isSettled(ref))
        assertTrue(store.importableAssets().isEmpty()) // nothing staged yet
    }

    @Test
    fun `a batch plan records every asset with its resources`() = runTest {
        val other = AssetRef("DEVICE-B", AssetId("ASSET-R"))
        store.planAll(
            listOf(
                PlannedAsset(ref, "2026-06-30T10:00:00Z", resources()),
                PlannedAsset(
                    other,
                    "2026-06-30T11:00:00Z",
                    listOf(PlannedResource("ASSET-R-primary.heic", "https://e/r", "primary", "image/heic", "R.HEIC")),
                ),
            ),
        )
        assertEquals(
            setOf(ref to "ASSET-Q-primary.heic", ref to "ASSET-Q-live.mov", other to "ASSET-R-primary.heic"),
            store.pendingDownloads().map { it.ref to it.resource.resourceKey }.toSet(),
        )
        assertEquals(2, store.counts().stillArriving)
        store.markStaged(other, "ASSET-R-primary.heic", "/stage/r.heic")
        assertEquals(listOf(other), store.importableAssets().map { it.ref }, "an asset's resources landed with it")
        assertEquals("2026-06-30T11:00:00Z", store.importableAssets().single().creationDate)
    }

    @Test
    fun `a re-plan refreshes the url of an unstaged resource only`() = runTest {
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic") // primary now staged, live pending

        // A later union read re-plans with freshly presigned (rotated) urls for both resources.
        store.plan(
            ref,
            "2026-06-30T10:00:00Z",
            listOf(
                PlannedResource(
                    "ASSET-Q-primary.heic",
                    "https://e/primary?sig=NEW",
                    "primary",
                    "image/heic",
                    "IMG.HEIC",
                ),
                PlannedResource("ASSET-Q-live.mov", "https://e/live?sig=NEW", "live", "video/quicktime", "IMG.MOV"),
            ),
        )

        // The not-yet-staged resource (live) picks up the fresh url; the staged one is not re-queued.
        val pending = store.pendingDownloads()
        assertEquals(1, pending.size)
        assertEquals("ASSET-Q-live.mov", pending.single().resource.resourceKey)
        assertEquals("https://e/live?sig=NEW", pending.single().resource.url)

        // The staged resource (primary) keeps its staging untouched (no re-download).
        assertEquals(listOf("ASSET-Q-primary.heic"), store.stagedResources(ref).map { it.resourceKey })
        assertEquals("/stage/primary.heic", store.stagedResources(ref).single().stagedPath)
    }

    @Test
    fun `a batch re-plan refreshes only unstaged urls and never downgrades a settled row`() = runTest {
        val done = AssetRef("DEVICE-B", AssetId("DONE"))
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        store.plan(done, "2026-06-30T10:00:00Z", resources())
        store.markImported(done, AssetId("LOCAL-DONE"))

        val rotated = listOf(
            PlannedResource("ASSET-Q-primary.heic", "https://e/primary?sig=NEW", "primary", "image/heic", "IMG.HEIC"),
            PlannedResource("ASSET-Q-live.mov", "https://e/live?sig=NEW", "live", "video/quicktime", "IMG.MOV"),
        )
        store.planAll(
            listOf(
                PlannedAsset(ref, "2026-06-30T10:00:00Z", rotated),
                PlannedAsset(done, "2026-06-30T10:00:00Z", rotated),
            ),
        )

        val pending = store.pendingDownloads()
        assertEquals(
            listOf(ref to "https://e/live?sig=NEW"),
            pending.map { it.ref to it.resource.url },
            "only the unstaged resource is refreshed",
        )
        assertEquals(
            "/stage/primary.heic",
            store.stagedResources(ref).single().stagedPath,
            "a staged resource keeps its staging",
        )
        assertTrue(store.isSettled(done), "and a terminal row is never downgraded")
        assertEquals(setOf(AssetId("LOCAL-DONE")), store.suppressedLocalIds())
    }

    @Test
    fun `a planned resource names the event whose bytes it fetches`() = runTest {
        store.planAll(listOf(planned(ref)), "EVENT-1", members = listOf(ref))
        assertEquals(setOf("EVENT-1"), store.pendingDownloads().mapTo(mutableSetOf()) { it.eventId })

        // Re-planned by another event's read while still unstaged: re-pointed at that event's bytes.
        store.planAll(listOf(planned(ref)), "EVENT-2", members = listOf(ref))
        assertEquals(setOf("EVENT-2"), store.pendingDownloads().mapTo(mutableSetOf()) { it.eventId })
    }

    @Test
    fun `settled among answers the asked refs that are imported or unimportable`() = runTest {
        val imported = AssetRef("DEVICE-A", AssetId("IMPORTED"))
        val unimportable = AssetRef("DEVICE-A", AssetId("UNIMPORTABLE"))
        val pending = AssetRef("DEVICE-A", AssetId("PENDING"))
        val unconfirmed = AssetRef("DEVICE-A", AssetId("UNCONFIRMED"))
        val notAsked = AssetRef("DEVICE-B", AssetId("IMPORTED-NOT-ASKED"))
        val unknown = AssetRef("DEVICE-Z", AssetId("NEVER-PLANNED"))
        listOf(imported, unimportable, pending, unconfirmed, notAsked).forEach {
            store.plan(it, "2026-06-30T10:00:00Z", resources())
        }
        store.markImported(imported, AssetId("LOCAL-1"))
        store.markImported(notAsked, AssetId("LOCAL-2"))
        store.settleUnimportable(unimportable)
        store.recordCreatedLocalId(unconfirmed, AssetId("LOCAL-3"))

        assertEquals(
            setOf(imported, unimportable),
            store.settledAmong(listOf(imported, unimportable, pending, unconfirmed, unknown)),
            "both terminal states answer yes; a pending, an unconfirmed and an unknown ref do not, and an unasked one is not reported",
        )
        assertTrue(store.settledAmong(emptyList()).isEmpty())
    }

    /** A reconcile that found nothing new still stores its position and its members; one with neither writes nothing. */
    @Test
    fun `a plan with nothing to record writes nothing`() = runTest {
        store.planAll(emptyList())
        store.planAll(emptyList(), eventId = "EVENT-1")
        assertEquals(0, store.counts().stillArriving)
        assertEquals(null, store.union.cursor("EVENT-1"))

        store.planAll(emptyList(), eventId = "EVENT-1", cursor = 5)
        assertEquals(5, store.union.cursor("EVENT-1"))
    }

    // ---- staging and importing -----------------------------------------------------------------------------------

    @Test
    fun `staging a resource with no row applies to nothing`() = runTest {
        assertFalse(
            store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/orphan.heic"),
            "a transfer that outran its row's prune is answered as unrecorded, so its file is discarded",
        )
        assertTrue(store.importableAssets().isEmpty(), "and nothing becomes importable")
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        assertTrue(store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic"), "a planned row takes it")
    }

    @Test
    fun `an asset is importable only when every resource is staged`() = runTest {
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        assertTrue(store.importableAssets().isEmpty()) // live still missing
        store.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")
        assertEquals(listOf(ref), store.importableAssets().map { it.ref })
        assertEquals(2, store.stagedResources(ref).size)
        assertEquals(setOf("primary", "live"), store.stagedResources(ref).map { it.role }.toSet())
    }

    @Test
    fun `in flight counts assets with an enqueued - not yet staged resource`() = runTest {
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        assertEquals(0, store.counts().inFlight) // planned, but nothing sent to the OS yet

        store.markAllEnqueued(store.pendingDownloads().filter { it.resource.resourceKey == "ASSET-Q-primary.heic" })
        assertEquals(1, store.counts().inFlight) // a resource sent → the asset is in flight

        store.markAllEnqueued(store.pendingDownloads().filter { it.resource.resourceKey == "ASSET-Q-live.mov" })
        assertEquals(1, store.counts().inFlight) // asset-counted, not one per resource

        store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        assertEquals(1, store.counts().inFlight) // live still enqueued-not-staged

        store.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")
        assertEquals(0, store.counts().inFlight) // every resource staged → no longer in flight
    }

    @Test
    fun `a batch enqueue puts every marked asset in flight`() = runTest {
        val other = AssetRef("DEVICE-B", AssetId("ASSET-R"))
        store.planAll(
            listOf(
                PlannedAsset(ref, "2026-06-30T10:00:00Z", resources()),
                PlannedAsset(other, "2026-06-30T11:00:00Z", resources()),
            ),
        )
        assertEquals(0, store.counts().inFlight)
        store.markAllEnqueued(emptyList())
        assertEquals(0, store.counts().inFlight, "an empty batch marks nothing")

        store.markAllEnqueued(store.pendingDownloads())
        assertEquals(2, store.counts().inFlight, "asset-counted, every asset of the batch")

        store.markStaged(ref, "ASSET-Q-primary.heic", "/p")
        store.markStaged(ref, "ASSET-Q-live.mov", "/l")
        assertEquals(1, store.counts().inFlight, "staging still supersedes a batch mark")
    }

    /**
     * The projection's counts come from ONE read (capability `receiving-photos`).
     *
     * A store could satisfy every count's individual semantics above and still publish a torn composite by
     * answering them from three different instants — which is what this asserts against. It cannot catch an
     * interleaving directly (there is no writer racing this test), so it asserts the surface instead: the
     * three counts are one value, taken together, and a store that cannot produce them together cannot
     * satisfy this signature at all.
     */
    @Test
    fun `the projection counts come from one read`() = runTest {
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        store.markAllEnqueued(store.pendingDownloads().filter { it.resource.resourceKey == "ASSET-Q-primary.heic" })

        val counts = store.counts()
        assertEquals(0, counts.imported, "nothing imported yet")
        assertEquals(1, counts.stillArriving, "the planned asset can still arrive")
        assertEquals(1, counts.inFlight, "and one of its resources is enqueued, not staged")

        store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/p")
        store.markStaged(ref, "ASSET-Q-live.mov", "/stage/l")
        store.markImported(ref, AssetId("LOCAL-1"))

        val settled = store.counts()
        assertEquals(1, settled.imported)
        assertEquals(1, settled.stillArriving, "an imported asset stays in the denominator")
        assertEquals(0, settled.inFlight)
    }

    @Test
    fun `an import settles the row and suppresses its asset`() = runTest {
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        store.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")
        store.markImported(ref, AssetId("LOCAL-NEW_L0_001"))

        assertTrue(store.isSettled(ref))
        assertEquals(setOf(AssetId("LOCAL-NEW_L0_001")), store.suppressedLocalIds())
        assertEquals(1, store.counts().imported)
        assertTrue(store.importableAssets().isEmpty()) // imported, no longer importable
        assertTrue(store.pendingDownloads().isEmpty()) // imported asset's resources are not re-queued
    }

    // ---- the created-asset marker: the only record that an asset must not be uploaded ----------------------------

    /**
     * The unconfirmed row — the state the duplicate-import defect lives in (capability `receiving-photos`).
     * The marker is written inside the platform's change block and the confirmation never arrives, so an
     * asset exists that the row does not know about. Every property below is what stops that asset being
     * imported a second time and then uploaded back into the event.
     */
    @Test
    fun `an unconfirmed row is adjudicated not imported and never loses its marker`() = runTest {
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        store.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")
        assertEquals(listOf(ref), store.importableAssets().map { it.ref }) // ordinary work, before the marker

        store.recordCreatedLocalId(ref, AssetId("LOCAL-CREATED"))

        // Out of the ordinary import queue: importing it again is the duplicate.
        assertTrue(store.importableAssets().isEmpty(), "a row carrying a marker is not ordinary import work")
        // ...and into the adjudication queue instead.
        assertEquals(
            listOf(ref to AssetId("LOCAL-CREATED")),
            store.unconfirmedImports().map { it.ref to it.createdLocalId },
        )
        // Suppressed from the moment the marker exists — the asset is observable before it is confirmed.
        assertEquals(setOf(AssetId("LOCAL-CREATED")), store.suppressedLocalIds())
        assertFalse(store.isSettled(ref), "still unconfirmed")

        // The marker is the only record that this asset must not be uploaded: a prune must not take it.
        assertTrue(store.pruneNonTerminal(protecting = emptySet()).isEmpty(), "it strands no files either")
        assertEquals(setOf(AssetId("LOCAL-CREATED")), store.suppressedLocalIds(), "the marker survives a prune")
        assertEquals(listOf(ref), store.unconfirmedImports().map { it.ref }, "and so does the row")
        assertEquals(2, store.stagedResources(ref).size, "and its staged bytes stay reachable for the retry")

        // Cleared (the library says the asset never existed) → ordinary work again.
        assertTrue(store.clearCreatedLocalId(ref, AssetId("LOCAL-CREATED")), "the clear names the marker the row holds")
        assertTrue(store.unconfirmedImports().isEmpty())
        assertEquals(listOf(ref), store.importableAssets().map { it.ref })
        assertTrue(store.suppressedLocalIds().isEmpty())
    }

    /**
     * The marker write's report, and why `false` is an emergency (capability `receiving-photos`).
     *
     * Matching no row means the row was deleted between this import being selected and its change block
     * running — the failure the prune's `protecting` set exists to prevent. The asset the block goes on to
     * create then has no suppression handle at all, and this Boolean is the only evidence it ever happened.
     */
    @Test
    fun `a marker write onto a row that is gone reports it`() = runTest {
        assertFalse(
            store.recordCreatedLocalId(AssetRef("DEVICE-Z", AssetId("NEVER-PLANNED")), AssetId("LOCAL-CREATED")),
            "no row, so the marker landed on nothing",
        )

        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        assertTrue(
            store.recordCreatedLocalId(ref, AssetId("LOCAL-CREATED")),
            "an ordinary write reports that it landed",
        )
    }

    /**
     * The completion's own confirming write (capability `receiving-photos`). The callback that learns the
     * outcome settles the row, so an import whose wait was abandoned needs no later library lookup to
     * discover what the completion already knew.
     */
    @Test
    fun `a completion settles its row against the marker it holds`() = runTest {
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        store.recordCreatedLocalId(ref, AssetId("LOCAL-CREATED"))

        store.confirmCreatedLocalId(ref, AssetId("LOCAL-CREATED"))

        assertTrue(store.isSettled(ref), "settled by the party that learned the outcome")
        assertEquals(setOf(AssetId("LOCAL-CREATED")), store.suppressedLocalIds(), "against the marker it already held")
        assertTrue(store.unconfirmedImports().isEmpty(), "and it no longer awaits adjudication")
    }

    /**
     * The guard on that write. A completion arriving after its marker was cleared and the asset
     * re-imported must not mark the row terminal against an identifier it no longer describes — that
     * would drop the asset the row NOW points at out of the suppression set.
     */
    @Test
    fun `a late completion cannot settle a row whose marker moved on`() = runTest {
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        store.recordCreatedLocalId(ref, AssetId("FIRST"))
        store.clearCreatedLocalId(ref, AssetId("FIRST"))
        store.recordCreatedLocalId(ref, AssetId("SECOND"))

        assertFalse(
            store.confirmCreatedLocalId(ref, AssetId("FIRST")), // the abandoned transaction reports at last
            "and it reports that it applied to nothing — the caller gates a byte release on this",
        )

        assertFalse(store.isSettled(ref), "the stale completion settled nothing")
        assertEquals(
            listOf(ref to AssetId("SECOND")),
            store.unconfirmedImports().map { it.ref to it.createdLocalId },
            "and the marker the row now holds is intact",
        )
    }

    /**
     * The guard on the FAILURE mirror, and the harm it prevents (capability `receiving-photos`).
     *
     * A row settled as *present* by adjudication is terminal while its transaction may still be open. If
     * that transaction then reports failure, an unguarded clear strips the marker off a terminal row — and
     * a terminal row is never adjudicated or re-imported again, so the asset stays in the library with
     * nothing recording that it must not be uploaded. That loss is permanent.
     */
    @Test
    fun `a late clear cannot strip a settled row's marker`() = runTest {
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        store.recordCreatedLocalId(ref, AssetId("LOCAL-CREATED"))
        store.confirmCreatedLocalId(ref, AssetId("LOCAL-CREATED")) // adjudication (or the completion) settles it

        assertFalse(
            store.clearCreatedLocalId(ref, AssetId("LOCAL-CREATED")),
            "a clear against a settled row applies to nothing",
        )
        assertTrue(store.isSettled(ref), "the row is still terminal")
        assertEquals(
            setOf(AssetId("LOCAL-CREATED")),
            store.suppressedLocalIds(),
            "and its asset is still suppressed — stripping this handle is unrecoverable",
        )
    }

    /** The other half of the same guard: a clear naming a marker the row has moved on from. */
    @Test
    fun `a clear naming a stale marker leaves the current one intact`() = runTest {
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        store.recordCreatedLocalId(ref, AssetId("FIRST"))
        store.clearCreatedLocalId(ref, AssetId("FIRST"))
        store.recordCreatedLocalId(ref, AssetId("SECOND"))

        assertFalse(
            store.clearCreatedLocalId(ref, AssetId("FIRST")),
            "the abandoned transaction's clear applies to nothing",
        )
        assertEquals(
            listOf(ref to AssetId("SECOND")),
            store.unconfirmedImports().map { it.ref to it.createdLocalId },
            "the marker the row now holds is intact",
        )
    }

    /** Staged bytes are released only once a row is settled — releasing earlier loses the photo. */
    @Test
    fun `staged paths are offered only for settled rows`() = runTest {
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        store.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")

        // Unconfirmed: neither releasable as confirmed, nor as prunable — the retry needs these bytes.
        store.recordCreatedLocalId(ref, AssetId("LOCAL-CREATED"))
        assertTrue(store.stagedPathsOfImportedAssets().isEmpty())
        assertTrue(
            store.pruneNonTerminal(protecting = emptySet()).isEmpty(),
            "a marker-carrying row is never prunable, so its bytes are never stranded",
        )

        store.markImported(ref, AssetId("LOCAL-CREATED"))
        assertEquals(
            setOf("/stage/primary.heic", "/stage/live.mov"),
            store.stagedPathsOfImportedAssets().toSet(),
            "confirmed → the bytes are redundant",
        )

        // Dropping the resource rows is what makes a release pass self-extinguishing.
        store.dropResources(ref)
        assertTrue(store.stagedPathsOfImportedAssets().isEmpty(), "a second pass finds nothing")
        assertTrue(store.isSettled(ref), "while the row and its marker remain")
        assertEquals(setOf(AssetId("LOCAL-CREATED")), store.suppressedLocalIds())
    }

    // ---- settling as unimportable --------------------------------------------------------------------------------

    /**
     * A row settled as permanently unimportable leaves every read that could offer it work again
     * (capability `receiving-photos`). Both implementations must agree, because the SQL expresses "terminal"
     * as a `NOT IN` list and the fake expresses it as an enum property — two spellings of one notion, and a
     * predicate missed on either side puts the row back into the retry loop this state exists to end.
     */
    @Test
    fun `a settled unimportable row is offered no work and holds no handle`() = runTest {
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/p")
        store.markStaged(ref, "ASSET-Q-live.mov", "/stage/l")
        assertEquals(1, store.importableAssets().size)

        assertTrue(store.settleUnimportable(ref), "the row was non-terminal and carried no marker")

        assertTrue(store.isSettled(ref), "discovery must not plan it again")
        assertTrue(store.importableAssets().isEmpty(), "no import may be attempted")
        assertTrue(store.unconfirmedImports().isEmpty(), "and it is not adjudicated — no asset was created")
        assertTrue(store.pendingDownloads().isEmpty(), "nor re-downloaded")
        assertTrue(store.stagedResources(ref).isEmpty(), "the rows that made it findable are dropped")
        assertTrue(store.suppressedLocalIds().isEmpty(), "it is NOT a suppression handle: no asset exists")
        assertEquals(0, store.counts().imported, "it never counts as arrived")
        assertEquals(0, store.counts().stillArriving, "and it leaves the denominator, so the screen can still complete")
    }

    /** The guard: a row that already settled one way must not be re-settled another. */
    @Test
    fun `settling an already terminal row applies nothing`() = runTest {
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/p")
        store.markStaged(ref, "ASSET-Q-live.mov", "/stage/l")
        store.markImported(ref, AssetId("LOCAL-1"))

        assertFalse(store.settleUnimportable(ref), "an imported row is terminal — this must match nothing")
        assertEquals(setOf(AssetId("LOCAL-1")), store.suppressedLocalIds(), "and its handle is untouched")
        assertEquals(1, store.counts().imported)
    }

    /** And a row mid-import — marker written, commit not landed — is not a row to give up on. */
    @Test
    fun `settling a row that carries a marker applies nothing`() = runTest {
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/p")
        store.markStaged(ref, "ASSET-Q-live.mov", "/stage/l")
        store.recordCreatedLocalId(ref, AssetId("LOCAL-1"))

        assertFalse(store.settleUnimportable(ref), "an asset WAS created for this ref — it is not unimportable")
        assertEquals(1, store.unconfirmedImports().size, "it stays adjudicable")
        assertEquals(setOf(AssetId("LOCAL-1")), store.suppressedLocalIds(), "and stays suppressed")
    }

    // ---- pruning at a leave or a switch --------------------------------------------------------------------------

    @Test
    fun `a prune drops non-terminal rows and keeps imported ones`() = runTest {
        val imported = AssetRef("DEVICE-A", AssetId("DONE"))
        store.plan(
            imported,
            "2026-01-01T00:00:00Z",
            listOf(PlannedResource("DONE-primary.heic", "u", "primary", "image/heic", "D.HEIC")),
        )
        store.markStaged(imported, "DONE-primary.heic", "/d")
        store.markImported(imported, AssetId("LOCAL-DONE"))
        store.plan(ref, "2026-06-30T10:00:00Z", resources()) // a fresh, non-terminal asset

        store.pruneNonTerminal(protecting = emptySet())

        assertTrue(store.isSettled(imported)) // terminal row preserved (delete-proof, cross-event dedup)
        assertEquals(setOf(AssetId("LOCAL-DONE")), store.suppressedLocalIds())
        assertFalse(store.isSettled(ref))
        assertTrue(store.pendingDownloads().isEmpty()) // the non-terminal asset's resources were dropped
    }

    /**
     * The prune frees exactly what it stranded (capability `receiving-photos`). Read and delete are one
     * operation, so the paths returned describe the rows this call actually dropped — not the rows that
     * looked prunable at some earlier instant.
     */
    @Test
    fun `prune returns the staged paths it stranded`() = runTest {
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        store.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")

        val stranded = store.pruneNonTerminal(protecting = emptySet())

        assertEquals(setOf("/stage/primary.heic", "/stage/live.mov"), stranded.toSet())
        // `pendingDownloads` would pass either way (it filters on stagedPath IS NULL, and both are staged),
        // so assert on the resource rows themselves.
        assertTrue(store.stagedResources(ref).isEmpty(), "and the rows that referenced them are gone")
    }

    /**
     * The `protecting` set, and the state that makes it necessary (capability `receiving-photos`).
     *
     * An import is claimed BEFORE its change block runs, so its row is non-terminal and carries no marker —
     * indistinguishable, by state alone, from ordinary prunable work. Dropping it makes the change block's
     * marker write land on nothing, and the asset it creates is then uploaded back into the event with no
     * suppression handle. Nothing else in the store can express "this row's marker is still coming".
     */
    @Test
    fun `prune spares a claimed row that has no marker yet`() = runTest {
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        store.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")

        val stranded = store.pruneNonTerminal(protecting = setOf(ref))

        assertTrue(stranded.isEmpty(), "a protected row's files are not stranded")
        assertEquals(2, store.stagedResources(ref).size, "and its bytes are still there for the import to read")
        // The whole point: the change block that runs next still finds a row to write its marker onto.
        assertTrue(
            store.recordCreatedLocalId(ref, AssetId("LOCAL-CREATED")),
            "the marker write lands on a row that exists",
        )
        assertEquals(setOf(AssetId("LOCAL-CREATED")), store.suppressedLocalIds())
    }

    /** An unprotected sibling is still dropped in the same call — protection is per-ref, not a global off. */
    @Test
    fun `prune protects only the refs it was given`() = runTest {
        val other = AssetRef("DEVICE-B", AssetId("ASSET-R"))
        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        store.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")
        store.plan(
            other,
            "2026-06-30T11:00:00Z",
            listOf(PlannedResource("ASSET-R-primary.heic", "u", "primary", "image/heic", "R.HEIC")),
        )
        store.markStaged(other, "ASSET-R-primary.heic", "/stage/r.heic")

        val stranded = store.pruneNonTerminal(protecting = setOf(ref))

        assertEquals(listOf("/stage/r.heic"), stranded, "only the unprotected row's files are stranded")
        assertEquals(2, store.stagedResources(ref).size)
        assertTrue(store.stagedResources(other).isEmpty(), "and only its rows are gone")
    }

    @Test
    fun `a leave's prune forgets every position - so every next read is a full one`() = runTest {
        store.planAll(emptyList(), "EVENT-1", cursor = 3)
        store.planAll(emptyList(), "EVENT-2", cursor = 4)
        store.pruneNonTerminal(protecting = emptySet())
        assertEquals(null, store.union.cursor("EVENT-1"))
        assertEquals(null, store.union.cursor("EVENT-2"))
    }

    // ---- events: a photo is received for the events whose union holds it -----------------------------------------

    /**
     * The joined screen's received count is THIS membership's (capability `sync-status`). An imported row
     * outlives its event — it is the suppression handle — so an unscoped count read every foreign photo the
     * device ever imported as received for whatever event came next (measured on a device: "2418 received"
     * for an event whose union was empty).
     */
    @Test
    fun `event-scoped counts see only that event's union`() = runTest {
        val earlier = AssetRef("DEVICE-B", AssetId("ASSET-OLD"))
        val earlierResources = listOf(
            PlannedResource("ASSET-OLD-primary.heic", "https://e/old", "primary", "image/heic", "OLD.HEIC"),
        )
        store.planAll(
            listOf(PlannedAsset(earlier, "2026-06-01T10:00:00Z", earlierResources)),
            eventId = "EVENT-1",
            members = listOf(earlier),
        )
        store.markStaged(earlier, "ASSET-OLD-primary.heic", "/stage/old")
        store.markImported(earlier, AssetId("LOCAL-OLD"))
        assertEquals(1, store.counts("EVENT-1").imported)

        // The next event's union holds none of it.
        store.planAll(
            listOf(PlannedAsset(ref, "2026-06-30T10:00:00Z", resources())),
            eventId = "EVENT-2",
            members = listOf(ref),
        )
        val next = store.counts("EVENT-2")
        assertEquals(0, next.imported, "the earlier event's photo is not received for this one")
        assertEquals(1, next.stillArriving, "only this event's photo is there to receive")
        assertEquals(setOf(AssetId("LOCAL-OLD")), store.suppressedLocalIds(), "the earlier row is kept as a handle")
        assertEquals(2, store.counts().stillArriving, "the device-wide census still sees both")

        // A photo that reappears in a later union counts for that event from then on.
        store.planAll(emptyList(), eventId = "EVENT-2", members = listOf(ref, earlier))
        assertEquals(1, store.counts("EVENT-2").imported)
        assertEquals(2, store.counts("EVENT-2").stillArriving)
        assertEquals(1, store.counts("EVENT-1").stillArriving, "and still for the event it was received in")
    }

    /** Read by the event album's gather: only an import this event's union holds, and only a confirmed one. */
    @Test
    fun `an event's imported local ids are its confirmed imports only`() = runTest {
        fun r(id: String) = AssetRef("DEVICE-A", AssetId(id))
        val imported = r("IMPORTED")
        val pending = r("PENDING")
        val unconfirmed = r("UNCONFIRMED")
        val unimportable = r("UNIMPORTABLE")
        val others = r("OTHER-EVENT")
        val untagged = r("UNTAGGED")
        store.planAll(
            listOf(imported, pending, unconfirmed, unimportable).map(::planned),
            "EVENT-1",
            members = listOf(imported, pending, unconfirmed, unimportable),
        )
        store.planAll(listOf(planned(others)), "EVENT-2", members = listOf(others))
        store.plan(untagged, "2026-06-30T12:00:00Z", resources())
        store.markImported(imported, AssetId("LOCAL-A"))
        store.recordCreatedLocalId(unconfirmed, AssetId("LOCAL-CREATED"))
        store.settleUnimportable(unimportable)
        store.markImported(others, AssetId("LOCAL-B"))
        store.markImported(untagged, AssetId("LOCAL-C"))

        assertEquals(
            setOf(AssetId("LOCAL-A")),
            store.importedLocalIdsOf("EVENT-1"),
            "a pending, an unconfirmed (a marker is not yet known to name an asset) and an unimportable ref are " +
                "omitted, and so are another event's and an untagged one",
        )
    }

    @Test
    fun `an imported ref a later event reconciles answers for both events`() = runTest {
        store.planAll(listOf(planned(ref)), "EVENT-1", members = listOf(ref))
        store.markImported(ref, AssetId("LOCAL-1"))
        store.planAll(emptyList(), "EVENT-2", members = listOf(ref))
        assertEquals(
            setOf(AssetId("LOCAL-1")),
            store.importedLocalIdsOf("EVENT-2"),
            "the settled ref is the later event's too",
        )
        assertEquals(setOf(AssetId("LOCAL-1")), store.importedLocalIdsOf("EVENT-1"), "and still the earlier one's")
        assertEquals(1, store.counts("EVENT-1").imported)
        assertEquals(1, store.counts("EVENT-2").imported)
    }

    @Test
    fun `the join's purge forgets other events' refs and keeps every photo's record`() = runTest {
        store.planAll(listOf(planned(ref)), "EVENT-1", members = listOf(ref))
        store.markImported(ref, AssetId("LOCAL-1"))
        store.planAll(emptyList(), "EVENT-2", members = listOf(ref))

        store.union.purgeEventRefsExcept("EVENT-2")

        assertTrue(store.importedLocalIdsOf("EVENT-1").isEmpty(), "the other event's refs are gone")
        assertEquals(setOf(AssetId("LOCAL-1")), store.importedLocalIdsOf("EVENT-2"))
        assertTrue(store.isSettled(ref), "the photo's record survives")
        assertEquals(setOf(AssetId("LOCAL-1")), store.suppressedLocalIds(), "and so does its import marker")
    }

    // ---- adoption of marked photos at join -----------------------------------------------------------------------

    @Test
    fun `an adopted photo is settled suppressed and counted for its event`() = runTest {
        val adopted = store.adoptAll(
            listOf(AdoptedAsset(ref, AssetId("LOCAL-KEPT"), "2026-06-30T10:00:00Z")),
            "EVENT-1",
        )
        assertEquals(setOf(ref), adopted)
        assertTrue(store.isSettled(ref), "an adopted ref is never planned or downloaded again")
        assertEquals(setOf(AssetId("LOCAL-KEPT")), store.suppressedLocalIds(), "and its asset is never uploaded back")
        assertEquals(setOf(AssetId("LOCAL-KEPT")), store.importedLocalIdsOf("EVENT-1"))
        assertEquals(1, store.counts("EVENT-1").imported, "it counts as received for the event it was adopted for")
        assertTrue(
            store.pendingDownloads().isEmpty() && store.importableAssets().isEmpty() && store.unconfirmedImports().isEmpty(),
        )

        store.plan(ref, "2026-06-30T10:00:00Z", resources())
        assertTrue(store.pendingDownloads().isEmpty(), "a later plan cannot downgrade it")
    }

    @Test
    fun `adoption settles a planned row and leaves a marked or terminal one`() = runTest {
        val marked = AssetRef("DEVICE-B", AssetId("ASSET-MARKED"))
        val deleted = AssetRef("DEVICE-B", AssetId("ASSET-DELETED"))
        store.plan(ref, "2026-06-30T10:00:00Z", resources()) // planned while the photo grant was pending
        store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
        store.plan(marked, "2026-06-30T11:00:00Z", resources())
        assertTrue(store.recordCreatedLocalId(marked, AssetId("LOCAL-CREATED")), "an import created an asset for it")
        store.plan(deleted, "2026-06-30T12:00:00Z", resources())
        store.markImported(deleted, AssetId("LOCAL-GONE")) // imported, then deleted from the library by the member

        val adopted = store.adoptAll(
            listOf(
                AdoptedAsset(ref, AssetId("LOCAL-KEPT"), "2026-06-30T10:00:00Z"),
                AdoptedAsset(marked, AssetId("LOCAL-OTHER"), "2026-06-30T11:00:00Z"),
                AdoptedAsset(deleted, AssetId("LOCAL-Y"), "2026-06-30T12:00:00Z"),
            ),
            "EVENT-1",
        )
        assertEquals(setOf(ref), adopted, "only the planned row, which no import has created an asset for")
        assertTrue(store.isSettled(ref), "it is never imported: the library already holds it")
        assertTrue(store.importableAssets().none { it.ref == ref })
        assertTrue(store.pendingDownloads().none { it.ref == ref }, "and nothing of it is downloaded any more")
        assertEquals(
            setOf(AssetId("LOCAL-KEPT"), AssetId("LOCAL-CREATED"), AssetId("LOCAL-GONE")),
            store.suppressedLocalIds(),
            "each row keeps the handle of the asset it records",
        )
        assertEquals(
            listOf("/stage/primary.heic"),
            store.stagedPathsOfImportedAssets(),
            "its staged bytes are released",
        )
        assertEquals(1, store.counts("EVENT-1").imported, "it counts as received for the event it was adopted for")
    }

    @Test
    fun `adopting nothing writes nothing`() = runTest {
        assertTrue(store.adoptAll(emptyList(), "EVENT-1").isEmpty())
        assertEquals(0, store.counts().stillArriving)
    }

    // ---- the union position, and what a full read found withdrawn ------------------------------------------------

    @Test
    fun `a plan stores the union position it covered for its event`() = runTest {
        assertEquals(null, store.union.cursor("EVENT-1"), "no position before any read")
        store.planAll(listOf(planned(ref)), "EVENT-1", members = listOf(ref), cursor = 7)
        assertEquals(7, store.union.cursor("EVENT-1"))
        assertEquals(null, store.union.cursor("EVENT-2"), "a position is per event")
        store.planAll(emptyList(), "EVENT-1", cursor = 9)
        assertEquals(9, store.union.cursor("EVENT-1"), "a read that served nothing new still moves it")
    }

    @Test
    fun `a withdrawn row not yet received is pruned with the paths it stranded`() = runTest {
        store.planAll(listOf(planned(ref)), "EVENT-1", members = listOf(ref))
        store.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")

        val stranded = store.union.pruneWithdrawn("EVENT-1", listed = emptySet(), protecting = emptySet())

        assertEquals(listOf("/stage/primary.heic"), stranded)
        assertTrue(store.pendingDownloads().isEmpty(), "its work is gone")
        assertFalse(store.isSettled(ref), "deleted, not settled — a photo that comes back is planned again")
        store.planAll(listOf(planned(ref)), "EVENT-1", members = listOf(ref))
        assertEquals(2, store.pendingDownloads().size)
    }

    @Test
    fun `a withdrawal prune keeps what is listed - received - judged - claimed or another event's`() = runTest {
        fun r(id: String) = AssetRef("DEVICE-A", AssetId(id))
        val listed = r("LISTED")
        val imported = r("IMPORTED")
        val unimportable = r("UNIMPORTABLE")
        val claimed = r("CLAIMED")
        val marked = r("MARKED")
        val otherEvent = r("OTHER")
        val all = listOf(listed, imported, unimportable, claimed, marked)
        store.planAll(all.map(::planned), "EVENT-1", members = all)
        store.planAll(listOf(planned(otherEvent)), "EVENT-2", members = listOf(otherEvent))
        store.markImported(imported, AssetId("L-IMPORTED"))
        store.settleUnimportable(unimportable)
        store.recordCreatedLocalId(marked, AssetId("L-MARKED"))

        store.union.pruneWithdrawn("EVENT-1", listed = setOf(listed), protecting = setOf(claimed))

        assertTrue(store.isSettled(imported) && store.isSettled(unimportable), "a received or judged row stays")
        assertEquals(
            setOf(listed, claimed, marked),
            (store.pendingDownloads().mapTo(mutableSetOf()) { it.ref } + store.unconfirmedImports().map { it.ref }) - otherEvent,
            "listed, claimed and marker-carrying rows keep their work",
        )
        store.union.pruneWithdrawn("EVENT-1", listed = emptySet(), protecting = emptySet())
        assertTrue(
            otherEvent in store.pendingDownloads().map { it.ref },
            "another event's row is not this read's to judge",
        )
    }
}
