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
    private val pending = MutablePendingManifestsSource()
    private val permission = FakePermissionSource(PermissionStatus.GRANTED)
    private val gallery = InMemoryGalleryStatusSource(initial = 0)

    private fun ready(
        pending: Int = 0,
        completed: Int = 0,
        total: Int = 0,
        active: Boolean = true,
    ) = SyncStatus.Ready(SyncProgress(pending, completed, total, failed = 0, active, estimatedRemaining = null))

    private fun source(scope: kotlinx.coroutines.CoroutineScope) =
        ListingSyncStatusSource(completed, pending, permission, gallery, scope)

    @Test
    fun `initial value is Loading before the first read`() = runTest {
        completed.set(setOf("a"))
        gallery.set(3)

        val source = source(backgroundScope)

        assertEquals(SyncStatus.Loading, source.status.value)
    }

    @Test
    fun `first Ready reflects the listing and in-flight manifests and gallery`() = runTest {
        completed.set(setOf("a", "b"))
        pending.set(setOf("c"))
        gallery.set(5)

        val source = source(backgroundScope)
        runCurrent()

        assertEquals(ready(pending = 1, completed = 2, total = 5), source.status.value)
    }

    @Test
    fun `a newly complete asset re-mints completed`() = runTest {
        gallery.set(4)
        val source = source(backgroundScope)
        runCurrent()
        assertEquals(ready(total = 4), source.status.value)

        completed.set(setOf("a"))
        runCurrent()

        assertEquals(ready(completed = 1, total = 4), source.status.value)
    }

    @Test
    fun `a new in-flight manifest re-mints pending`() = runTest {
        gallery.set(4)
        val source = source(backgroundScope)
        runCurrent()

        pending.set(setOf("a"))
        runCurrent()

        assertEquals(ready(pending = 1, total = 4), source.status.value)
    }

    @Test
    fun `a gallery change re-mints with unchanged counts`() = runTest {
        completed.set(setOf("a"))
        gallery.set(4)
        val source = source(backgroundScope)
        runCurrent()
        assertEquals(ready(completed = 1, total = 4), source.status.value)

        gallery.set(9)
        runCurrent()

        assertEquals(ready(completed = 1, total = 9), source.status.value)
    }

    @Test
    fun `a permission flip re-mints with unchanged counts`() = runTest {
        completed.set(setOf("a"))
        gallery.set(4)
        val source = source(backgroundScope)
        runCurrent()

        permission.state.value = PermissionStatus.DENIED
        runCurrent()

        assertEquals(ready(completed = 1, total = 4, active = false), source.status.value)
    }

    @Test
    fun `the source never estimates and never gives up`() = runTest {
        pending.set(setOf("a"))
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
