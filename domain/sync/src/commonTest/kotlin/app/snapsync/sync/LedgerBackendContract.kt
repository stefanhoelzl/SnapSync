package app.snapsync.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The storage-seam contract every [LedgerBackend] must satisfy (sync-ledger spec). Concrete
 * backends bind [createBackend]; the same scenarios run unchanged against each.
 */
abstract class LedgerBackendContract {

    protected abstract fun createBackend(): LedgerBackend

    private fun entry(
        key: String = "cloud-1-ios.photo.heic",
        state: LedgerState = LedgerState.REQUESTED,
        attempt: Int = 0,
        version: String = "2026-06-12T10:00:00Z",
    ) = LedgerEntry(key, state, attempt, version)

    @Test
    fun `put then get round-trips field for field`() = runTest {
        val backend = createBackend()
        val entry = entry(state = LedgerState.COMPLETED, attempt = 3, version = "v7")

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
    fun `writer records are self-contained entries`() = runTest {
        val backend = createBackend()
        val writer = LedgerWriter(backend)

        writer.recordRequested("k", attempt = 0, version = "v1")
        assertEquals(entry("k", LedgerState.REQUESTED, 0, "v1"), writer.entry("k"))

        writer.recordFailed("k", attempt = 0, version = "v1")
        assertEquals(entry("k", LedgerState.FAILED, 0, "v1"), writer.entry("k"))

        writer.recordCompleted("k", attempt = 1, version = "v1")
        assertEquals(entry("k", LedgerState.COMPLETED, 1, "v1"), writer.entry("k"))
    }

    @Test
    fun `recording is idempotent`() = runTest {
        val backend = createBackend()
        val writer = LedgerWriter(backend)

        writer.recordCompleted("k", attempt = 2, version = "v1")
        val once = writer.entry("k")
        writer.recordCompleted("k", attempt = 2, version = "v1")

        assertEquals(once, writer.entry("k"))
    }
}

class InMemoryLedgerBackendTest : LedgerBackendContract() {
    override fun createBackend(): LedgerBackend = InMemoryLedgerBackend()
}
