package app.snapsync.world

import app.snapsync.model.LedgerAggregates
import app.snapsync.ports.LedgerStore
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.feature.upload.LedgerWriter
import app.snapsync.model.PendingResource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The storage-seam contract every [LedgerStore] must satisfy (sync-ledger spec). Concrete
 * backends bind [createBackend]; the same scenarios run unchanged against each.
 */
abstract class LedgerStoreContract {

    protected abstract fun createBackend(): LedgerStore

    // assetId defaults to the key, so a test that doesn't care about grouping gets one photo per
    // row (the historical per-row behaviour); multi-resource-photo tests pass an explicit assetId.
    // eventId defaults to the pre-provenance sentinel "" so tests that are not about provenance
    // stay readable; provenance tests pass an explicit eventId.
    private fun entry(
        key: String = "cloud-1-ios.photo.heic",
        assetId: String = key,
        state: LedgerState = LedgerState.REQUESTED,
        attempt: Int = 0,
        eventId: String = "",
    ) = LedgerEntry(key, assetId, state, attempt, eventId)

    @Test
    fun `put then get round-trips field for field`() = runTest {
        val backend = createBackend()
        val entry = entry(assetId = "A", state = LedgerState.COMPLETED, attempt = 3, eventId = "E1")

        backend.put(entry)

        assertEquals(entry, backend.get(entry.key))
    }

    @Test
    fun `eventId is stored verbatim including the pre-provenance sentinel`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "a", eventId = "E1"))
        backend.put(entry(key = "b", eventId = "")) // the 4.sqm migration default

        assertEquals("E1", backend.get("a")?.eventId)
        assertEquals("", backend.get("b")?.eventId)
    }

    @Test
    fun `put overwrites unconditionally - no precedence in the backend`() = runTest {
        val backend = createBackend()
        backend.put(entry(state = LedgerState.COMPLETED, attempt = 2))

        backend.put(entry(state = LedgerState.REQUESTED, attempt = 0))

        assertEquals(entry(state = LedgerState.REQUESTED, attempt = 0), backend.get(entry().key))
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
        backend.put(entry(key = "a", state = LedgerState.REQUESTED))
        backend.put(entry(key = "b", state = LedgerState.FAILED))
        backend.put(entry(key = "c", state = LedgerState.COMPLETED))
        backend.put(entry(key = "d", state = LedgerState.COMPLETED))

        assertEquals(LedgerAggregates(pending = 2, completed = 2), backend.aggregates())
    }

    @Test
    fun `a photo counts complete only when all its resources are`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "P-photo.jpg", assetId = "P", state = LedgerState.COMPLETED))
        backend.put(entry(key = "P-video.mov", assetId = "P", state = LedgerState.REQUESTED))

        assertEquals(LedgerAggregates(pending = 1, completed = 0), backend.aggregates())
    }

    @Test
    fun `mixed photos - one complete and one partial`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED))
        backend.put(entry(key = "A-video.mov", assetId = "A", state = LedgerState.COMPLETED))
        backend.put(entry(key = "B-photo.jpg", assetId = "B", state = LedgerState.COMPLETED))
        backend.put(entry(key = "B-edit.jpg", assetId = "B", state = LedgerState.FAILED))

        assertEquals(LedgerAggregates(pending = 1, completed = 1), backend.aggregates())
    }

    @Test
    fun `pendingResources returns only non-COMPLETED rows paired with their asset`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED))
        backend.put(entry(key = "A-video.mov", assetId = "A", state = LedgerState.COMPLETED))
        backend.put(entry(key = "B-photo.jpg", assetId = "B", state = LedgerState.REQUESTED))
        backend.put(entry(key = "B-edit.jpg", assetId = "B", state = LedgerState.FAILED))

        assertEquals(
            setOf(PendingResource("B", "B-photo.jpg"), PendingResource("B", "B-edit.jpg")),
            backend.pendingResources().toSet(),
        )
    }

    @Test
    fun `pendingResources is empty when every row is complete`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED))

        assertEquals(emptyList(), backend.pendingResources())
    }

    @Test
    fun `put dings an active changes collector`() = runTest {
        val backend = createBackend()
        var dings = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            backend.changes.collect { dings++ }
        }

        backend.put(entry())
        runCurrent()

        assertEquals(1, dings)
    }

    @Test
    fun `writer records are self-contained entries`() = runTest {
        val backend = createBackend()
        val writer = LedgerWriter(backend)

        writer.recordRequested("k", "A", attempt = 0, eventId = "E1")
        assertEquals(entry("k", "A", LedgerState.REQUESTED, 0, eventId = "E1"), writer.entry("k"))

        writer.recordFailed("k", "A", attempt = 0, eventId = "E1")
        assertEquals(entry("k", "A", LedgerState.FAILED, 0, eventId = "E1"), writer.entry("k"))

        writer.recordCompleted("k", "A", attempt = 1, eventId = "E1")
        assertEquals(entry("k", "A", LedgerState.COMPLETED, 1, eventId = "E1"), writer.entry("k"))
    }

    @Test
    fun `backfillEventId rewrites only the pre-provenance sentinel and nothing else`() = runTest {
        val backend = createBackend()
        // Two sentinel rows (as the 4.sqm migration leaves them) and one row another event recorded.
        backend.put(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED, attempt = 2, eventId = ""))
        backend.put(entry(key = "A-video.mov", assetId = "A", state = LedgerState.REQUESTED, attempt = 0, eventId = ""))
        backend.put(entry(key = "B-photo.jpg", assetId = "B", state = LedgerState.COMPLETED, attempt = 1, eventId = "OLD"))

        backend.backfillEventId("E1")

        // Sentinel rows gain the live event id; every other field is untouched.
        assertEquals(
            entry("A-photo.jpg", "A", LedgerState.COMPLETED, 2, eventId = "E1"),
            backend.get("A-photo.jpg"),
        )
        assertEquals(
            entry("A-video.mov", "A", LedgerState.REQUESTED, 0, eventId = "E1"),
            backend.get("A-video.mov"),
        )
        // A row already carrying provenance is never rewritten.
        assertEquals(
            entry("B-photo.jpg", "B", LedgerState.COMPLETED, 1, eventId = "OLD"),
            backend.get("B-photo.jpg"),
        )
    }

    @Test
    fun `backfillEventId is idempotent`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "a", eventId = ""))

        backend.backfillEventId("E1")
        backend.backfillEventId("E2") // a later sweep finds no sentinel rows — nothing changes

        assertEquals("E1", backend.get("a")?.eventId)
    }

    @Test
    fun `backfillEventId dings an active changes collector`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "a", eventId = ""))
        var dings = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }

        backend.backfillEventId("E1")
        runCurrent()

        assertEquals(1, dings)
    }

    @Test
    fun `writer exposes the backfill sweep`() = runTest {
        val backend = createBackend()
        val writer = LedgerWriter(backend)
        writer.recordCompleted("k", "A", attempt = 0, eventId = "")

        writer.backfillEventId("E1")

        assertEquals("E1", writer.entry("k")?.eventId)
    }

    @Test
    fun `deleteByAssetId removes only that asset's rows`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "A-photo.jpg", assetId = "A"))
        backend.put(entry(key = "A-video.mov", assetId = "A"))
        backend.put(entry(key = "B-photo.jpg", assetId = "B"))

        backend.deleteByAssetId("A")

        assertNull(backend.get("A-photo.jpg"))
        assertNull(backend.get("A-video.mov"))
        assertEquals("B-photo.jpg", backend.get("B-photo.jpg")?.key)
    }

    @Test
    fun `deleteByAssetId dings an active changes collector`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "A-photo.jpg", assetId = "A"))
        var dings = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }

        backend.deleteByAssetId("A")
        runCurrent()

        assertEquals(1, dings)
    }

    @Test
    fun `retainAssets removes the complement and keeps the members`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "a-1", assetId = "a"))
        backend.put(entry(key = "a-2", assetId = "a"))
        backend.put(entry(key = "b-1", assetId = "b"))
        backend.put(entry(key = "c-1", assetId = "c"))

        backend.retainAssets(setOf("a", "c"))

        assertEquals("a-1", backend.get("a-1")?.key)
        assertEquals("a-2", backend.get("a-2")?.key)
        assertNull(backend.get("b-1"))
        assertEquals("c-1", backend.get("c-1")?.key)
    }

    @Test
    fun `retainAssets with empty set empties the store`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "a", assetId = "a"))
        backend.put(entry(key = "b", assetId = "b"))

        backend.retainAssets(emptySet())

        assertEquals(LedgerAggregates(0, 0), backend.aggregates())
    }

    @Test
    fun `retainAssets dings an active changes collector`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "a", assetId = "a"))
        backend.put(entry(key = "b", assetId = "b"))
        var dings = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }

        backend.retainAssets(setOf("a"))
        runCurrent()

        assertEquals(1, dings)
    }

    @Test
    fun `resetTo replaces every row with the baseline verbatim`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "old-1", assetId = "old"))
        backend.put(entry(key = "old-2", assetId = "old"))

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
    fun `clearRequested removes only REQUESTED rows leaving COMPLETED and FAILED`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "R-photo.jpg", assetId = "R", state = LedgerState.REQUESTED))
        backend.put(entry(key = "C-photo.jpg", assetId = "C", state = LedgerState.COMPLETED))
        backend.put(entry(key = "F-photo.jpg", assetId = "F", state = LedgerState.FAILED))

        backend.clearRequested()

        assertNull(backend.get("R-photo.jpg")) // the orphaned REQUESTED row is dropped
        assertEquals(LedgerState.COMPLETED, backend.get("C-photo.jpg")?.state) // dedup truth kept
        assertEquals(LedgerState.FAILED, backend.get("F-photo.jpg")?.state) // FAILED self-heals via retry
    }

    @Test
    fun `clearRequested dings an active changes collector`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "R-photo.jpg", assetId = "R", state = LedgerState.REQUESTED))
        var dings = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { backend.changes.collect { dings++ } }

        backend.clearRequested()
        runCurrent()

        assertEquals(1, dings)
    }

    @Test
    fun `resetTo with an empty baseline empties the store`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "a", assetId = "a"))
        backend.put(entry(key = "b", assetId = "b"))

        backend.resetTo(emptyList())

        assertNull(backend.get("a"))
        assertEquals(LedgerAggregates(0, 0), backend.aggregates())
    }

    @Test
    fun `writer prunes by assetId and retains an asset set - reader cannot`() = runTest {
        val backend = createBackend()
        val writer = LedgerWriter(backend)
        writer.recordRequested("X-photo.jpg", "X", attempt = 0, eventId = "E1")
        writer.recordRequested("X-video.mov", "X", attempt = 0, eventId = "E1")
        writer.recordRequested("Y-photo.jpg", "Y", attempt = 0, eventId = "E1")

        writer.deleteByAssetId("X")
        assertNull(writer.entry("X-photo.jpg"))
        assertNull(writer.entry("X-video.mov"))
        assertEquals("Y-photo.jpg", writer.entry("Y-photo.jpg")?.key)

        writer.recordRequested("Z-photo.jpg", "Z", attempt = 0, eventId = "E1")
        writer.retainAssets(setOf("Y"))
        assertNull(writer.entry("Z-photo.jpg"))
        assertEquals("Y-photo.jpg", writer.entry("Y-photo.jpg")?.key)
    }

    @Test
    fun `recording converges on assetId state and attempt`() = runTest {
        val backend = createBackend()
        val writer = LedgerWriter(backend)

        writer.recordCompleted("k", "A", attempt = 2, eventId = "E1")
        writer.recordCompleted("k", "A", attempt = 2, eventId = "E1")

        val entry = writer.entry("k")!!
        assertEquals("A", entry.assetId)
        assertEquals(LedgerState.COMPLETED, entry.state)
        assertEquals(2, entry.attempt)
        assertEquals("E1", entry.eventId)
    }
}
