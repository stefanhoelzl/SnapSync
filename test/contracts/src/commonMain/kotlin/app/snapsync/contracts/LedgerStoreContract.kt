@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.contracts

import app.snapsync.model.toLedgerRow
import app.snapsync.model.ResourceRole
import app.snapsync.model.Resource
import app.snapsync.model.RESOURCE_META_ORIGINAL_FILENAME
import app.snapsync.model.RESOURCE_META_MIME
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.LedgerAggregates
import app.snapsync.ports.LedgerStore
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.feature.upload.LedgerWriter
import app.snapsync.model.PendingResource
import app.snapsync.model.TerminalOutcome

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent

/** The ledger contract's state vocabulary. Its clauses have no state distinction yet: every one starts from an empty store. */
enum class LedgerStoreState { EMPTY }

/**
 * The storage-seam contract every [LedgerStore] must satisfy (capability `photo-sharing`; mechanism:
 * `docs/architecture.md`). Each implementation is bound once per host it runs on; the same clauses run unchanged
 * against each.
 *
 * Its guarded-write and presence clauses live in [recordGuardClauses] and its manifest-version clauses in
 * [manifestVersionClauses] — a split for size only (the harness tier's `LargeClass` ceiling); the one list
 * below holds all three parts, so every binding runs every clause once.
 */
object LedgerStoreContract : Contract<LedgerStoreState, LedgerStore>("LedgerStore") {

    override val clauses = clauses {
        recordGuardClauses()
        manifestVersionClauses()

        clause("a recorded entry round-trips field for field", LedgerStoreState.EMPTY) { backend ->
            val entry = entry(assetId = "A", state = LedgerState.COMPLETED, destinationPath = "/a")

            assertTrue(backend.recordUnlessSettled(entry), "a new key always applies")

            assertEquals(entry, backend.get(entry.key))
        }

        clause("a row is resolvable by the destination its upload was addressed to", LedgerStoreState.EMPTY) { backend ->
            val path = "/api/v2/files/devices/D/cloud-1/primary"

            backend.recordUnlessSettled(entry(destinationPath = path))

            assertEquals(entry().key, backend.entryForDestination(path)?.key)
        }

        clause("a row recorded without a destination is never matched and stays usable", LedgerStoreState.EMPTY) { backend ->

            // A row written before the ledger kept a destination — the state every device carries after an
            // upgrade. It must read back normally and simply not answer a destination lookup, because the
            // tier that reads it falls back to the older recovery for exactly these rows.
            backend.recordUnlessSettled(entry())

            assertNull(backend.entryForDestination("/api/v2/files/devices/D/cloud-1/primary"))
            assertEquals(entry(), backend.get(entry().key))
        }

        clause("an unknown destination resolves to nothing", LedgerStoreState.EMPTY) { backend ->
            assertNull(backend.entryForDestination("/api/v2/files/devices/D/never/primary"))
        }

        clause("unknown key reads null", LedgerStoreState.EMPTY) { backend ->
            assertNull(backend.get("never-put"))
        }

        clause("empty ledger aggregates to zero counts", LedgerStoreState.EMPTY) { backend ->
            assertEquals(LedgerAggregates(0, 0), backend.aggregates())
        }

        clause("photos count by asset - one photo per distinct asset", LedgerStoreState.EMPTY) { backend ->
            backend.recordUnlessSettled(entry(key = "a", state = LedgerState.REQUESTED))
            backend.recordUnlessSettled(entry(key = "b", state = LedgerState.DISCOVERED))
            backend.recordUnlessSettled(entry(key = "c", state = LedgerState.COMPLETED))
            backend.recordUnlessSettled(entry(key = "d", state = LedgerState.COMPLETED))

            assertEquals(LedgerAggregates(pending = 2, completed = 2), backend.aggregates())
        }

        clause("a photo counts complete only when all its resources are", LedgerStoreState.EMPTY) { backend ->
            backend.recordUnlessSettled(entry(key = "P-photo.jpg", assetId = "P", state = LedgerState.COMPLETED))
            backend.recordUnlessSettled(entry(key = "P-video.mov", assetId = "P", state = LedgerState.REQUESTED))

            assertEquals(LedgerAggregates(pending = 1, completed = 0), backend.aggregates())
        }

        clause("mixed photos - one complete and one partial", LedgerStoreState.EMPTY) { backend ->
            backend.recordUnlessSettled(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED))
            backend.recordUnlessSettled(entry(key = "A-video.mov", assetId = "A", state = LedgerState.COMPLETED))
            backend.recordUnlessSettled(entry(key = "B-photo.jpg", assetId = "B", state = LedgerState.COMPLETED))
            backend.recordUnlessSettled(entry(key = "B-edit.jpg", assetId = "B", state = LedgerState.DISCOVERED))

            assertEquals(LedgerAggregates(pending = 1, completed = 1), backend.aggregates())
        }

        clause("assetProgress answers a photo done only when all its resources are", LedgerStoreState.EMPTY) { backend ->
            backend.recordUnlessSettled(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED))
            backend.recordUnlessSettled(entry(key = "A-video.mov", assetId = "A", state = LedgerState.COMPLETED))
            backend.recordUnlessSettled(entry(key = "B-photo.jpg", assetId = "B", state = LedgerState.COMPLETED))
            backend.recordUnlessSettled(entry(key = "B-edit.jpg", assetId = "B", state = LedgerState.DISCOVERED))

            assertEquals(mapOf("A" to true, "B" to false), backend.assetProgress())
        }

        clause("assetProgress on an empty ledger answers nothing", LedgerStoreState.EMPTY) { backend ->
            assertEquals(emptyMap(), backend.assetProgress())
        }

        clause("assetProgress agrees with the aggregate read", LedgerStoreState.EMPTY) { backend ->
            backend.recordUnlessSettled(entry(key = "a", state = LedgerState.REQUESTED))
            backend.recordUnlessSettled(entry(key = "b", state = LedgerState.DISCOVERED))
            backend.recordUnlessSettled(entry(key = "c", state = LedgerState.COMPLETED))

            val progress = backend.assetProgress()
            val aggregates = backend.aggregates()
            assertEquals(aggregates.completed, progress.count { it.value })
            assertEquals(aggregates.pending, progress.count { !it.value })
        }

        clause("assetProgress sees a photo done as soon as its last upload is recorded", LedgerStoreState.EMPTY) { backend ->
            LedgerWriter(backend).recordRequested(res("a.heic", "A"), destinationPath = "/a.heic")
            backend.markTerminal("a.heic", TerminalOutcome.COMPLETED)

            assertEquals(mapOf("A" to true), backend.assetProgress())
        }

        clause("pendingResources returns only non-COMPLETED rows paired with their asset", LedgerStoreState.EMPTY) { backend ->
            backend.recordUnlessSettled(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED))
            backend.recordUnlessSettled(entry(key = "A-video.mov", assetId = "A", state = LedgerState.COMPLETED))
            backend.recordUnlessSettled(entry(key = "B-photo.jpg", assetId = "B", state = LedgerState.REQUESTED))
            backend.recordUnlessSettled(entry(key = "B-edit.jpg", assetId = "B", state = LedgerState.DISCOVERED))

            assertEquals(
                setOf(PendingResource("B", "B-photo.jpg"), PendingResource("B", "B-edit.jpg")),
                backend.pendingResources().toSet(),
            )
        }

        clause("pendingResources is empty when every row is complete", LedgerStoreState.EMPTY) { backend ->
            backend.recordUnlessSettled(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED))

            assertEquals(emptyList(), backend.pendingResources())
        }

        clause("an applied record dings an active changes collector", LedgerStoreState.EMPTY) { backend ->
            var dings = 0
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                backend.changes.collect { dings++ }
            }

            backend.recordUnlessSettled(entry())
            runCurrent()

            assertEquals(1, dings)
        }

        clause("writer records are self-contained entries", LedgerStoreState.EMPTY) { backend ->
            val writer = LedgerWriter(backend)

            writer.recordRequested(res("k", "A"))
            assertEquals(entry("k", "A", LedgerState.REQUESTED), writer.entry("k"))

            writer.recordFailed(res("k", "A"))
            assertEquals(entry("k", "A", LedgerState.DISCOVERED), writer.entry("k"), "a failure returns the row to the work")
        }

        clause("resetTo replaces every row with the baseline verbatim", LedgerStoreState.EMPTY) { backend ->
            backend.recordUnlessSettled(entry(key = "old-1", assetId = "old"))
            backend.recordUnlessSettled(entry(key = "old-2", assetId = "old"))

            val seed = listOf(
                entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED),
                entry(key = "B-photo.jpg", assetId = "B", state = LedgerState.COMPLETED),
            )
            backend.resetTo(seed)

            assertNull(backend.get("old-1"))
            assertNull(backend.get("old-2"))
            assertEquals(seed[0], backend.get("A-photo.jpg"))
            assertEquals(seed[1], backend.get("B-photo.jpg"))
            assertEquals(LedgerAggregates(pending = 0, completed = 2), backend.aggregates())
        }

        clause("resetTo dings an active changes collector exactly once", LedgerStoreState.EMPTY) { backend ->
            var dings = 0
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }

            backend.resetTo(listOf(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED)))
            runCurrent()

            assertEquals(1, dings)
        }

        clause("resetTo with an empty baseline empties the store", LedgerStoreState.EMPTY) { backend ->
            backend.recordUnlessSettled(entry(key = "a", assetId = "a"))
            backend.recordUnlessSettled(entry(key = "b", assetId = "b"))

            backend.resetTo(emptyList())

            assertNull(backend.get("a"))
            assertEquals(LedgerAggregates(0, 0), backend.aggregates())
        }

        clause("recording converges on assetId and state", LedgerStoreState.EMPTY) { backend ->
            val writer = LedgerWriter(backend)

            writer.recordFailed(res("k", "A"))
            writer.recordFailed(res("k", "A"))

            val entry = writer.entry("k")!!
            assertEquals("A", entry.assetId)
            assertEquals(LedgerState.DISCOVERED, entry.state)
        }

        clause("a recorded row round-trips its manifest detail", LedgerStoreState.EMPTY) { backend ->
            LedgerWriter(backend).recordRequested(res())

            val row = backend.get("cloud-1-ios.photo.heic")!!
            assertEquals(CREATION_DATE, row.creationDate)
            assertEquals(ResourceRole.PRIMARY, row.role)
            assertEquals("image/heic", row.contentType)
            assertEquals("IMG_0001.HEIC", row.originalFilename)
        }

        clause("a state transition never erases the manifest detail", LedgerStoreState.EMPTY) { backend ->
            // THE invariant behind the ledger-backed manifest. A retry-spent job comes back from the platform
            // as a key, and the cycle rebuilds its Resource from that key alone — with empty metadata,
            // because adjudicating a failure needs nothing else. If the failure's write overwrote with those
            // blanks, the row would lose its capture date, and — the manifest projecting every row whatever
            // its state — the photo would drop out of the event union while its upload was being retried.
            val writer = LedgerWriter(backend)
            writer.recordRequested(res())

            val bare = Resource("cloud-1-ios.photo.heic", "cloud-1", "image/heic", emptyMap(), Unit)
            writer.recordFailed(bare)

            val row = backend.get("cloud-1-ios.photo.heic")!!
            assertEquals(LedgerState.DISCOVERED, row.state)
            assertEquals(CREATION_DATE, row.creationDate, "the detail written at REQUESTED survives")
            assertEquals("IMG_0001.HEIC", row.originalFilename)
        }

        clause("the manifest projection is not state-scoped because it lists intent", LedgerStoreState.EMPTY) { backend ->
            val writer = LedgerWriter(backend)
            backend.seedCompleted(res("done.heic", "A"))
            writer.recordRequested(res("inflight.heic", "B"))
            writer.recordDiscovered(listOf(res("found.heic", "D")))
            writer.recordFailed(res("failed.heic", "F"))
            // A row the join-time load seeded from a stored-file listing: COMPLETED, but no capture date.
            // The read no longer excludes it — the membership's policy does, because an empty capture date
            // sorts before every real cutoff (capability `photo-sharing`).
            backend.recordUnlessSettled(LedgerEntry("seeded.heic", "C", LedgerState.COMPLETED))

            assertEquals(
                listOf("done.heic", "failed.heic", "found.heic", "inflight.heic", "seeded.heic"),
                backend.manifestRows().map { it.key }.sorted(),
                "every row is declared: what a device INTENDS to provide does not depend on how " +
                    "far its bytes have got",
            )
        }

        clause("markTerminal flips a REQUESTED row and says it applied", LedgerStoreState.EMPTY) { backend ->
            LedgerWriter(backend).recordRequested(res("a.heic", "A"), destinationPath = "/a.heic")

            assertTrue(backend.markTerminal("a.heic", TerminalOutcome.COMPLETED), "it applied")
            val row = backend.get("a.heic")!!
            assertEquals(LedgerState.COMPLETED, row.state)
            // Every other column is the statement's business to preserve, not the caller's: the party that
            // records a terminal outcome is a platform callback holding nothing but the key.
            assertEquals("A", row.assetId)
            assertEquals("/a.heic", row.destinationPath)
            assertEquals(CREATION_DATE, row.creationDate, "the manifest detail survives the transition")
        }

        clause("markTerminal refuses a row that is not REQUESTED", LedgerStoreState.EMPTY) { backend ->
            backend.seedCompleted(res("a.heic", "A"))

            assertFalse(backend.markTerminal("a.heic", TerminalOutcome.FAILED), "it did not apply")
            assertEquals(LedgerState.COMPLETED, backend.get("a.heic")!!.state, "and clobbered nothing")
        }

        clause("a failed terminal outcome returns the row to the work read", LedgerStoreState.EMPTY) { backend ->
            LedgerWriter(backend).recordRequested(res("a.heic", "A"), destinationPath = "/a.heic")

            assertTrue(backend.markTerminal("a.heic", TerminalOutcome.FAILED), "it applied")

            val row = backend.get("a.heic")!!
            assertEquals(LedgerState.DISCOVERED, row.state, "a failure is recorded as needing a job")
            assertEquals("/a.heic", row.destinationPath, "every other column preserved")
            assertEquals(listOf("a.heic"), backend.rowsNeedingJob().map { it.key })
        }

        clause("markTerminal on an absent key writes nothing and says so", LedgerStoreState.EMPTY) { backend ->

            assertFalse(backend.markTerminal("ghost.heic", TerminalOutcome.COMPLETED))
            assertNull(backend.get("ghost.heic"), "a guarded write never resurrects a pruned row")
        }

        clause("an applied markTerminal dings an active changes collector", LedgerStoreState.EMPTY) { backend ->
            LedgerWriter(backend).recordRequested(res("a.heic", "A"))
            var dings = 0
            val job = launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }
            runCurrent()

            backend.markTerminal("a.heic", TerminalOutcome.COMPLETED)
            runCurrent()

            assertEquals(1, dings, "it changed the truth, so watchers must re-read it")
            job.cancel()
        }

        clause("a completion recorded by the platform settles the photo everywhere at once", LedgerStoreState.EMPTY) { backend ->
            // No state sits between "the bytes are stored" and "settled" any more: the moment the platform's
            // callback records the outcome, the photo counts completed and nothing is outstanding for it —
            // without waiting for any cycle.
            LedgerWriter(backend).recordRequested(res("up.heic", "A"))
            backend.markTerminal("up.heic", TerminalOutcome.COMPLETED)

            assertEquals(LedgerAggregates(pending = 0, completed = 1), backend.aggregates())
            assertEquals(emptyList(), backend.pendingResources())
            assertEquals(emptyList(), backend.rowsNeedingJob())
            assertEquals(listOf("up.heic"), backend.manifestRows().map { it.key }, "and it is still declared")
        }

        clause("the backfill fills a bare row and leaves an enriched one alone", LedgerStoreState.EMPTY) { backend ->
            backend.recordUnlessSettled(LedgerEntry("seeded.heic", "C", LedgerState.COMPLETED, destinationPath = "/s"))

            backend.backfillManifestDetail(res("seeded.heic", "C").toLedgerRow(LedgerState.DISCOVERED))
            val filled = backend.get("seeded.heic")!!
            assertEquals(CREATION_DATE, filled.creationDate)
            assertEquals(LedgerState.COMPLETED, filled.state, "the sweep touches the detail only — state is not its business")
            assertEquals("/s", filled.destinationPath)

            // Idempotent: a second sweep with a DIFFERENT value must not overwrite what is already there.
            val other = Resource(
                "seeded.heic", "C", "image/heic",
                mapOf(RESOURCE_META_CREATION_DATE to "2099-01-01T00:00:00Z"), Unit,
            )
            backend.backfillManifestDetail(other.toLedgerRow(LedgerState.COMPLETED))
            assertEquals(CREATION_DATE, backend.get("seeded.heic")!!.creationDate)
        }

        clause("rowsNeedingJob returns DISCOVERED rows and nothing else", LedgerStoreState.EMPTY) { backend ->
            backend.recordUnlessSettled(entry(key = "a.heic", assetId = "A", state = LedgerState.DISCOVERED))
            // A failure the writer recorded lands in DISCOVERED too: one fact, one state.
            LedgerWriter(backend).recordFailed(res("b.heic", "B"))
            backend.recordUnlessSettled(entry(key = "c.heic", assetId = "C", state = LedgerState.REQUESTED))
            backend.recordUnlessSettled(entry(key = "e.heic", assetId = "E", state = LedgerState.COMPLETED))

            // No live job, no bytes on the backend. REQUESTED has a job and COMPLETED is settled — neither is work.
            assertEquals(
                listOf("a.heic", "b.heic"),
                backend.rowsNeedingJob().map { it.key },
            )
        }

        clause("rowsNeedingJob returns every needing row unbounded in a stable key order", LedgerStoreState.EMPTY) { backend ->
            for (k in listOf("c.heic", "a.heic", "d.heic", "b.heic")) {
                backend.recordUnlessSettled(entry(key = k, assetId = k, state = LedgerState.DISCOVERED))
            }

            // Ordered so a caller's slice is deterministic rather than whatever the storage returned — and
            // UNBOUNDED, which is the half that matters (capability `photo-sharing`). The cycle bounds what it
            // RESOLVES, after admitting these rows against the membership's current policy; a bound applied
            // here instead would let excluded rows sorting ahead of admitted ones fill the slice on every
            // cycle, and the admitted work further down would never be reached.
            assertEquals(
                listOf("a.heic", "b.heic", "c.heic", "d.heic"),
                backend.rowsNeedingJob().map { it.key },
            )
        }

        clause("a DISCOVERED row is backlog and declared but no stranding candidate", LedgerStoreState.EMPTY) { backend ->
            backend.recordUnlessSettled(entry(key = "a.heic", assetId = "A", state = LedgerState.DISCOVERED))

            // Counted as outstanding everywhere...
            assertEquals(LedgerAggregates(pending = 1, completed = 0), backend.aggregates())
            assertEquals(listOf(PendingResource("A", "a.heic")), backend.pendingResources())
            // ...and it IS declared: the manifest states what this device will provide, and the backend
            // keeps the asset out of the union until every declared role has a resource.
            assertEquals(listOf("a.heic"), backend.manifestRows().map { it.key })
        }

        clause("a guarded terminal write cannot touch a DISCOVERED row", LedgerStoreState.EMPTY) { backend ->
            backend.recordUnlessSettled(entry(key = "a.heic", assetId = "A", state = LedgerState.DISCOVERED))

            // `markTerminal` is guarded on REQUESTED, which is what lets the walk write a row without racing
            // the platform's delegate for a key it has never issued a job for.
            assertFalse(backend.markTerminal("a.heic", TerminalOutcome.COMPLETED))
            assertEquals(LedgerState.DISCOVERED, backend.get("a.heic")!!.state)
        }
    }

    // ── The per-asset progress read (capability `photo-sharing`, "Per-asset progress read") ────────────













    // ── manifest detail (capability `photo-sharing`) ────────────────────────────────────────────────




    // ── The guarded terminal write and the narrow reads ─────────────────────────────────────────────








    // --- the work-source read (capability `photo-sharing`) --------------------------------------------
}
