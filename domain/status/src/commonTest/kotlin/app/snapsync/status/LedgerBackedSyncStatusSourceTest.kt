package app.snapsync.status

import app.snapsync.gallery.InMemoryGalleryStatusSource
import app.snapsync.permission.PermissionStatus
import app.snapsync.permission.PermissionStatusSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LedgerBackedSyncStatusSourceTest {

    private val ledgerCounts = MutableLedgerCountsSource()
    private val permission = FakePermissionSource(PermissionStatus.GRANTED)
    private val gallery = InMemoryGalleryStatusSource(initial = 0)

    private fun ready(
        pending: Int = 0,
        completed: Int = 0,
        total: Int = 0,
        active: Boolean = true,
    ) = SyncStatus.Ready(SyncProgress(pending, completed, total, failed = 0, active, estimatedRemaining = null))

    private fun source(scope: kotlinx.coroutines.CoroutineScope) =
        LedgerBackedSyncStatusSource(ledgerCounts, permission, gallery, scope)

    @Test
    fun `initial value is Loading before the first read`() = runTest {
        ledgerCounts.set(completed = 1, pending = 0)
        gallery.set(3)

        val source = source(backgroundScope)

        assertEquals(SyncStatus.Loading, source.status.value)
    }

    @Test
    fun `first Ready reflects completed and pending clamped to remaining`() = runTest {
        // pending high (1000) so it reads as the remaining; completed 2 of 5 → remaining 3.
        ledgerCounts.set(completed = 2, pending = 1000)
        gallery.set(5)

        val source = source(backgroundScope)
        runCurrent()

        assertEquals(ready(pending = 3, completed = 2, total = 5), source.status.value)
    }

    @Test
    fun `pending is the in-flight count when below remaining`() = runTest {
        ledgerCounts.set(completed = 2, pending = 2) // synced 2, remaining 5, only 2 in flight
        gallery.set(7)
        val source = source(backgroundScope)
        runCurrent()

        // pending = min(2, 5) = 2 — the real in-flight, not the remaining 5
        assertEquals(ready(pending = 2, completed = 2, total = 7), source.status.value)
    }

    @Test
    fun `pending is clamped to remaining when a deleted-but-unpruned photo over-counts`() = runTest {
        ledgerCounts.set(completed = 5, pending = 3) // synced 5, remaining 2, ledger reports 3 in flight
        gallery.set(7)
        val source = source(backgroundScope)
        runCurrent()

        // pending = min(3, 2) = 2 — never above remaining
        assertEquals(ready(pending = 2, completed = 5, total = 7), source.status.value)
    }

    @Test
    fun `an in-flight change re-mints pending`() = runTest {
        ledgerCounts.set(completed = 1, pending = 3)
        gallery.set(6)
        val source = source(backgroundScope)
        runCurrent()
        assertEquals(ready(pending = 3, completed = 1, total = 6), source.status.value)

        ledgerCounts.set(completed = 1, pending = 1)
        runCurrent()

        assertEquals(ready(pending = 1, completed = 1, total = 6), source.status.value)
    }

    @Test
    fun `a newly complete asset re-mints completed and shrinks remaining`() = runTest {
        ledgerCounts.set(completed = 0, pending = 1000)
        gallery.set(4)
        val source = source(backgroundScope)
        runCurrent()
        assertEquals(ready(pending = 4, total = 4), source.status.value)

        ledgerCounts.set(completed = 1, pending = 1000)
        runCurrent()

        assertEquals(ready(pending = 3, completed = 1, total = 4), source.status.value)
    }

    @Test
    fun `pending is zero when completed meets or exceeds the live total`() = runTest {
        // The gallery total can momentarily lag the ledger; remaining (and so pending) clamps at 0.
        ledgerCounts.set(completed = 3, pending = 1000)
        gallery.set(1)
        val source = source(backgroundScope)
        runCurrent()

        assertEquals(ready(pending = 0, completed = 3, total = 1), source.status.value)
    }

    @Test
    fun `a gallery change re-mints with the recomputed remaining`() = runTest {
        ledgerCounts.set(completed = 1, pending = 1000)
        gallery.set(4)
        val source = source(backgroundScope)
        runCurrent()
        assertEquals(ready(pending = 3, completed = 1, total = 4), source.status.value)

        gallery.set(9)
        runCurrent()

        assertEquals(ready(pending = 8, completed = 1, total = 9), source.status.value)
    }

    @Test
    fun `a permission flip re-mints with unchanged counts`() = runTest {
        ledgerCounts.set(completed = 1, pending = 1000)
        gallery.set(4)
        val source = source(backgroundScope)
        runCurrent()

        permission.state.value = PermissionStatus.DENIED
        runCurrent()

        assertEquals(ready(pending = 3, completed = 1, total = 4, active = false), source.status.value)
    }

    @Test
    fun `the source never estimates and never gives up`() = runTest {
        ledgerCounts.set(completed = 0, pending = 1000)
        gallery.set(1)
        val source = source(backgroundScope)
        runCurrent()

        val progress = assertIs<SyncStatus.Ready>(source.status.value).progress
        assertEquals(0, progress.failed)
        assertEquals(null, progress.estimatedRemaining)
        assertEquals(1, progress.pending)
        assertEquals(1, progress.total)
    }
}

private class FakePermissionSource(initial: PermissionStatus) : PermissionStatusSource {
    val state = MutableStateFlow(initial)
    override val permission: StateFlow<PermissionStatus> = state
}
