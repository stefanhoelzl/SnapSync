package app.snapsync.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LedgerWatcherTest {

    private val t0 = Instant.fromEpochMilliseconds(1_000_000)
    private val backend = InMemoryLedgerBackend()
    private val writer = LedgerWriter(backend, FixedClock(t0))
    private val watcher = LedgerWatcher(backend)

    @Test
    fun `collection starts with current truth - no write needed`() = runTest {
        writer.recordCompleted("k", assetId = "k", attempt = 0, version = "v1")

        val first = watcher.snapshot.first()

        assertEquals(LedgerSnapshot(completed = 1, newestCompletionAt = t0, pendingByAsset = emptyMap()), first)
    }

    @Test
    fun `a write re-emits a consistent snapshot`() = runTest {
        val emissions = mutableListOf<LedgerSnapshot>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            watcher.snapshot.collect { emissions += it }
        }
        runCurrent()

        // A new asset's first resource is REQUESTED — it joins the backlog, completed stays 0.
        writer.recordRequested("k", assetId = "k", attempt = 0, version = "v1")
        runCurrent()

        assertEquals(
            listOf(
                LedgerSnapshot(completed = 0, newestCompletionAt = null, pendingByAsset = emptyMap()),
                LedgerSnapshot(completed = 0, newestCompletionAt = null, pendingByAsset = mapOf("k" to setOf("k"))),
            ),
            emissions,
        )
    }

    @Test
    fun `writes that leave the snapshot unchanged stay silent`() = runTest {
        writer.recordRequested("k", assetId = "k", attempt = 0, version = "v1")
        val emissions = mutableListOf<LedgerSnapshot>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            watcher.snapshot.collect { emissions += it }
        }
        runCurrent()

        writer.recordRequested("k", assetId = "k", attempt = 1, version = "v1")
        runCurrent()

        assertEquals(
            listOf(LedgerSnapshot(completed = 0, newestCompletionAt = null, pendingByAsset = mapOf("k" to setOf("k")))),
            emissions,
        )
    }
}
