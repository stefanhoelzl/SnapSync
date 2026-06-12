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

    private fun entry(
        key: String = "cloud-1-ios.photo.heic",
        state: LedgerState = LedgerState.REQUESTED,
        attempt: Int = 0,
        version: String = "2026-06-12T10:00:00Z",
        updatedAt: Instant = t0,
    ) = LedgerEntry(key, state, attempt, version, updatedAt)

    @Test
    fun `put then get round-trips field for field`() = runTest {
        val backend = createBackend()
        val entry = entry(state = LedgerState.COMPLETED, attempt = 3, version = "v7", updatedAt = t1)

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
    fun `mixed states count by proof - everything non-completed is pending`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "a", state = LedgerState.REQUESTED))
        backend.put(entry(key = "b", state = LedgerState.FAILED))
        backend.put(entry(key = "c", state = LedgerState.COMPLETED, updatedAt = t1))
        backend.put(entry(key = "d", state = LedgerState.COMPLETED, updatedAt = t2))

        assertEquals(LedgerAggregates(pending = 2, completed = 2, newestCompletionAt = t2), backend.aggregates())
    }

    @Test
    fun `newest completion ignores non-completed rows`() = runTest {
        val backend = createBackend()
        backend.put(entry(key = "done", state = LedgerState.COMPLETED, updatedAt = t1))
        backend.put(entry(key = "hope", state = LedgerState.REQUESTED, updatedAt = t2))

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

        writer.recordRequested("k", attempt = 0, version = "v1")
        assertEquals(entry("k", LedgerState.REQUESTED, 0, "v1", t0), writer.entry("k"))

        clock.instant = t1
        writer.recordFailed("k", attempt = 0, version = "v1")
        assertEquals(entry("k", LedgerState.FAILED, 0, "v1", t1), writer.entry("k"))

        clock.instant = t2
        writer.recordCompleted("k", attempt = 1, version = "v1")
        assertEquals(entry("k", LedgerState.COMPLETED, 1, "v1", t2), writer.entry("k"))
    }

    @Test
    fun `recording converges on state attempt and version - only the timestamp moves`() = runTest {
        val backend = createBackend()
        val clock = FixedClock(t0)
        val writer = LedgerWriter(backend, clock)

        writer.recordCompleted("k", attempt = 2, version = "v1")
        clock.instant = t1
        writer.recordCompleted("k", attempt = 2, version = "v1")

        val entry = writer.entry("k")!!
        assertEquals(LedgerState.COMPLETED, entry.state)
        assertEquals(2, entry.attempt)
        assertEquals("v1", entry.version)
        assertEquals(t1, entry.updatedAt)
    }
}

class InMemoryLedgerBackendTest : LedgerBackendContract() {
    override fun createBackend(): LedgerBackend = InMemoryLedgerBackend()
}
