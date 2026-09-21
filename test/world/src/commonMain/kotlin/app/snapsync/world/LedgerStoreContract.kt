package app.snapsync.world

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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The storage-seam contract every [LedgerStore] must satisfy (sync-ledger spec). Concrete
 * backends bind [createBackend]; the same scenarios run unchanged against each.
 *
 * Its guarded-write and presence scenarios live in the base, [LedgerRecordGuardContract] — a split for
 * size only (the harness tier's `LargeClass` ceiling), so every binding still runs both halves once.
 */
abstract class LedgerStoreContract : LedgerRecordGuardContract() {

    @Test
    fun `a recorded entry round-trips field for field`() = runTest {
        val backend = createBackend()
        val entry = entry(assetId = "A", state = LedgerState.COMPLETED, destinationPath = "/a")

        assertTrue(backend.recordUnlessSettled(entry), "a new key always applies")

        assertEquals(entry, backend.get(entry.key))
    }

    @Test
    fun `a row is resolvable by the destination its upload was addressed to`() = runTest {
        val backend = createBackend()
        val path = "/api/v2/files/devices/D/cloud-1/primary"

        backend.recordUnlessSettled(entry(destinationPath = path))

        assertEquals(entry().key, backend.entryForDestination(path)?.key)
    }

    @Test
    fun `a row recorded without a destination is never matched and stays usable`() = runTest {
        val backend = createBackend()

        // A row written before the ledger kept a destination — the state every device carries after an
        // upgrade. It must read back normally and simply not answer a destination lookup, because the
        // tier that reads it falls back to the older recovery for exactly these rows.
        backend.recordUnlessSettled(entry())

        assertNull(backend.entryForDestination("/api/v2/files/devices/D/cloud-1/primary"))
        assertEquals(entry(), backend.get(entry().key))
    }

    @Test
    fun `an unknown destination resolves to nothing`() = runTest {
        val backend = createBackend()
        assertNull(backend.entryForDestination("/api/v2/files/devices/D/never/primary"))
    }

    @Test
    fun `unknown key reads null`() = runTest {
        assertNull(createBackend().get("never-put"))
    }

    @Test
    fun `empty ledger aggregates to zero counts`() = runTest {
        assertEquals(LedgerAggregates(0, 0), createBackend().aggregates())
    }

    @Test
    fun `photos count by asset - one photo per distinct asset`() = runTest {
        val backend = createBackend()
        backend.recordUnlessSettled(entry(key = "a", state = LedgerState.REQUESTED))
        backend.recordUnlessSettled(entry(key = "b", state = LedgerState.DISCOVERED))
        backend.recordUnlessSettled(entry(key = "c", state = LedgerState.COMPLETED))
        backend.recordUnlessSettled(entry(key = "d", state = LedgerState.COMPLETED))

        assertEquals(LedgerAggregates(pending = 2, completed = 2), backend.aggregates())
    }

    @Test
    fun `a photo counts complete only when all its resources are`() = runTest {
        val backend = createBackend()
        backend.recordUnlessSettled(entry(key = "P-photo.jpg", assetId = "P", state = LedgerState.COMPLETED))
        backend.recordUnlessSettled(entry(key = "P-video.mov", assetId = "P", state = LedgerState.REQUESTED))

        assertEquals(LedgerAggregates(pending = 1, completed = 0), backend.aggregates())
    }

    @Test
    fun `mixed photos - one complete and one partial`() = runTest {
        val backend = createBackend()
        backend.recordUnlessSettled(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED))
        backend.recordUnlessSettled(entry(key = "A-video.mov", assetId = "A", state = LedgerState.COMPLETED))
        backend.recordUnlessSettled(entry(key = "B-photo.jpg", assetId = "B", state = LedgerState.COMPLETED))
        backend.recordUnlessSettled(entry(key = "B-edit.jpg", assetId = "B", state = LedgerState.DISCOVERED))

        assertEquals(LedgerAggregates(pending = 1, completed = 1), backend.aggregates())
    }

    @Test
    fun `pendingResources returns only non-COMPLETED rows paired with their asset`() = runTest {
        val backend = createBackend()
        backend.recordUnlessSettled(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED))
        backend.recordUnlessSettled(entry(key = "A-video.mov", assetId = "A", state = LedgerState.COMPLETED))
        backend.recordUnlessSettled(entry(key = "B-photo.jpg", assetId = "B", state = LedgerState.REQUESTED))
        backend.recordUnlessSettled(entry(key = "B-edit.jpg", assetId = "B", state = LedgerState.DISCOVERED))

        assertEquals(
            setOf(PendingResource("B", "B-photo.jpg"), PendingResource("B", "B-edit.jpg")),
            backend.pendingResources().toSet(),
        )
    }

    @Test
    fun `pendingResources is empty when every row is complete`() = runTest {
        val backend = createBackend()
        backend.recordUnlessSettled(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED))

        assertEquals(emptyList(), backend.pendingResources())
    }

    @Test
    fun `an applied record dings an active changes collector`() = runTest {
        val backend = createBackend()
        var dings = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            backend.changes.collect { dings++ }
        }

        backend.recordUnlessSettled(entry())
        runCurrent()

        assertEquals(1, dings)
    }

    @Test
    fun `writer records are self-contained entries`() = runTest {
        val backend = createBackend()
        val writer = LedgerWriter(backend)

        writer.recordRequested(res("k", "A"))
        assertEquals(entry("k", "A", LedgerState.REQUESTED), writer.entry("k"))

        writer.recordFailed(res("k", "A"))
        assertEquals(entry("k", "A", LedgerState.DISCOVERED), writer.entry("k"), "a failure returns the row to the work")
    }

    @Test
    fun `resetTo replaces every row with the baseline verbatim`() = runTest {
        val backend = createBackend()
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

    @Test
    fun `resetTo dings an active changes collector exactly once`() = runTest {
        val backend = createBackend()
        var dings = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }

        backend.resetTo(listOf(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED)))
        runCurrent()

        assertEquals(1, dings)
    }

    @Test
    fun `demoteRequested returns only REQUESTED rows to DISCOVERED and keeps their detail`() = runTest {
        val backend = createBackend()
        val requested = entry(key = "R-photo.jpg", assetId = "R", state = LedgerState.REQUESTED)
        backend.recordUnlessSettled(entry(key = "D-photo.jpg", assetId = "D", state = LedgerState.DISCOVERED))
        backend.recordUnlessSettled(requested)
        backend.recordUnlessSettled(entry(key = "C-photo.jpg", assetId = "C", state = LedgerState.COMPLETED))

        backend.demoteRequested()

        // The orphaned REQUESTED row is kept, demoted, and otherwise field-for-field what was recorded.
        assertEquals(requested.withState(LedgerState.DISCOVERED), backend.get("R-photo.jpg"))
        assertEquals(LedgerState.DISCOVERED, backend.get("D-photo.jpg")?.state)
        assertEquals(LedgerState.COMPLETED, backend.get("C-photo.jpg")?.state) // dedup truth kept
    }

    @Test
    fun `a demoted row is returned by the work read without a walk`() = runTest {
        val backend = createBackend()
        backend.recordUnlessSettled(entry(key = "R-photo.jpg", assetId = "R", state = LedgerState.REQUESTED))

        backend.demoteRequested()

        assertTrue(backend.rowsNeedingJob().any { it.key == "R-photo.jpg" })
    }

    @Test
    fun `demoteRequested dings an active changes collector`() = runTest {
        val backend = createBackend()
        backend.recordUnlessSettled(entry(key = "R-photo.jpg", assetId = "R", state = LedgerState.REQUESTED))
        var dings = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }

        backend.demoteRequested()
        runCurrent()

        assertEquals(1, dings)
    }

    @Test
    fun `resetTo with an empty baseline empties the store`() = runTest {
        val backend = createBackend()
        backend.recordUnlessSettled(entry(key = "a", assetId = "a"))
        backend.recordUnlessSettled(entry(key = "b", assetId = "b"))

        backend.resetTo(emptyList())

        assertNull(backend.get("a"))
        assertEquals(LedgerAggregates(0, 0), backend.aggregates())
    }

    @Test
    fun `recording converges on assetId and state`() = runTest {
        val backend = createBackend()
        val writer = LedgerWriter(backend)

        writer.recordFailed(res("k", "A"))
        writer.recordFailed(res("k", "A"))

        val entry = writer.entry("k")!!
        assertEquals("A", entry.assetId)
        assertEquals(LedgerState.DISCOVERED, entry.state)
    }

    // ── manifest detail (capability `sync-ledger`) ────────────────────────────────────────────────

    @Test
    fun `a recorded row round-trips its manifest detail`() = runTest {
        val backend = createBackend()
        LedgerWriter(backend).recordRequested(res())

        val row = backend.get("cloud-1-ios.photo.heic")!!
        assertEquals(CREATION_DATE, row.creationDate)
        assertEquals(ResourceRole.PRIMARY, row.role)
        assertEquals("image/heic", row.contentType)
        assertEquals("IMG_0001.HEIC", row.originalFilename)
    }

    @Test
    fun `a state transition never erases the manifest detail`() = runTest {
        // THE invariant behind the ledger-backed manifest. A retry-spent job comes back from the platform
        // as a key, and the cycle rebuilds its Resource from that key alone — with empty metadata,
        // because adjudicating a failure needs nothing else. If the failure's write overwrote with those
        // blanks, the row would lose its capture date, and — the manifest projecting every row whatever
        // its state — the photo would drop out of the event union while its upload was being retried.
        val backend = createBackend()
        val writer = LedgerWriter(backend)
        writer.recordRequested(res())

        val bare = Resource("cloud-1-ios.photo.heic", "cloud-1", "image/heic", emptyMap(), Unit)
        writer.recordFailed(bare)

        val row = backend.get("cloud-1-ios.photo.heic")!!
        assertEquals(LedgerState.DISCOVERED, row.state)
        assertEquals(CREATION_DATE, row.creationDate, "the detail written at REQUESTED survives")
        assertEquals("IMG_0001.HEIC", row.originalFilename)
    }

    @Test
    fun `the manifest projection is not state-scoped because it lists intent`() = runTest {
        val backend = createBackend()
        val writer = LedgerWriter(backend)
        backend.seedCompleted(res("done.heic", "A"))
        writer.recordRequested(res("inflight.heic", "B"))
        writer.recordDiscovered(listOf(res("found.heic", "D")))
        writer.recordFailed(res("failed.heic", "F"))
        // A row the re-join reconcile seeded from a filename listing: COMPLETED, but no capture date.
        // The read no longer excludes it — the membership's policy does, because an empty capture date
        // sorts before every real cutoff (capability `photo-selection-policy`).
        backend.recordUnlessSettled(LedgerEntry("seeded.heic", "C", LedgerState.COMPLETED))

        assertEquals(
            listOf("done.heic", "failed.heic", "found.heic", "inflight.heic", "seeded.heic"),
            backend.manifestRows().map { it.key }.sorted(),
            "every row is declared: what a device INTENDS to provide does not depend on how " +
                "far its bytes have got",
        )
    }

    // ── The guarded terminal write and the narrow reads ─────────────────────────────────────────────

    @Test
    fun `markTerminal flips a REQUESTED row and says it applied`() = runTest {
        val backend = createBackend()
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

    @Test
    fun `markTerminal refuses a row that is not REQUESTED`() = runTest {
        val backend = createBackend()
        backend.seedCompleted(res("a.heic", "A"))

        assertFalse(backend.markTerminal("a.heic", TerminalOutcome.FAILED), "it did not apply")
        assertEquals(LedgerState.COMPLETED, backend.get("a.heic")!!.state, "and clobbered nothing")
    }

    @Test
    fun `a failed terminal outcome returns the row to the work read`() = runTest {
        val backend = createBackend()
        LedgerWriter(backend).recordRequested(res("a.heic", "A"), destinationPath = "/a.heic")

        assertTrue(backend.markTerminal("a.heic", TerminalOutcome.FAILED), "it applied")

        val row = backend.get("a.heic")!!
        assertEquals(LedgerState.DISCOVERED, row.state, "a failure is recorded as needing a job")
        assertEquals("/a.heic", row.destinationPath, "every other column preserved")
        assertEquals(listOf("a.heic"), backend.rowsNeedingJob().map { it.key })
        assertEquals(emptySet(), backend.requestedKeys())
    }

    @Test
    fun `markTerminal on an absent key writes nothing and says so`() = runTest {
        val backend = createBackend()

        assertFalse(backend.markTerminal("ghost.heic", TerminalOutcome.COMPLETED))
        assertNull(backend.get("ghost.heic"), "a guarded write never resurrects a pruned row")
    }

    @Test
    fun `an applied markTerminal dings an active changes collector`() = runTest {
        val backend = createBackend()
        LedgerWriter(backend).recordRequested(res("a.heic", "A"))
        var dings = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }
        runCurrent()

        backend.markTerminal("a.heic", TerminalOutcome.COMPLETED)
        runCurrent()

        assertEquals(1, dings, "it changed the truth, so watchers must re-read it")
        job.cancel()
    }

    @Test
    fun `requestedKeys is REQUESTED only - never the whole backlog`() = runTest {
        val backend = createBackend()
        val writer = LedgerWriter(backend)
        writer.recordRequested(res("flight.heic", "A"))
        writer.recordFailed(res("bad.heic", "B"))
        backend.seedCompleted(res("done.heic", "C"))
        writer.recordRequested(res("up.heic", "D"))
        backend.markTerminal("up.heic", TerminalOutcome.COMPLETED)

        // A failed row is already back in the work read and a COMPLETED row has landed; handing either to the
        // stranded pass re-reports a loss that did not happen.
        assertEquals(setOf("flight.heic"), backend.requestedKeys())
    }

    @Test
    fun `a completion recorded by the platform settles the photo everywhere at once`() = runTest {
        // No state sits between "the bytes are stored" and "settled" any more: the moment the platform's
        // callback records the outcome, the photo counts completed and nothing is outstanding for it —
        // without waiting for any cycle.
        val backend = createBackend()
        LedgerWriter(backend).recordRequested(res("up.heic", "A"))
        backend.markTerminal("up.heic", TerminalOutcome.COMPLETED)

        assertEquals(LedgerAggregates(pending = 0, completed = 1), backend.aggregates())
        assertEquals(emptyList(), backend.pendingResources())
        assertEquals(emptyList(), backend.rowsNeedingJob())
        assertEquals(listOf("up.heic"), backend.manifestRows().map { it.key }, "and it is still declared")
    }

    @Test
    fun `the backfill fills a bare row and leaves an enriched one alone`() = runTest {
        val backend = createBackend()
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

    // --- the work-source read (capability `sync-ledger`) --------------------------------------------

    @Test
    fun `rowsNeedingJob returns DISCOVERED rows and nothing else`() = runTest {
        val backend = createBackend()
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

    @Test
    fun `rowsNeedingJob returns every needing row unbounded in a stable key order`() = runTest {
        val backend = createBackend()
        for (k in listOf("c.heic", "a.heic", "d.heic", "b.heic")) {
            backend.recordUnlessSettled(entry(key = k, assetId = k, state = LedgerState.DISCOVERED))
        }

        // Ordered so a caller's slice is deterministic rather than whatever the storage returned — and
        // UNBOUNDED, which is the half that matters (capability `sync-ledger`). The cycle bounds what it
        // RESOLVES, after admitting these rows against the membership's current policy; a bound applied
        // here instead would let excluded rows sorting ahead of admitted ones fill the slice on every
        // cycle, and the admitted work further down would never be reached.
        assertEquals(
            listOf("a.heic", "b.heic", "c.heic", "d.heic"),
            backend.rowsNeedingJob().map { it.key },
        )
    }

    @Test
    fun `a DISCOVERED row is backlog and declared but no stranding candidate`() = runTest {
        val backend = createBackend()
        backend.recordUnlessSettled(entry(key = "a.heic", assetId = "A", state = LedgerState.DISCOVERED))

        // Counted as outstanding everywhere...
        assertEquals(LedgerAggregates(pending = 1, completed = 0), backend.aggregates())
        assertEquals(listOf(PendingResource("A", "a.heic")), backend.pendingResources())
        // ...and it IS declared: the manifest states what this device will provide, and the backend
        // keeps the asset out of the union until every declared role has a resource.
        assertEquals(listOf("a.heic"), backend.manifestRows().map { it.key })
        // ...and it is not a stranding candidate: a row that never had a job cannot be a lost transfer,
        // and surfacing it would write a failure that did not happen.
        assertEquals(emptySet(), backend.requestedKeys())
    }

    @Test
    fun `a guarded terminal write cannot touch a DISCOVERED row`() = runTest {
        val backend = createBackend()
        backend.recordUnlessSettled(entry(key = "a.heic", assetId = "A", state = LedgerState.DISCOVERED))

        // `markTerminal` is guarded on REQUESTED, which is what lets the walk write a row without racing
        // the platform's delegate for a key it has never issued a job for.
        assertFalse(backend.markTerminal("a.heic", TerminalOutcome.COMPLETED))
        assertEquals(LedgerState.DISCOVERED, backend.get("a.heic")!!.state)
    }
}
