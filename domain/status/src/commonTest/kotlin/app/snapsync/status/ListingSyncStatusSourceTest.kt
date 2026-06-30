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

class ListingSyncStatusSourceTest {

    private val completed = MutableCompletedAssetsSource()
    private val permission = FakePermissionSource(PermissionStatus.GRANTED)
    private val gallery = InMemoryGalleryStatusSource(initial = 0)

    // Defaults high so the structural tests read `pending = remaining`; the clamp tests set it lower.
    private val inFlight = MutableInFlightSource(initial = 1000)

    private fun ready(
        pending: Int = 0,
        completed: Int = 0,
        total: Int = 0,
        active: Boolean = true,
    ) = SyncStatus.Ready(SyncProgress(pending, completed, total, failed = 0, active, estimatedRemaining = null))

    private fun source(scope: kotlinx.coroutines.CoroutineScope) =
        ListingSyncStatusSource(completed, permission, gallery, inFlight, scope)

    @Test
    fun `initial value is Loading before the first read`() = runTest {
        completed.set(setOf("a"))
        gallery.set(3)

        val source = source(backgroundScope)

        assertEquals(SyncStatus.Loading, source.status.value)
    }

    @Test
    fun `first Ready reflects completed and pending clamped to remaining`() = runTest {
        completed.set(setOf("a", "b"))
        gallery.set(5)

        val source = source(backgroundScope)
        runCurrent()

        // pending = min(inFlight=1000, remaining = 5 - 2) = 3
        assertEquals(ready(pending = 3, completed = 2, total = 5), source.status.value)
    }

    @Test
    fun `pending is the in-flight count when below remaining`() = runTest {
        completed.set(setOf("a", "b")) // synced 2
        gallery.set(7) // remaining 5
        inFlight.set(2) // only 2 actually uploading
        val source = source(backgroundScope)
        runCurrent()

        // pending = min(2, 5) = 2 — the real in-flight, not the remaining 5
        assertEquals(ready(pending = 2, completed = 2, total = 7), source.status.value)
    }

    @Test
    fun `pending is clamped to remaining when the ledger over-counts`() = runTest {
        completed.set(setOf("a", "b", "c", "d", "e")) // synced 5
        gallery.set(7) // remaining 2
        inFlight.set(3) // a finished-but-unacked job still reads in-flight
        val source = source(backgroundScope)
        runCurrent()

        // pending = min(3, 2) = 2 — never above remaining
        assertEquals(ready(pending = 2, completed = 5, total = 7), source.status.value)
    }

    @Test
    fun `an in-flight change re-mints pending`() = runTest {
        completed.set(setOf("a")) // synced 1
        gallery.set(6) // remaining 5
        inFlight.set(3)
        val source = source(backgroundScope)
        runCurrent()
        assertEquals(ready(pending = 3, completed = 1, total = 6), source.status.value)

        inFlight.set(1)
        runCurrent()

        assertEquals(ready(pending = 1, completed = 1, total = 6), source.status.value)
    }

    @Test
    fun `a newly complete asset re-mints completed and shrinks remaining`() = runTest {
        gallery.set(4)
        val source = source(backgroundScope)
        runCurrent()
        assertEquals(ready(pending = 4, total = 4), source.status.value)

        completed.set(setOf("a"))
        runCurrent()

        assertEquals(ready(pending = 3, completed = 1, total = 4), source.status.value)
    }

    @Test
    fun `pending is zero when completed meets or exceeds the live total`() = runTest {
        // The gallery total can momentarily lag the completed set; remaining (and so pending) clamps at 0.
        completed.set(setOf("a", "b", "c"))
        gallery.set(1)
        val source = source(backgroundScope)
        runCurrent()

        assertEquals(ready(pending = 0, completed = 3, total = 1), source.status.value)
    }

    @Test
    fun `a gallery change re-mints with the recomputed remaining`() = runTest {
        completed.set(setOf("a"))
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
        completed.set(setOf("a"))
        gallery.set(4)
        val source = source(backgroundScope)
        runCurrent()

        permission.state.value = PermissionStatus.DENIED
        runCurrent()

        assertEquals(ready(pending = 3, completed = 1, total = 4, active = false), source.status.value)
    }

    @Test
    fun `the source never estimates and never gives up`() = runTest {
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
