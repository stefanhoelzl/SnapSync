@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.feature.status

import app.snapsync.model.AssetId
import app.snapsync.model.SyncProgress
import app.snapsync.model.SyncStatus

import app.snapsync.feature.support.galleryAccess
import app.snapsync.model.GalleryAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LedgerBackedSyncStatusSourceTest {

    private val ledgerCounts = MutableLedgerCountsSource()
    // The photo-permission port's cell, read through the real grant service.
    private val grant = MutableStateFlow(GalleryAccess.GRANTED)
    private val permission = galleryAccess(grant)
    // The own-device admitted set the gallery counted (`OwnDeviceGalleryStatusSource.admitted`) — the test owns it.
    private val galleryCell = MutableStateFlow<Set<AssetId>?>(emptySet())

    private fun ready(
        pending: Int = 0,
        completed: Int = 0,
        total: Int = 0,
        active: Boolean = true,
    ) = SyncStatus.Ready(SyncProgress(pending, completed, total, failed = 0, active, estimatedRemaining = null))

    /** `n` distinct asset ids `<prefix>1..<prefix>n` — the admitted set, or a slice of the ledger's. */
    private fun ids(prefix: String, n: Int): Set<AssetId> = (1..n).mapTo(mutableSetOf()) { AssetId("$prefix$it") }

    private fun source(scope: kotlinx.coroutines.CoroutineScope) =
        LedgerBackedSyncStatusSource(ledgerCounts, permission, galleryCell, scope)

    @Test
    fun `initial value is Loading before the first read`() = runTest {
        ledgerCounts.set(done = setOf(AssetId("a1")), pending = emptySet())
        galleryCell.value = ids("a", 3)

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
            MutableStateFlow(null), // seeded null — never enumerated
            backgroundScope,
        )
        runCurrent()

        assertEquals(SyncStatus.Loading, unread.status.value)
    }

    @Test
    fun `an unread gallery total alone holds the source at Loading`() = runTest {
        ledgerCounts.set(done = setOf(AssetId("a1")), pending = emptySet()) // read
        galleryCell.value = null // not counted

        val source = source(backgroundScope)
        runCurrent()

        assertEquals(SyncStatus.Loading, source.status.value)
    }

    @Test
    fun `unread ledger counts alone hold the source at Loading`() = runTest {
        galleryCell.value = ids("a", 3) // counted
        val source = LedgerBackedSyncStatusSource(
            MutableLedgerCountsSource(), // never set → UNREAD
            permission,
            galleryCell,
            backgroundScope,
        )
        runCurrent()

        assertEquals(SyncStatus.Loading, source.status.value)
    }

    @Test
    fun `a read zero and a counted zero total do mint a Ready`() = runTest {
        // The other half of the rule: a counted zero is an ANSWER. A non-contributing membership must
        // still settle the screen, exactly as it did before (design D3).
        ledgerCounts.set(done = emptySet(), pending = emptySet())
        galleryCell.value = emptySet()

        val source = source(backgroundScope)
        runCurrent()

        assertEquals(ready(pending = 0, completed = 0, total = 0), source.status.value)
    }

    @Test
    fun `once Ready the source never regresses to Loading`() = runTest {
        ledgerCounts.set(done = setOf(AssetId("a1")), pending = emptySet())
        galleryCell.value = ids("a", 2)
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
        // Every remaining admitted photo in flight, plus 1000 not-done rows the membership does not admit:
        // completed 2 of 5 → remaining 3, and only the admitted 3 count as pending.
        ledgerCounts.set(done = setOf(AssetId("a1"), AssetId("a2")), pending = setOf(AssetId("a3"), AssetId("a4"), AssetId("a5")) + ids("x", 1000))
        galleryCell.value = ids("a", 5)

        val source = source(backgroundScope)
        runCurrent()

        assertEquals(ready(pending = 3, completed = 2, total = 5), source.status.value)
    }

    @Test
    fun `pending is the in-flight count when below remaining`() = runTest {
        // synced 2, remaining 5, only 2 in flight
        ledgerCounts.set(done = setOf(AssetId("a1"), AssetId("a2")), pending = setOf(AssetId("a3"), AssetId("a4")))
        galleryCell.value = ids("a", 7)
        val source = source(backgroundScope)
        runCurrent()

        // pending = min(2, 5) = 2 — the real in-flight, not the remaining 5
        assertEquals(ready(pending = 2, completed = 2, total = 7), source.status.value)
    }

    @Test
    fun `pending is clamped to remaining when a deleted-but-unpruned photo over-counts`() = runTest {
        // synced 5, remaining 2, ledger reports 3 in flight — one of them a photo deleted from the
        // library (no longer admitted) whose row was not yet pruned
        ledgerCounts.set(done = ids("a", 5), pending = setOf(AssetId("a6"), AssetId("a7"), AssetId("deleted")))
        galleryCell.value = ids("a", 7)
        val source = source(backgroundScope)
        runCurrent()

        // pending = the 2 admitted in-flight photos — the deleted one is outside the set, never above remaining
        assertEquals(ready(pending = 2, completed = 5, total = 7), source.status.value)
    }

    @Test
    fun `an in-flight change re-mints pending`() = runTest {
        ledgerCounts.set(done = setOf(AssetId("a1")), pending = setOf(AssetId("a2"), AssetId("a3"), AssetId("a4")))
        galleryCell.value = ids("a", 6)
        val source = source(backgroundScope)
        runCurrent()
        assertEquals(ready(pending = 3, completed = 1, total = 6), source.status.value)

        ledgerCounts.set(done = setOf(AssetId("a1")), pending = setOf(AssetId("a2")))
        runCurrent()

        assertEquals(ready(pending = 1, completed = 1, total = 6), source.status.value)
    }

    @Test
    fun `a newly complete asset re-mints completed and shrinks remaining`() = runTest {
        ledgerCounts.set(done = emptySet(), pending = ids("a", 4))
        galleryCell.value = ids("a", 4)
        val source = source(backgroundScope)
        runCurrent()
        assertEquals(ready(pending = 4, total = 4), source.status.value)

        ledgerCounts.set(done = setOf(AssetId("a1")), pending = ids("a", 4) - AssetId("a1"))
        runCurrent()

        assertEquals(ready(pending = 3, completed = 1, total = 4), source.status.value)
    }

    @Test
    fun `pending is zero when completed meets or exceeds the live total`() = runTest {
        // The gallery total can momentarily lag the ledger: rows done for photos the admitted set does not
        // (yet, or any more) hold. Counting over the admitted set keeps completed <= total structurally, so
        // completed meets the total and nothing reads as pending.
        ledgerCounts.set(done = setOf(AssetId("a1"), AssetId("a2"), AssetId("a3")), pending = ids("x", 1000))
        galleryCell.value = setOf(AssetId("a1"))
        val source = source(backgroundScope)
        runCurrent()

        assertEquals(ready(pending = 0, completed = 1, total = 1), source.status.value)
    }

    @Test
    fun `a gallery change re-mints with the recomputed remaining`() = runTest {
        ledgerCounts.set(done = setOf(AssetId("a1")), pending = ids("a", 9) - AssetId("a1"))
        galleryCell.value = ids("a", 4)
        val source = source(backgroundScope)
        runCurrent()
        assertEquals(ready(pending = 3, completed = 1, total = 4), source.status.value)

        galleryCell.value = ids("a", 9)
        runCurrent()

        assertEquals(ready(pending = 8, completed = 1, total = 9), source.status.value)
    }

    @Test
    fun `a permission flip re-mints with unchanged counts`() = runTest {
        ledgerCounts.set(done = setOf(AssetId("a1")), pending = ids("a", 4) - AssetId("a1"))
        galleryCell.value = ids("a", 4)
        val source = source(backgroundScope)
        runCurrent()

        grant.value = GalleryAccess.DENIED
        runCurrent()

        assertEquals(ready(pending = 3, completed = 1, total = 4, active = false), source.status.value)
    }

    @Test
    fun `a limited grant is active`() = runTest {
        // Usable access (capability `photo-access`): a partial grant is syncing, not blocked.
        ledgerCounts.set(done = setOf(AssetId("a1")), pending = ids("a", 4) - AssetId("a1"))
        galleryCell.value = ids("a", 4)
        val source = source(backgroundScope)
        runCurrent()

        grant.value = GalleryAccess.LIMITED
        runCurrent()

        assertEquals(ready(pending = 3, completed = 1, total = 4, active = true), source.status.value)
    }

    @Test
    fun `the source never estimates and never gives up`() = runTest {
        ledgerCounts.set(done = emptySet(), pending = setOf(AssetId("a1")))
        galleryCell.value = setOf(AssetId("a1"))
        val source = source(backgroundScope)
        runCurrent()

        val progress = assertIs<SyncStatus.Ready>(source.status.value).progress
        assertEquals(0, progress.failed)
        assertEquals(null, progress.estimatedRemaining)
        assertEquals(1, progress.pending)
        assertEquals(1, progress.total)
    }

    // ---- Counted over the admitted set (capability `sync-status`, design D5) -----------------------

    @Test
    fun `historical done rows outside the admitted set do not mask a pending in-window photo`() = runTest {
        // The masking bug this counts-over-the-admitted-set rule fixes. The join-time load seeds a done row
        // for everything this device ever stored, so a whole-ledger count read 1,402 completed against a
        // total of 3 — `synced >= total` — and settled "In sync" while `z` was still uploading.
        val historical = ids("old", 1_400)
        ledgerCounts.set(done = setOf(AssetId("x"), AssetId("y")) + historical, pending = setOf(AssetId("z")))
        galleryCell.value = setOf(AssetId("x"), AssetId("y"), AssetId("z"))

        val source = source(backgroundScope)
        runCurrent()

        val progress = assertIs<SyncStatus.Ready>(source.status.value).progress
        assertEquals(ready(pending = 1, completed = 2, total = 3), source.status.value)
        assertTrue(progress.completed < progress.total, "one admitted photo is still pending — not in sync")
    }

    @Test
    fun `a bare done row for an admitted photo counts as completed as soon as the set is counted`() = runTest {
        // A listing-loaded row: COMPLETED, no job history, nothing else known about it. Before the gallery
        // has counted, nothing is minted; the moment the admitted set arrives holding it, it is completed.
        ledgerCounts.set(done = setOf(AssetId("loaded")), pending = emptySet())
        galleryCell.value = null
        val source = source(backgroundScope)
        runCurrent()
        assertEquals(SyncStatus.Loading, source.status.value)

        galleryCell.value = setOf(AssetId("loaded"))
        runCurrent()

        assertEquals(ready(pending = 0, completed = 1, total = 1), source.status.value)
    }

    @Test
    fun `an admitted photo with no ledger row is neither completed nor pending`() = runTest {
        // Undiscovered: admitted by the policy, but no job created yet. It stays in the remainder —
        // counted in the total, not done, and not claimed as in flight.
        ledgerCounts.set(done = setOf(AssetId("a1")), pending = setOf(AssetId("a2")))
        galleryCell.value = setOf(AssetId("a1"), AssetId("a2"), AssetId("undiscovered"))

        val source = source(backgroundScope)
        runCurrent()

        assertEquals(ready(pending = 1, completed = 1, total = 3), source.status.value)
    }

    @Test
    fun `an uncounted admitted set holds Loading even over a read ledger that has everything done`() = runTest {
        // Read counts alone never mint a snapshot: without the admitted set there is nothing to count them
        // over, and a done-heavy ledger must not stand in for a total.
        ledgerCounts.set(done = ids("a", 50), pending = emptySet())
        galleryCell.value = null

        val source = source(backgroundScope)
        runCurrent()

        assertEquals(SyncStatus.Loading, source.status.value)
    }
}
