package app.snapsync.status

import app.snapsync.engine.LedgerAggregates
import app.snapsync.engine.LedgerBackend
import app.snapsync.engine.LedgerEntry
import app.snapsync.engine.LedgerState
import app.snapsync.engine.LedgerWatcher
import app.snapsync.engine.LedgerWriter
import app.snapsync.engine.PendingResource
import app.snapsync.gallery.InMemoryGalleryStatusSource
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LedgerSyncStatusSourceTest {

    private val t0 = Instant.fromEpochMilliseconds(1_000_000)
    private val backend = RowStore()
    private val writer = LedgerWriter(backend, object : Clock {
        override fun now(): Instant = t0
    })
    private val watcher = LedgerWatcher(backend)
    private val permission = FakePermissionSource(PermissionStatus.GRANTED)
    private val gallery = InMemoryGalleryStatusSource(initial = 0)
    private val observed = FakeObservedCompletions()

    private fun snapshot(
        pending: Int = 0,
        completed: Int = 0,
        total: Int = 0,
        active: Boolean = true,
        lastFinishedAt: Instant? = null,
    ) = SyncProgress(pending, completed, total, failed = 0, active, estimatedRemaining = null, lastFinishedAt)

    private fun ready(
        pending: Int = 0,
        completed: Int = 0,
        total: Int = 0,
        active: Boolean = true,
        lastFinishedAt: Instant? = null,
    ) = SyncStatus.Ready(snapshot(pending, completed, total, active, lastFinishedAt))

    private fun source(testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler, scope: kotlinx.coroutines.CoroutineScope) =
        LedgerSyncStatusSource(watcher, permission, gallery, observed, scope, StandardTestDispatcher(testScheduler))

    @Test
    fun `initial value is Loading before the first read`() = runTest {
        writer.recordCompleted("a", assetId = "a", attempt = 0)
        gallery.set(3)

        val source = source(testScheduler, backgroundScope)

        assertEquals(SyncStatus.Loading, source.status.value)
    }

    @Test
    fun `first Ready reflects ledger and gallery`() = runTest {
        writer.recordCompleted("a", assetId = "a", attempt = 0)
        writer.recordCompleted("b", assetId = "b", attempt = 0)
        writer.recordRequested("c", assetId = "c", attempt = 0)
        gallery.set(5)

        val source = source(testScheduler, backgroundScope)
        runCurrent()

        assertEquals(ready(pending = 1, completed = 2, total = 5, lastFinishedAt = t0), source.status.value)
    }

    @Test
    fun `a ledger change re-mints a Ready snapshot`() = runTest {
        gallery.set(4)
        val source = source(testScheduler, backgroundScope)
        runCurrent()
        assertEquals(ready(total = 4), source.status.value)

        writer.recordCompleted("a", assetId = "a", attempt = 0)
        runCurrent()

        assertEquals(ready(completed = 1, total = 4, lastFinishedAt = t0), source.status.value)
    }

    @Test
    fun `a gallery change re-mints a Ready snapshot with unchanged counts`() = runTest {
        writer.recordCompleted("a", assetId = "a", attempt = 0)
        gallery.set(4)
        val source = source(testScheduler, backgroundScope)
        runCurrent()
        assertEquals(ready(completed = 1, total = 4, lastFinishedAt = t0), source.status.value)

        gallery.set(9)
        runCurrent()

        assertEquals(ready(completed = 1, total = 9, lastFinishedAt = t0), source.status.value)
    }

    @Test
    fun `a permission flip re-mints a Ready snapshot with unchanged counts`() = runTest {
        writer.recordCompleted("a", assetId = "a", attempt = 0)
        gallery.set(4)
        val source = source(testScheduler, backgroundScope)
        runCurrent()

        permission.state.value = PermissionStatus.DENIED
        runCurrent()

        assertEquals(ready(completed = 1, total = 4, active = false, lastFinishedAt = t0), source.status.value)
    }

    @Test
    fun `an observed completion promotes a pending photo before any ledger write`() = runTest {
        writer.recordRequested("P-photo.jpg", assetId = "P", attempt = 0)
        writer.recordRequested("P-video.mov", assetId = "P", attempt = 0)
        gallery.set(1)
        val source = source(testScheduler, backgroundScope)
        runCurrent()
        assertEquals(ready(pending = 1, completed = 0, total = 1), source.status.value)

        // All of P's outstanding resources are observed succeeded — P promotes, with no ledger write
        // and no fabricated timestamp.
        observed.state.value = setOf("P-photo.jpg", "P-video.mov")
        runCurrent()

        assertEquals(ready(pending = 0, completed = 1, total = 1, lastFinishedAt = null), source.status.value)
    }

    @Test
    fun `a released observed key does not revert its photo while still outstanding`() = runTest {
        writer.recordRequested("P-photo.jpg", assetId = "P", attempt = 0)
        gallery.set(1)
        val source = source(testScheduler, backgroundScope)
        runCurrent()
        observed.state.value = setOf("P-photo.jpg")
        runCurrent()
        assertEquals(ready(pending = 0, completed = 1, total = 1), source.status.value)

        // The platform releases the key (e.g. acknowledged) but the ledger ding has not yet recorded
        // it — sticky retention keeps P complete rather than blinking it back to pending.
        observed.state.value = emptySet()
        runCurrent()

        assertEquals(ready(pending = 0, completed = 1, total = 1), source.status.value)
    }

    @Test
    fun `the source never estimates and never gives up`() = runTest {
        writer.recordFailed("a", assetId = "a", attempt = 3)
        gallery.set(1)
        val source = source(testScheduler, backgroundScope)
        runCurrent()

        val progress = assertIs<SyncStatus.Ready>(source.status.value).progress
        assertEquals(0, progress.failed)
        assertEquals(null, progress.estimatedRemaining)
        assertEquals(1, progress.pending)
        assertEquals(1, progress.total)
    }
}

/** Local row-store double — mirrors the backend contract (engine's test doubles aren't published). */
private class RowStore : LedgerBackend {

    private val rows = mutableMapOf<String, LedgerEntry>()

    private val dings = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val changes: Flow<Unit> = dings

    override suspend fun get(key: String): LedgerEntry? = rows[key]

    override suspend fun put(entry: LedgerEntry) {
        rows[entry.key] = entry
        dings.tryEmit(Unit)
    }

    override suspend fun clear() {
        rows.clear()
        dings.tryEmit(Unit)
    }

    override suspend fun resetTo(entries: List<LedgerEntry>) {
        val next = entries.associateByTo(mutableMapOf()) { it.key }
        rows.clear()
        rows.putAll(next)
        dings.tryEmit(Unit)
    }

    override suspend fun deleteByAssetId(assetId: String) {
        rows.values.removeAll { it.assetId == assetId }
        dings.tryEmit(Unit)
    }

    override suspend fun retainAssets(keep: Set<String>) {
        rows.values.removeAll { it.assetId !in keep }
        dings.tryEmit(Unit)
    }

    override suspend fun aggregates(): LedgerAggregates {
        // Counted by photo (assetId): a photo is complete only when all its rows are COMPLETED.
        val byAsset = rows.values.groupBy { it.assetId }
        val complete = byAsset.values.filter { group -> group.all { it.state == LedgerState.COMPLETED } }
        return LedgerAggregates(
            pending = byAsset.size - complete.size,
            completed = complete.size,
            newestCompletionAt = complete.flatten().maxOfOrNull { it.updatedAt },
        )
    }

    override suspend fun pendingResources(): List<PendingResource> =
        rows.values.filter { it.state != LedgerState.COMPLETED }.map { PendingResource(it.assetId, it.key) }
}

private class FakePermissionSource(initial: PermissionStatus) : PermissionStatusSource {
    val state = MutableStateFlow(initial)
    override val permission: StateFlow<PermissionStatus> = state
}

private class FakeObservedCompletions : ObservedCompletionsSource {
    val state = MutableStateFlow<Set<String>>(emptySet())
    override val keys: StateFlow<Set<String>> = state
    override suspend fun refresh() = Unit
}
