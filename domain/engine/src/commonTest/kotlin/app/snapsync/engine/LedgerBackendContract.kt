package app.snapsync.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The storage-seam contract every [LedgerBackend] must satisfy (sync-ledger spec). Concrete
 * backends bind [createBackend]; the same scenarios run unchanged against each.
 */
abstract class LedgerBackendContract {

    protected abstract fun createBackend(): LedgerBackend

    private val t0 = Instant.fromEpochMilliseconds(1_000_000)
    private val t1 = Instant.fromEpochMilliseconds(2_000_000)
    private val t2 = Instant.fromEpochMilliseconds(3_000_000)

    // assetId defaults to the key, so a test that doesn't care about grouping gets one photo per
    // row (the historical per-row behaviour); multi-resource-photo tests pass an explicit assetId.
    private fun entry(
        key: String = "cloud-1-ios.photo.heic",
        assetId: String = key,
        state: LedgerState = LedgerState.REQUESTED,
        attempt: Int = 0,
        version: String = "2026-06-12T10:00:00Z",
        updatedAt: Instant = t0,
    ) = LedgerEntry(key, assetId, state, attempt, version, updatedAt)

    @Test
    fun `put then get round-trips field for field`() = runTest {
        val backend = createBackend()
        val entry = entry(assetId = "A", state = LedgerState.COMPLETED, attempt = 3, version = "v7", updatedAt = t1)

        backend.put(entry)

        assertEquals(entry, backend.get(entry.key))
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
    fun `empty ledger aggregates to zero counts and no completion`() = runTest {
        assertEquals(LedgerAggregates(0, 0, null), createBackend().aggregates())
    }

    @Test
    fun `photos count by asset - one photo per distinct asset`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "a", state = LedgerState.REQUESTED))
        backend.put(entry(key = "b", state = LedgerState.FAILED))
        backend.put(entry(key = "c", state = LedgerState.COMPLETED, updatedAt = t1))
        backend.put(entry(key = "d", state = LedgerState.COMPLETED, updatedAt = t2))

        assertEquals(LedgerAggregates(pending = 2, completed = 2, newestCompletionAt = t2), backend.aggregates())
    }

    @Test
    fun `a photo counts complete only when all its resources are`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "P-photo.jpg", assetId = "P", state = LedgerState.COMPLETED, updatedAt = t1))
        backend.put(entry(key = "P-video.mov", assetId = "P", state = LedgerState.REQUESTED, updatedAt = t2))

        assertEquals(LedgerAggregates(pending = 1, completed = 0, newestCompletionAt = null), backend.aggregates())
    }

    @Test
    fun `mixed photos - one complete and one partial`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED, updatedAt = t1))
        backend.put(entry(key = "A-video.mov", assetId = "A", state = LedgerState.COMPLETED, updatedAt = t1))
        backend.put(entry(key = "B-photo.jpg", assetId = "B", state = LedgerState.COMPLETED, updatedAt = t2))
        backend.put(entry(key = "B-edit.jpg", assetId = "B", state = LedgerState.FAILED, updatedAt = t2))

        assertEquals(LedgerAggregates(pending = 1, completed = 1, newestCompletionAt = t1), backend.aggregates())
    }

    @Test
    fun `newest completion is the latest fully-completed photo`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "A-photo.jpg", assetId = "A", state = LedgerState.COMPLETED, updatedAt = t0))
        backend.put(entry(key = "B-photo.jpg", assetId = "B", state = LedgerState.COMPLETED, updatedAt = t1))
        // A partially-done photo never contributes to newestCompletionAt.
        backend.put(entry(key = "C-photo.jpg", assetId = "C", state = LedgerState.REQUESTED, updatedAt = t2))

        assertEquals(t1, backend.aggregates().newestCompletionAt)
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
    fun `writer records are self-contained stamped entries`() = runTest {
        val backend = createBackend()
        val clock = FixedClock(t0)
        val writer = LedgerWriter(backend, clock)

        writer.recordRequested("k", "A", attempt = 0, version = "v1")
        assertEquals(entry("k", "A", LedgerState.REQUESTED, 0, "v1", t0), writer.entry("k"))

        clock.instant = t1
        writer.recordFailed("k", "A", attempt = 0, version = "v1")
        assertEquals(entry("k", "A", LedgerState.FAILED, 0, "v1", t1), writer.entry("k"))

        clock.instant = t2
        writer.recordCompleted("k", "A", attempt = 1, version = "v1")
        assertEquals(entry("k", "A", LedgerState.COMPLETED, 1, "v1", t2), writer.entry("k"))
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

        assertEquals(LedgerAggregates(0, 0, null), backend.aggregates())
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
    fun `writer prunes by assetId and retains an asset set - reader cannot`() = runTest {
        val backend = createBackend()
        val writer = LedgerWriter(backend, FixedClock(t0))
        writer.recordRequested("X-photo.jpg", "X", attempt = 0, version = "v1")
        writer.recordRequested("X-video.mov", "X", attempt = 0, version = "v1")
        writer.recordRequested("Y-photo.jpg", "Y", attempt = 0, version = "v1")

        writer.deleteByAssetId("X")
        assertNull(writer.entry("X-photo.jpg"))
        assertNull(writer.entry("X-video.mov"))
        assertEquals("Y-photo.jpg", writer.entry("Y-photo.jpg")?.key)

        writer.recordRequested("Z-photo.jpg", "Z", attempt = 0, version = "v1")
        writer.retainAssets(setOf("Y"))
        assertNull(writer.entry("Z-photo.jpg"))
        assertEquals("Y-photo.jpg", writer.entry("Y-photo.jpg")?.key)

        // Compile-time guard: prune ops are absent from the read-only face.
        @Suppress("UNUSED_VARIABLE")
        val reader: LedgerReader = writer // narrowing compiles; `reader.deleteByAssetId(...)` would not
    }

    @Test
    fun `recording converges on assetId state attempt and version - only the timestamp moves`() = runTest {
        val backend = createBackend()
        val clock = FixedClock(t0)
        val writer = LedgerWriter(backend, clock)

        writer.recordCompleted("k", "A", attempt = 2, version = "v1")
        clock.instant = t1
        writer.recordCompleted("k", "A", attempt = 2, version = "v1")

        val entry = writer.entry("k")!!
        assertEquals("A", entry.assetId)
        assertEquals(LedgerState.COMPLETED, entry.state)
        assertEquals(2, entry.attempt)
        assertEquals("v1", entry.version)
        assertEquals(t1, entry.updatedAt)
    }
}

class InMemoryLedgerBackendTest : LedgerBackendContract() {
    override fun createBackend(): LedgerBackend = InMemoryLedgerBackend()
}
