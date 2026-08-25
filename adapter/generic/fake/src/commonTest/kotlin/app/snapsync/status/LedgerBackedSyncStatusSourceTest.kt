package app.snapsync.status

import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus

import app.snapsync.feature.status.LedgerBackedSyncStatusSource
import app.snapsync.feature.status.MutableLedgerCountsSource
import app.snapsync.fake.InMemoryGalleryStatusSource
import app.snapsync.model.PermissionStatus
import app.snapsync.ports.PhotoAccessStatusSource
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
    // The honest fake exposes only the port; the test owns the cell it reads (fake-honesty gate).
    private val galleryCell = MutableStateFlow<Int?>(0)
    private val gallery = InMemoryGalleryStatusSource(galleryCell)

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
        galleryCell.value = 3

        val source = source(backgroundScope)

        assertEquals(SyncStatus.Loading, source.status.value)
    }

    @Test
    fun `seeded but unread inputs never mint a Ready`() = runTest {
        // The defect this whole change exists for. Every input is a `StateFlow`, so all of them "have a
        // value" the instant they are built and `combine` emits on its first dispatch. When those values
        // were placeholder zeros, the projection minted `Ready(total = 0, completed = 0)` — and the
        // health rule hides a direction arrow when `synced >= total`, so `0 >= 0` on both arms rendered
        // a check mark reading "In sync" on a device that had read nothing (`SNAPSYNC-14`,
        // `SNAPSYNC-16`). Read-ness now lives in the input types, so existing cannot satisfy it.
        val unread = LedgerBackedSyncStatusSource(
            MutableLedgerCountsSource(), // seeded UNREAD
            permission,
            InMemoryGalleryStatusSource(), // seeded null — never enumerated
            backgroundScope,
        )
        runCurrent()

        assertEquals(SyncStatus.Loading, unread.status.value)
    }

    @Test
    fun `an unread gallery total alone holds the source at Loading`() = runTest {
        ledgerCounts.set(completed = 1, pending = 0) // read
        galleryCell.value = null // not counted

        val source = source(backgroundScope)
        runCurrent()

        assertEquals(SyncStatus.Loading, source.status.value)
    }

    @Test
    fun `unread ledger counts alone hold the source at Loading`() = runTest {
        galleryCell.value = 3 // counted
        val source = LedgerBackedSyncStatusSource(
            MutableLedgerCountsSource(), // never set → UNREAD
            permission,
            gallery,
            backgroundScope,
        )
        runCurrent()

        assertEquals(SyncStatus.Loading, source.status.value)
    }

    @Test
    fun `a read zero and a counted zero total do mint a Ready`() = runTest {
        // The other half of the rule: a counted zero is an ANSWER. A non-contributing membership must
        // still settle the screen, exactly as it did before (design D3).
        ledgerCounts.set(completed = 0, pending = 0)
        galleryCell.value = 0

        val source = source(backgroundScope)
        runCurrent()

        assertEquals(ready(pending = 0, completed = 0, total = 0), source.status.value)
    }

    @Test
    fun `once Ready the source never regresses to Loading`() = runTest {
        ledgerCounts.set(completed = 1, pending = 0)
        galleryCell.value = 2
        val source = source(backgroundScope)
        runCurrent()
        assertIs<SyncStatus.Ready>(source.status.value)

        // Nothing should be able to un-read an input, but the seam's contract is explicit that a source
        // MUST NOT regress once Ready — so the projection must not publish Loading a second time.
        galleryCell.value = null
        runCurrent()

        assertIs<SyncStatus.Ready>(source.status.value)
    }

    @Test
    fun `first Ready reflects completed and pending clamped to remaining`() = runTest {
        // pending high (1000) so it reads as the remaining; completed 2 of 5 → remaining 3.
        ledgerCounts.set(completed = 2, pending = 1000)
        galleryCell.value = 5

        val source = source(backgroundScope)
        runCurrent()

        assertEquals(ready(pending = 3, completed = 2, total = 5), source.status.value)
    }

    @Test
    fun `pending is the in-flight count when below remaining`() = runTest {
        ledgerCounts.set(completed = 2, pending = 2) // synced 2, remaining 5, only 2 in flight
        galleryCell.value = 7
        val source = source(backgroundScope)
        runCurrent()

        // pending = min(2, 5) = 2 — the real in-flight, not the remaining 5
        assertEquals(ready(pending = 2, completed = 2, total = 7), source.status.value)
    }

    @Test
    fun `pending is clamped to remaining when a deleted-but-unpruned photo over-counts`() = runTest {
        ledgerCounts.set(completed = 5, pending = 3) // synced 5, remaining 2, ledger reports 3 in flight
        galleryCell.value = 7
        val source = source(backgroundScope)
        runCurrent()

        // pending = min(3, 2) = 2 — never above remaining
        assertEquals(ready(pending = 2, completed = 5, total = 7), source.status.value)
    }

    @Test
    fun `an in-flight change re-mints pending`() = runTest {
        ledgerCounts.set(completed = 1, pending = 3)
        galleryCell.value = 6
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
        galleryCell.value = 4
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
        galleryCell.value = 1
        val source = source(backgroundScope)
        runCurrent()

        assertEquals(ready(pending = 0, completed = 3, total = 1), source.status.value)
    }

    @Test
    fun `a gallery change re-mints with the recomputed remaining`() = runTest {
        ledgerCounts.set(completed = 1, pending = 1000)
        galleryCell.value = 4
        val source = source(backgroundScope)
        runCurrent()
        assertEquals(ready(pending = 3, completed = 1, total = 4), source.status.value)

        galleryCell.value = 9
        runCurrent()

        assertEquals(ready(pending = 8, completed = 1, total = 9), source.status.value)
    }

    @Test
    fun `a permission flip re-mints with unchanged counts`() = runTest {
        ledgerCounts.set(completed = 1, pending = 1000)
        galleryCell.value = 4
        val source = source(backgroundScope)
        runCurrent()

        permission.state.value = PermissionStatus.DENIED
        runCurrent()

        assertEquals(ready(pending = 3, completed = 1, total = 4, active = false), source.status.value)
    }

    @Test
    fun `a limited grant is active`() = runTest {
        // Usable access (capability `limited-photo-access`): a partial grant is syncing, not blocked.
        ledgerCounts.set(completed = 1, pending = 1000)
        galleryCell.value = 4
        val source = source(backgroundScope)
        runCurrent()

        permission.state.value = PermissionStatus.LIMITED
        runCurrent()

        assertEquals(ready(pending = 3, completed = 1, total = 4, active = true), source.status.value)
    }

    @Test
    fun `the source never estimates and never gives up`() = runTest {
        ledgerCounts.set(completed = 0, pending = 1000)
        galleryCell.value = 1
        val source = source(backgroundScope)
        runCurrent()

        val progress = assertIs<SyncStatus.Ready>(source.status.value).progress
        assertEquals(0, progress.failed)
        assertEquals(null, progress.estimatedRemaining)
        assertEquals(1, progress.pending)
        assertEquals(1, progress.total)
    }
}

private class FakePermissionSource(initial: PermissionStatus) : PhotoAccessStatusSource {
    val state = MutableStateFlow(initial)
    override val permission: StateFlow<PermissionStatus> = state
}
