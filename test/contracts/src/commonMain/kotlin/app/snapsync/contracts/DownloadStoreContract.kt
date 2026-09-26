package app.snapsync.contracts

import app.snapsync.model.AssetRef
import app.snapsync.ports.DownloadStore
import app.snapsync.model.PlannedAsset
import app.snapsync.model.PlannedResource

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The download-store contract's state vocabulary. Every clause starts from an empty store. */
enum class DownloadStoreState { EMPTY }

/**
 * Shared contract for every [DownloadStore] impl — bound on the in-memory fake (JVM + simulator) and the
 * SQLDelight store (JVM over a JDBC driver, simulator over the native driver); mechanism: `docs/architecture.md`.
 * Exercises the download→stage→import lifecycle, the suppression projection, idempotency, and leave/switch
 * pruning.
 */
object DownloadStoreContract : Contract<DownloadStoreState, DownloadStore>("DownloadStore") {

    override val clauses = clauses {
        clause("plan then pending lists every resource", DownloadStoreState.EMPTY) { s ->
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            assertEquals(2, s.pendingDownloads().size)
            assertFalse(s.isSettled(ref))
            assertTrue(s.importableAssets().isEmpty()) // nothing staged yet
        }

        clause("staging a resource with no row applies to nothing", DownloadStoreState.EMPTY) { s ->
            assertFalse(
                s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/orphan.heic"),
                "a transfer that outran its row's prune is answered as unrecorded, so its file is discarded",
            )
            assertTrue(s.importableAssets().isEmpty(), "and nothing becomes importable")
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            assertTrue(s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic"), "a planned row takes it")
        }

        clause("importable only when all resources staged", DownloadStoreState.EMPTY) { s ->
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
            assertTrue(s.importableAssets().isEmpty()) // live still missing
            s.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")
            assertEquals(listOf(ref), s.importableAssets().map { it.ref })
            assertEquals(2, s.stagedResources(ref).size)
            assertEquals(setOf("primary", "live"), s.stagedResources(ref).map { it.role }.toSet())
        }

        clause("in flight counts assets with an enqueued not yet staged resource", DownloadStoreState.EMPTY) { s ->
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            assertEquals(0, s.counts().inFlight) // planned, but nothing sent to the OS yet

            s.markEnqueued(ref, "ASSET-Q-primary.heic")
            assertEquals(1, s.counts().inFlight) // a resource sent → the asset is in flight

            s.markEnqueued(ref, "ASSET-Q-live.mov")
            assertEquals(1, s.counts().inFlight) // asset-counted, not one per resource

            s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
            assertEquals(1, s.counts().inFlight) // live still enqueued-not-staged

            s.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")
            assertEquals(0, s.counts().inFlight) // every resource staged → no longer in flight
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
        clause("the projection counts come from one read", DownloadStoreState.EMPTY) { s ->
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            s.markEnqueued(ref, "ASSET-Q-primary.heic")

            val counts = s.counts()
            assertEquals(0, counts.imported, "nothing imported yet")
            assertEquals(1, counts.stillArriving, "the planned asset can still arrive")
            assertEquals(1, counts.inFlight, "and one of its resources is enqueued, not staged")

            s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/p")
            s.markStaged(ref, "ASSET-Q-live.mov", "/stage/l")
            s.markImported(ref, "LOCAL-1")

            val settled = s.counts()
            assertEquals(1, settled.imported)
            assertEquals(1, settled.stillArriving, "an imported asset stays in the denominator")
            assertEquals(0, settled.inFlight)
        }

        clause("import records suppression and idempotency", DownloadStoreState.EMPTY) { s ->
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
            s.markStaged(ref, "ASSET-Q-live.mov", "/stage/live.mov")
            s.markImported(ref, "LOCAL-NEW/L0/001")

            assertTrue(s.isSettled(ref))
            assertEquals(setOf("LOCAL-NEW/L0/001"), s.suppressedLocalIds())
            assertEquals(1, s.counts().imported)
            assertTrue(s.importableAssets().isEmpty()) // imported, no longer importable
            assertTrue(s.pendingDownloads().isEmpty()) // imported asset's resources are not re-queued
        }

        clause("replan refreshes url of unstaged resources only", DownloadStoreState.EMPTY) { s ->
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

        clause("plan never downgrades an imported asset", DownloadStoreState.EMPTY) { s ->
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            s.markStaged(ref, "ASSET-Q-primary.heic", "/p")
            s.markStaged(ref, "ASSET-Q-live.mov", "/l")
            s.markImported(ref, "LOCAL-NEW")
            s.plan(ref, "2026-06-30T10:00:00Z", resources()) // a later union read re-offers it
            assertTrue(s.isSettled(ref))
            assertEquals(1, s.counts().imported)
        }

        /**
         * The unconfirmed row — the state the duplicate-import defect lives in (capability `receiving-photos`).
         * The marker is written inside the platform's change block and the confirmation never arrives, so an
         * asset exists that the row does not know about. Every property below is what stops that asset being
         * imported a second time and then uploaded back into the event.
         */
        clause("an unconfirmed row is adjudicated not imported and never loses its marker", DownloadStoreState.EMPTY) { s ->
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
            assertFalse(s.isSettled(ref), "still unconfirmed")

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
         * The guard on the FAILURE mirror, and the harm it prevents (capability `receiving-photos`).
         *
         * A row settled as *present* by adjudication is terminal while its transaction may still be open. If
         * that transaction then reports failure, an unguarded clear strips the marker off a terminal row — and
         * a terminal row is never adjudicated or re-imported again, so the asset stays in the library with
         * nothing recording that it must not be uploaded. That loss is permanent.
         */
        clause("a late clear cannot strip a settled rows marker", DownloadStoreState.EMPTY) { s ->
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            s.recordCreatedLocalId(ref, "LOCAL-CREATED")
            s.confirmCreatedLocalId(ref, "LOCAL-CREATED") // adjudication (or the completion) settles it

            assertFalse(
                s.clearCreatedLocalId(ref, "LOCAL-CREATED"),
                "a clear against a settled row applies to nothing",
            )
            assertTrue(s.isSettled(ref), "the row is still terminal")
            assertEquals(
                setOf("LOCAL-CREATED"),
                s.suppressedLocalIds(),
                "and its asset is still suppressed — stripping this handle is unrecoverable",
            )
        }

        /** The other half of the same guard: a clear naming a marker the row has moved on from. */
        clause("a clear naming a stale marker leaves the current one intact", DownloadStoreState.EMPTY) { s ->
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
         * The marker write's report, and why `false` is an emergency (capability `receiving-photos`).
         *
         * Matching no row means the row was deleted between this import being selected and its change block
         * running — the failure the prune's `protecting` set exists to prevent. The asset the block goes on to
         * create then has no suppression handle at all, and this Boolean is the only evidence it ever happened.
         */
        clause("a marker write onto a row that is gone reports it", DownloadStoreState.EMPTY) { s ->

            assertFalse(
                s.recordCreatedLocalId(AssetRef("DEVICE-Z", "NEVER-PLANNED"), "LOCAL-CREATED"),
                "no row, so the marker landed on nothing",
            )

            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            assertTrue(s.recordCreatedLocalId(ref, "LOCAL-CREATED"), "an ordinary write reports that it landed")
        }

        /** Staged bytes are released only once a row is settled — releasing earlier loses the photo. */
        clause("staged paths are offered only for settled rows", DownloadStoreState.EMPTY) { s ->
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
            assertTrue(s.isSettled(ref), "while the row and its marker remain")
            assertEquals(setOf("LOCAL-CREATED"), s.suppressedLocalIds())
        }

        /**
         * The completion's own confirming write (capability `receiving-photos`). The callback that learns the
         * outcome settles the row, so an import whose wait was abandoned needs no later library lookup to
         * discover what the completion already knew.
         */
        clause("a completion settles its row against the marker it holds", DownloadStoreState.EMPTY) { s ->
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            s.recordCreatedLocalId(ref, "LOCAL-CREATED")

            s.confirmCreatedLocalId(ref, "LOCAL-CREATED")

            assertTrue(s.isSettled(ref), "settled by the party that learned the outcome")
            assertEquals(setOf("LOCAL-CREATED"), s.suppressedLocalIds(), "against the marker it already held")
            assertTrue(s.unconfirmedImports().isEmpty(), "and it no longer awaits adjudication")
        }

        /**
         * The guard on that write. A completion arriving after its marker was cleared and the asset
         * re-imported must not mark the row terminal against an identifier it no longer describes — that
         * would drop the asset the row NOW points at out of the suppression set.
         */
        clause("a late completion cannot settle a row whose marker moved on", DownloadStoreState.EMPTY) { s ->
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            s.recordCreatedLocalId(ref, "FIRST")
            s.clearCreatedLocalId(ref, "FIRST")
            s.recordCreatedLocalId(ref, "SECOND")

            assertFalse(
                s.confirmCreatedLocalId(ref, "FIRST"), // the abandoned transaction reports at last
                "and it reports that it applied to nothing — the caller gates a byte release on this",
            )

            assertFalse(s.isSettled(ref), "the stale completion settled nothing")
            assertEquals(
                listOf(ref to "SECOND"),
                s.unconfirmedImports().map { it.ref to it.createdLocalId },
                "and the marker the row now holds is intact",
            )
        }

        clause("prune drops non terminal keeps imported", DownloadStoreState.EMPTY) { s ->
            val imported = AssetRef("DEVICE-A", "DONE")
            s.plan(imported, "2026-01-01T00:00:00Z", listOf(PlannedResource("DONE-primary.heic", "u", "primary", "image/heic", "D.HEIC")))
            s.markStaged(imported, "DONE-primary.heic", "/d")
            s.markImported(imported, "LOCAL-DONE")
            s.plan(ref, "2026-06-30T10:00:00Z", resources()) // a fresh, non-terminal asset

            s.pruneNonTerminal(protecting = emptySet())

            assertTrue(s.isSettled(imported)) // terminal row preserved (delete-proof, cross-event dedup)
            assertEquals(setOf("LOCAL-DONE"), s.suppressedLocalIds())
            assertFalse(s.isSettled(ref))
            assertTrue(s.pendingDownloads().isEmpty()) // the non-terminal asset's resources were dropped
        }

        /**
         * The prune frees exactly what it stranded (capability `receiving-photos`). Read and delete are one
         * operation, so the paths returned describe the rows this call actually dropped — not the rows that
         * looked prunable at some earlier instant.
         */
        clause("prune returns the staged paths it stranded", DownloadStoreState.EMPTY) { s ->
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
         * The `protecting` set, and the state that makes it necessary (capability `receiving-photos`).
         *
         * An import is claimed BEFORE its change block runs, so its row is non-terminal and carries no marker —
         * indistinguishable, by state alone, from ordinary prunable work. Dropping it makes the change block's
         * marker write land on nothing, and the asset it creates is then uploaded back into the event with no
         * suppression handle. Nothing else in the store can express "this row's marker is still coming".
         */
        clause("prune spares a claimed row that has no marker yet", DownloadStoreState.EMPTY) { s ->
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
        clause("prune protects only the refs it was given", DownloadStoreState.EMPTY) { s ->
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

        /**
         * A row settled as permanently unimportable leaves every read that could offer it work again
         * (capability `receiving-photos`). Both implementations must agree, because the SQL expresses "terminal"
         * as a `NOT IN` list and the fake expresses it as an enum property — two spellings of one notion, and a
         * predicate missed on either side puts the row back into the retry loop this state exists to end.
         */
        clause("a settled unimportable row is offered no work and holds no handle", DownloadStoreState.EMPTY) { s ->
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/p")
            s.markStaged(ref, "ASSET-Q-live.mov", "/stage/l")
            assertEquals(1, s.importableAssets().size)

            assertTrue(s.settleUnimportable(ref), "the row was non-terminal and carried no marker")

            assertTrue(s.isSettled(ref), "discovery must not plan it again")
            assertTrue(s.importableAssets().isEmpty(), "no import may be attempted")
            assertTrue(s.unconfirmedImports().isEmpty(), "and it is not adjudicated — no asset was created")
            assertTrue(s.pendingDownloads().isEmpty(), "nor re-downloaded")
            assertTrue(s.stagedResources(ref).isEmpty(), "the rows that made it findable are dropped")
            assertTrue(s.suppressedLocalIds().isEmpty(), "it is NOT a suppression handle: no asset exists")
            assertEquals(0, s.counts().imported, "it never counts as arrived")
            assertEquals(0, s.counts().stillArriving, "and it leaves the denominator, so the screen can still complete")
        }

        /** The guard: a row that already settled one way must not be re-settled another. */
        clause("settling an already terminal row applies nothing", DownloadStoreState.EMPTY) { s ->
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/p")
            s.markStaged(ref, "ASSET-Q-live.mov", "/stage/l")
            s.markImported(ref, "LOCAL-1")

            assertFalse(s.settleUnimportable(ref), "an imported row is terminal — this must match nothing")
            assertEquals(setOf("LOCAL-1"), s.suppressedLocalIds(), "and its handle is untouched")
            assertEquals(1, s.counts().imported)
        }

        /** And a row mid-import — marker written, commit not landed — is not a row to give up on. */
        clause("settling a row that carries a marker applies nothing", DownloadStoreState.EMPTY) { s ->
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/p")
            s.markStaged(ref, "ASSET-Q-live.mov", "/stage/l")
            s.recordCreatedLocalId(ref, "LOCAL-1")

            assertFalse(s.settleUnimportable(ref), "an asset WAS created for this ref — it is not unimportable")
            assertEquals(1, s.unconfirmedImports().size, "it stays adjudicable")
            assertEquals(setOf("LOCAL-1"), s.suppressedLocalIds(), "and stays suppressed")
        }

        clause("imported local ids answers an imported ref", DownloadStoreState.EMPTY) { s ->
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            s.markImported(ref, "LOCAL-1")
            assertEquals(mapOf(ref to "LOCAL-1"), s.importedLocalIds(listOf(ref)))
        }

        clause("imported local ids omits a pending ref", DownloadStoreState.EMPTY) { s ->
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            assertTrue(s.importedLocalIds(listOf(ref)).isEmpty())
        }

        clause("imported local ids omits an unconfirmed ref", DownloadStoreState.EMPTY) { s ->
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            s.recordCreatedLocalId(ref, "LOCAL-CREATED")
            assertTrue(
                s.importedLocalIds(listOf(ref)).isEmpty(),
                "a marker without a confirmed import is not yet known to name an asset",
            )
        }

        clause("imported local ids omits unimportable and unknown refs", DownloadStoreState.EMPTY) { s ->
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            s.settleUnimportable(ref)
            val unknown = AssetRef("DEVICE-Z", "NEVER-PLANNED")
            assertTrue(s.importedLocalIds(listOf(ref, unknown)).isEmpty())
        }

        clause("imported local ids answers only the asked refs", DownloadStoreState.EMPTY) { s ->
            val other = AssetRef("DEVICE-B", "ASSET-R")
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            s.plan(other, "2026-06-30T11:00:00Z", resources())
            s.markImported(ref, "LOCAL-A")
            s.markImported(other, "LOCAL-B")
            assertEquals(mapOf(ref to "LOCAL-A"), s.importedLocalIds(listOf(ref)))
        }

        // --- the batch members a reconcile plans through (one read, one transaction each) ---

        clause("settled among answers the asked refs that are imported or unimportable", DownloadStoreState.EMPTY) { s ->
            val imported = AssetRef("DEVICE-A", "IMPORTED")
            val unimportable = AssetRef("DEVICE-A", "UNIMPORTABLE")
            val pending = AssetRef("DEVICE-A", "PENDING")
            val unconfirmed = AssetRef("DEVICE-A", "UNCONFIRMED")
            val notAsked = AssetRef("DEVICE-B", "IMPORTED-NOT-ASKED")
            val unknown = AssetRef("DEVICE-Z", "NEVER-PLANNED")
            listOf(imported, unimportable, pending, unconfirmed, notAsked).forEach { s.plan(it, "2026-06-30T10:00:00Z", resources()) }
            s.markImported(imported, "LOCAL-1")
            s.markImported(notAsked, "LOCAL-2")
            s.settleUnimportable(unimportable)
            s.recordCreatedLocalId(unconfirmed, "LOCAL-3")

            assertEquals(
                setOf(imported, unimportable),
                s.settledAmong(listOf(imported, unimportable, pending, unconfirmed, unknown)),
                "both terminal states answer yes; a pending, an unconfirmed and an unknown ref do not, and an unasked one is not reported",
            )
            assertTrue(s.settledAmong(emptyList()).isEmpty())
        }

        clause("plan all records every asset with its resources", DownloadStoreState.EMPTY) { s ->
            val other = AssetRef("DEVICE-B", "ASSET-R")
            s.planAll(
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
                s.pendingDownloads().map { it.ref to it.resource.resourceKey }.toSet(),
            )
            assertEquals(2, s.counts().stillArriving)
            s.markStaged(other, "ASSET-R-primary.heic", "/stage/r.heic")
            assertEquals(listOf(other), s.importableAssets().map { it.ref }, "an asset's resources landed with it")
            assertEquals("2026-06-30T11:00:00Z", s.importableAssets().single().creationDate)
        }

        clause("plan all re-plans exactly as plan does", DownloadStoreState.EMPTY) { s ->
            val done = AssetRef("DEVICE-B", "DONE")
            s.plan(ref, "2026-06-30T10:00:00Z", resources())
            s.markStaged(ref, "ASSET-Q-primary.heic", "/stage/primary.heic")
            s.plan(done, "2026-06-30T10:00:00Z", resources())
            s.markImported(done, "LOCAL-DONE")

            val rotated = listOf(
                PlannedResource("ASSET-Q-primary.heic", "https://e/primary?sig=NEW", "primary", "image/heic", "IMG.HEIC"),
                PlannedResource("ASSET-Q-live.mov", "https://e/live?sig=NEW", "live", "video/quicktime", "IMG.MOV"),
            )
            s.planAll(listOf(PlannedAsset(ref, "2026-06-30T10:00:00Z", rotated), PlannedAsset(done, "2026-06-30T10:00:00Z", rotated)))

            val pending = s.pendingDownloads()
            assertEquals(listOf(ref to "https://e/live?sig=NEW"), pending.map { it.ref to it.resource.url }, "only the unstaged resource is refreshed")
            assertEquals("/stage/primary.heic", s.stagedResources(ref).single().stagedPath, "a staged resource keeps its staging")
            assertTrue(s.isSettled(done), "and a terminal row is never downgraded")
            assertEquals(setOf("LOCAL-DONE"), s.suppressedLocalIds())
        }

        clause("mark all enqueued puts every marked asset in flight", DownloadStoreState.EMPTY) { s ->
            val other = AssetRef("DEVICE-B", "ASSET-R")
            s.planAll(listOf(PlannedAsset(ref, "2026-06-30T10:00:00Z", resources()), PlannedAsset(other, "2026-06-30T11:00:00Z", resources())))
            assertEquals(0, s.counts().inFlight)
            s.markAllEnqueued(emptyList())
            assertEquals(0, s.counts().inFlight, "an empty batch marks nothing")

            s.markAllEnqueued(s.pendingDownloads())
            assertEquals(2, s.counts().inFlight, "asset-counted, every asset of the batch")

            s.markStaged(ref, "ASSET-Q-primary.heic", "/p")
            s.markStaged(ref, "ASSET-Q-live.mov", "/l")
            assertEquals(1, s.counts().inFlight, "staging still supersedes a batch mark")
        }
    }

    private val ref = AssetRef("DEVICE-A", "ASSET-Q")
    private fun resources() = listOf(
        PlannedResource("ASSET-Q-primary.heic", "https://e/primary", "primary", "image/heic", "IMG.HEIC"),
        PlannedResource("ASSET-Q-live.mov", "https://e/live", "live", "video/quicktime", "IMG.MOV"),
    )






















    // --- imported local identifiers by ref (capability `receiving-photos`; read by the event album's gather) ---
}
