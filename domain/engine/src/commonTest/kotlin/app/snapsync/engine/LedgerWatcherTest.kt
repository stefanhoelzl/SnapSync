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

        val first = watcher.aggregates.first()

        assertEquals(LedgerAggregates(pending = 0, completed = 1, newestCompletionAt = t0), first)
    }

    @Test
    fun `a write re-emits the new aggregates`() = runTest {
        val emissions = mutableListOf<LedgerAggregates>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            watcher.aggregates.collect { emissions += it }
        }
        runCurrent()

        writer.recordCompleted("k", assetId = "k", attempt = 0, version = "v1")
        runCurrent()

        assertEquals(
            listOf(
                LedgerAggregates(0, 0, null),
                LedgerAggregates(pending = 0, completed = 1, newestCompletionAt = t0),
            ),
            emissions,
        )
    }

    @Test
    fun `writes that leave the aggregates unchanged stay silent`() = runTest {
        writer.recordRequested("k", assetId = "k", attempt = 0, version = "v1")
        val emissions = mutableListOf<LedgerAggregates>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            watcher.aggregates.collect { emissions += it }
        }
        runCurrent()

        writer.recordRequested("k", assetId = "k", attempt = 1, version = "v1")
        runCurrent()

        assertEquals(listOf(LedgerAggregates(pending = 1, completed = 0, newestCompletionAt = null)), emissions)
    }
}
