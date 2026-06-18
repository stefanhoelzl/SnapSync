package app.snapsync.status

import app.snapsync.engine.LedgerAggregates
import app.snapsync.engine.LedgerBackend
import app.snapsync.engine.LedgerEntry
import app.snapsync.engine.LedgerState
import app.snapsync.engine.LedgerWatcher
import app.snapsync.engine.LedgerWriter
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

    private fun snapshot(
        pending: Int = 0,
        completed: Int = 0,
        active: Boolean = true,
        lastFinishedAt: Instant? = null,
    ) = SyncProgress(pending, completed, failed = 0, active, estimatedRemaining = null, lastFinishedAt)

    private fun ready(
        pending: Int = 0,
        completed: Int = 0,
        active: Boolean = true,
        lastFinishedAt: Instant? = null,
    ) = SyncStatus.Ready(snapshot(pending, completed, active, lastFinishedAt))

    @Test
    fun `initial value is Loading before the first read`() = runTest {
        writer.recordCompleted("a", attempt = 0, version = "v1")

        val source = LedgerSyncStatusSource(watcher, permission, backgroundScope, StandardTestDispatcher(testScheduler))

        assertEquals(SyncStatus.Loading, source.status.value)
    }

    @Test
    fun `first Ready reflects the ledger`() = runTest {
        writer.recordCompleted("a", attempt = 0, version = "v1")
        writer.recordCompleted("b", attempt = 0, version = "v1")
        writer.recordRequested("c", attempt = 0, version = "v1")

        val source = LedgerSyncStatusSource(watcher, permission, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()

        assertEquals(ready(pending = 1, completed = 2, lastFinishedAt = t0), source.status.value)
    }

    @Test
    fun `a ledger change re-mints a Ready snapshot`() = runTest {
        val source = LedgerSyncStatusSource(watcher, permission, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(ready(), source.status.value)

        writer.recordCompleted("a", attempt = 0, version = "v1")
        runCurrent()

        assertEquals(ready(completed = 1, lastFinishedAt = t0), source.status.value)
    }

    @Test
    fun `a permission flip re-mints a Ready snapshot with unchanged counts`() = runTest {
        writer.recordCompleted("a", attempt = 0, version = "v1")
        val source = LedgerSyncStatusSource(watcher, permission, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()

        permission.state.value = PermissionStatus.DENIED
        runCurrent()

        assertEquals(ready(completed = 1, active = false, lastFinishedAt = t0), source.status.value)
    }

    @Test
    fun `the v1 source never estimates and never gives up`() = runTest {
        writer.recordFailed("a", attempt = 3, version = "v1")
        val source = LedgerSyncStatusSource(watcher, permission, backgroundScope, StandardTestDispatcher(testScheduler))
        runCurrent()

        val progress = assertIs<SyncStatus.Ready>(source.status.value).progress
        assertEquals(0, progress.failed)
        assertEquals(null, progress.estimatedRemaining)
        assertEquals(1, progress.pending)
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

    override suspend fun aggregates(): LedgerAggregates {
        val completed = rows.values.filter { it.state == LedgerState.COMPLETED }
        return LedgerAggregates(
            pending = rows.size - completed.size,
            completed = completed.size,
            newestCompletionAt = completed.maxOfOrNull { it.updatedAt },
        )
    }
}

private class FakePermissionSource(initial: PermissionStatus) : PermissionStatusSource {
    val state = MutableStateFlow(initial)
    override val permission: StateFlow<PermissionStatus> = state
}
