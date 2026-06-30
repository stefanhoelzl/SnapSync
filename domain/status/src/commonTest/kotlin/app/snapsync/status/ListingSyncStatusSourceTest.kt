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

    private fun ready(
        pending: Int = 0,
        completed: Int = 0,
        total: Int = 0,
        active: Boolean = true,
    ) = SyncStatus.Ready(SyncProgress(pending, completed, total, failed = 0, active, estimatedRemaining = null))

    private fun source(scope: kotlinx.coroutines.CoroutineScope) =
        ListingSyncStatusSource(completed, permission, gallery, scope)

    @Test
    fun `initial value is Loading before the first read`() = runTest {
        completed.set(setOf("a"))
        gallery.set(3)

        val source = source(backgroundScope)

        assertEquals(SyncStatus.Loading, source.status.value)
    }

    @Test
    fun `first Ready reflects completed and pending as total minus completed and gallery`() = runTest {
        completed.set(setOf("a", "b"))
        gallery.set(5)

        val source = source(backgroundScope)
        runCurrent()

        // pending = total - completed = 5 - 2 = 3
        assertEquals(ready(pending = 3, completed = 2, total = 5), source.status.value)
    }

    @Test
    fun `a newly complete asset re-mints completed and shrinks pending`() = runTest {
        gallery.set(4)
        val source = source(backgroundScope)
        runCurrent()
        assertEquals(ready(pending = 4, total = 4), source.status.value)

        completed.set(setOf("a"))
        runCurrent()

        assertEquals(ready(pending = 3, completed = 1, total = 4), source.status.value)
    }

    @Test
    fun `pending never goes negative when completed exceeds the live total`() = runTest {
        // The gallery total can momentarily lag the completed set; pending clamps at 0.
        completed.set(setOf("a", "b", "c"))
        gallery.set(1)
        val source = source(backgroundScope)
        runCurrent()

        assertEquals(ready(pending = 0, completed = 3, total = 1), source.status.value)
    }

    @Test
    fun `a gallery change re-mints with the recomputed pending`() = runTest {
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
